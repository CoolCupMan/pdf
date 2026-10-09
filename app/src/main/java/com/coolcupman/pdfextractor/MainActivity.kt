package com.coolcupman.pdfextractor

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // PDFBox ist nicht thread-sicher: alle PDF-Zugriffe auf einem Thread.
    private val pdfExecutor = Executors.newSingleThreadExecutor()
    private val pdfDispatcher = pdfExecutor.asCoroutineDispatcher()

    private var document: PDDocument? = null
    private var sourceName = "dokument"
    private val pageTextCache = HashMap<Int, String>()
    private var stripper: PDFTextStripper? = null
    private var pendingRange: PageRange? = null

    private lateinit var fileInfo: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var fractionInput: EditText
    private lateinit var toEndCheck: CheckBox
    private lateinit var includeEndCheck: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var actionButtons: List<Button>

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadPdf(uri)
    }
    private val savePdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val range = pendingRange
            if (uri != null && range != null) writePdf(uri, range)
        }
    private val saveTxt =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val range = pendingRange
            if (uri != null && range != null) writeTxt(uri, range)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContentView(R.layout.activity_main)

        fileInfo = findViewById(R.id.fileInfo)
        startInput = findViewById(R.id.startInput)
        endInput = findViewById(R.id.endInput)
        fractionInput = findViewById(R.id.fractionInput)
        toEndCheck = findViewById(R.id.toEndCheck)
        includeEndCheck = findViewById(R.id.includeEndCheck)
        progressBar = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        val pickButton = findViewById<Button>(R.id.pickButton)
        val checkButton = findViewById<Button>(R.id.checkButton)
        val extractButton = findViewById<Button>(R.id.extractButton)
        val txtButton = findViewById<Button>(R.id.txtButton)
        actionButtons = listOf(checkButton, extractButton, txtButton)

        pickButton.setOnClickListener { openPdf.launch(arrayOf("application/pdf")) }
        checkButton.setOnClickListener { resolveRange { showRange(it) } }
        extractButton.setOnClickListener {
            resolveRange { range ->
                showRange(range)
                pendingRange = range
                savePdf.launch("${sourceName}_S${range.start}-${range.end}.pdf")
            }
        }
        txtButton.setOnClickListener {
            resolveRange { range ->
                showRange(range)
                pendingRange = range
                saveTxt.launch("${sourceName}_S${range.start}-${range.end}.txt")
            }
        }
        toEndCheck.setOnCheckedChangeListener { _, checked ->
            endInput.isEnabled = !checked
            fractionInput.isEnabled = !checked
        }

        findViewById<TextView>(R.id.versionInfo).text =
            getString(R.string.version_info, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID)
        setActionsEnabled(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        val doc = document
        document = null
        pdfExecutor.execute { doc?.close() }
        pdfExecutor.shutdown()
    }

    private fun loadPdf(uri: Uri) {
        setBusy(true, getString(R.string.loading))
        lifecycleScope.launch {
            try {
                val total = withContext(pdfDispatcher) {
                    document?.close()
                    document = null
                    pageTextCache.clear()

                    // Zuerst in den Cache kopieren: schneller wahlfreier Zugriff auch bei großen PDFs.
                    val file = File(cacheDir, "input.pdf")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val doc = PDDocument.load(file)
                    doc.isAllSecurityToBeRemoved = true
                    document = doc
                    stripper = PDFTextStripper()
                    doc.numberOfPages
                }
                sourceName = displayName(uri)
                fileInfo.text = getString(R.string.file_info, sourceName, total)
                setBusy(false, getString(R.string.loaded, total))
                setActionsEnabled(true)
            } catch (e: Exception) {
                setBusy(false, getString(R.string.error, e.message ?: e.javaClass.simpleName))
                setActionsEnabled(false)
            }
        }
    }

    private fun resolveRange(onResolved: (PageRange) -> Unit) {
        val doc = document ?: return
        val startText = startInput.text.toString()
        val endText = endInput.text.toString()
        val fractionText = fractionInput.text.toString()
        val toEnd = toEndCheck.isChecked
        val includeEnd = includeEndCheck.isChecked

        setBusy(true, getString(R.string.searching))
        lifecycleScope.launch {
            try {
                val range = withContext(pdfDispatcher) {
                    PageRangeResolver(
                        total = doc.numberOfPages,
                        pageText = { page -> pageText(doc, page) },
                        progress = { page, total -> postProgress(page, total) },
                    ).resolve(startText, endText, fractionText, toEnd, includeEnd)
                }
                setBusy(false, null)
                onResolved(range)
            } catch (e: RangeException) {
                setBusy(false, e.message)
            } catch (e: Exception) {
                setBusy(false, getString(R.string.error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Muss auf [pdfDispatcher] laufen. */
    private fun pageText(doc: PDDocument, page: Int): String =
        pageTextCache.getOrPut(page) {
            val s = stripper ?: PDFTextStripper().also { stripper = it }
            s.startPage = page
            s.endPage = page
            s.getText(doc)
        }

    private fun postProgress(page: Int, total: Int) {
        runOnUiThread {
            progressBar.isIndeterminate = false
            progressBar.max = total
            progressBar.progress = page
            status.text = getString(R.string.search_progress, page, total)
        }
    }

    private fun showRange(r: PageRange) {
        status.text = getString(
            R.string.range_info, r.start, r.end, r.count, r.total, r.startInfo, r.endInfo
        )
    }

    private fun writePdf(uri: Uri, range: PageRange) {
        val doc = document ?: return
        setBusy(true, getString(R.string.writing))
        lifecycleScope.launch {
            try {
                withContext(pdfDispatcher) {
                    val parts = Splitter().apply {
                        setStartPage(range.start)
                        setEndPage(range.end)
                        setSplitAtPage(range.count)
                    }.split(doc)
                    val part = parts.first()
                    try {
                        contentResolver.openOutputStream(uri)!!.use { part.save(it) }
                    } finally {
                        parts.forEach { it.close() }
                    }
                }
                setBusy(false, getString(R.string.saved_pdf, range.start, range.end, range.count))
            } catch (e: Exception) {
                setBusy(false, getString(R.string.error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun writeTxt(uri: Uri, range: PageRange) {
        val doc = document ?: return
        setBusy(true, getString(R.string.writing))
        lifecycleScope.launch {
            try {
                withContext(pdfDispatcher) {
                    contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                        for (p in range.start..range.end) {
                            postProgress(p, range.end)
                            w.write("===== Seite $p =====\n")
                            w.write(pageText(doc, p))
                            w.write("\n")
                        }
                    }
                }
                setBusy(false, getString(R.string.saved_txt, range.start, range.end))
            } catch (e: Exception) {
                setBusy(false, getString(R.string.error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
        return (name ?: "dokument").removeSuffix(".pdf").removeSuffix(".PDF")
    }

    private fun setBusy(busy: Boolean, message: String?) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = true
        if (message != null) status.text = message
        setActionsEnabled(!busy && document != null)
    }

    private fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }
}
