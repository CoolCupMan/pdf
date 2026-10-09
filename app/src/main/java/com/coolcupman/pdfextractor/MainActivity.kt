package com.coolcupman.pdfextractor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val vm: ExtractorViewModel by viewModels()

    private lateinit var fileInfo: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var fromFractionInput: EditText
    private lateinit var toFractionInput: EditText
    private lateinit var toEndCheck: CheckBox
    private lateinit var includeEndCheck: CheckBox
    private lateinit var folderInfo: TextView
    private lateinit var filenameInput: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var resultActions: View
    private lateinit var documentButtons: List<MaterialButton>
    private lateinit var importButton: MaterialButton
    private lateinit var folderButton: MaterialButton

    // Dateiauswahl des Systems (Downloads, Google Files, Drive, SD-Karte …)
    private val importPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.loadPdf(uri)
    }

    private val chooseFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                }
                vm.setExportFolder(tree)
            }
        }

    private val savePdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.PDF.mime)) {
            vm.onSaveLocationChosen(it)
        }
    private val saveTxt =
        registerForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.TXT.mime)) {
            vm.onSaveLocationChosen(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        applyInsets(findViewById(R.id.root), toolbar, findViewById(R.id.scroll))

        fileInfo = findViewById(R.id.fileInfo)
        startInput = findViewById(R.id.startInput)
        endInput = findViewById(R.id.endInput)
        fromFractionInput = findViewById(R.id.fromFractionInput)
        toFractionInput = findViewById(R.id.toFractionInput)
        toEndCheck = findViewById(R.id.toEndCheck)
        includeEndCheck = findViewById(R.id.includeEndCheck)
        folderInfo = findViewById(R.id.folderInfo)
        filenameInput = findViewById(R.id.filenameInput)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        resultActions = findViewById(R.id.resultActions)
        importButton = findViewById(R.id.importButton)
        folderButton = findViewById(R.id.folderButton)

        val checkButton = findViewById<MaterialButton>(R.id.checkButton)
        val extractButton = findViewById<MaterialButton>(R.id.extractButton)
        val txtButton = findViewById<MaterialButton>(R.id.txtButton)
        documentButtons = listOf(checkButton, extractButton, txtButton)

        importButton.setOnClickListener { importPdf.launch("application/pdf") }
        folderButton.setOnClickListener { chooseFolder.launch(vm.state.value.exportFolder) }
        checkButton.setOnClickListener { vm.check(inputs()) }
        extractButton.setOnClickListener {
            vm.export(ExportKind.PDF, inputs(), filenameInput.text.toString())
        }
        txtButton.setOnClickListener {
            vm.export(ExportKind.TXT, inputs(), filenameInput.text.toString())
        }
        findViewById<MaterialButton>(R.id.openButton).setOnClickListener { openResult(share = false) }
        findViewById<MaterialButton>(R.id.shareButton).setOnClickListener { openResult(share = true) }

        toEndCheck.setOnCheckedChangeListener { _, _ -> updateEndFields() }
        updateEndFields()

        findViewById<TextView>(R.id.versionInfo).text = getString(
            R.string.version_info,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect(::render) }
                launch {
                    vm.events.collect { e ->
                        when (e) {
                            is UiEvent.AskSaveLocation -> when (e.kind) {
                                ExportKind.PDF -> savePdf.launch(e.fileName)
                                ExportKind.TXT -> saveTxt.launch(e.fileName)
                            }
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** PDF aus "Öffnen mit" oder "Teilen" (z. B. Google Files). */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) vm.loadPdf(uri)
    }

    /** Edge-to-Edge (Pflicht ab Android 15): Inhalt von System- und Kamera-Leisten und Tastatur freihalten. */
    private fun applyInsets(root: View, toolbar: View, scroll: NestedScrollView) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            scroll.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun inputs() = RangeInputs(
        start = startInput.text.toString(),
        end = endInput.text.toString(),
        fromFraction = fromFractionInput.text.toString(),
        toFraction = toFractionInput.text.toString(),
        toEnd = toEndCheck.isChecked,
        includeEndMatch = includeEndCheck.isChecked,
    )

    private fun updateEndFields() {
        val manualEnd = !toEndCheck.isChecked
        endInput.isEnabled = manualEnd
        toFractionInput.isEnabled = manualEnd
    }

    private fun render(s: UiState) {
        fileInfo.text = s.fileInfo ?: getString(R.string.no_file)
        s.status?.let { status.text = it }

        progress.visibility = if (s.busy) View.VISIBLE else View.GONE
        val p = s.progress
        if (p != null) {
            progress.isIndeterminate = false
            progress.max = p.second
            progress.setProgressCompat(p.first, false)
        } else {
            progress.isIndeterminate = true
        }

        importButton.isEnabled = !s.busy
        folderButton.isEnabled = !s.busy
        documentButtons.forEach { it.isEnabled = !s.busy && s.hasDocument }

        folderInfo.text = s.exportFolder
            ?.let { getString(R.string.folder_info, StoragePaths.describeFolder(this, it)) }
            ?: getString(R.string.no_folder)

        resultActions.visibility = if (s.lastOutput != null && !s.busy) View.VISIBLE else View.GONE
    }

    private fun openResult(share: Boolean) {
        val s = vm.state.value
        val uri = s.lastOutput ?: return
        val mime = s.lastOutputMime ?: "*/*"
        val intent = if (share) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            )
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }
}
