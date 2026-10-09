package com.coolcupman.pdfextractor

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class ExportKind(val mime: String, val ext: String) {
    PDF("application/pdf", "pdf"),
    TXT("text/plain", "txt"),
}

/** Alle Eingaben aus der Oberfläche. */
data class RangeInputs(
    val start: String,
    val end: String,
    val fromFraction: String,
    val toFraction: String,
    val toEnd: Boolean,
    val includeEndMatch: Boolean,
)

data class UiState(
    val fileInfo: String? = null,
    val status: String? = null,
    val busy: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val hasDocument: Boolean = false,
    val exportFolder: Uri? = null,
    val lastOutput: Uri? = null,
    val lastOutputMime: String? = null,
)

sealed interface UiEvent {
    /** Kein (nutzbarer) Exportordner: Speicherort per Dialog erfragen. */
    data class AskSaveLocation(val kind: ExportKind, val fileName: String) : UiEvent
}

class ExtractorViewModel(app: Application) : AndroidViewModel(app) {

    // PDFBox ist nicht thread-sicher: alle PDF-Zugriffe auf einem Thread.
    private val pdfExecutor = Executors.newSingleThreadExecutor()
    private val pdfDispatcher = pdfExecutor.asCoroutineDispatcher()

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var document: PDDocument? = null
    private var sourceName = "dokument"
    private val pageTextCache = HashMap<Int, String>()
    private var stripper: PDFTextStripper? = null
    private var pending: Pair<ExportKind, PageRange>? = null

    private val _state = MutableStateFlow(
        UiState(exportFolder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    )
    val state: StateFlow<UiState> = _state

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        PDFBoxResourceLoader.init(app)
    }

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    override fun onCleared() {
        val doc = document
        document = null
        pdfExecutor.execute { doc?.close() }
        pdfExecutor.shutdown()
    }

    // ---------- Import ----------

    fun loadPdf(uri: Uri) {
        val ctx = getApplication<Application>()
        setBusy(str(R.string.loading))
        viewModelScope.launch {
            try {
                val total = withContext(pdfDispatcher) {
                    document?.close()
                    document = null
                    pageTextCache.clear()

                    // In den Cache kopieren: schneller wahlfreier Zugriff auch bei großen PDFs.
                    val file = File(ctx.cacheDir, "input.pdf")
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Datei kann nicht gelesen werden")
                    input.use { i -> file.outputStream().use { i.copyTo(it) } }
                    val doc = PDDocument.load(file)
                    doc.isAllSecurityToBeRemoved = true
                    document = doc
                    stripper = PDFTextStripper()
                    sourceName = displayName(uri)
                    doc.numberOfPages to file.length()
                }
                val size = Formatter.formatShortFileSize(ctx, total.second)
                _state.update {
                    it.copy(
                        fileInfo = str(R.string.file_info, sourceName, total.first, size),
                        status = str(R.string.loaded, total.first),
                        busy = false, progress = null, hasDocument = true, lastOutput = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = errorText(e), busy = false, progress = null,
                        hasDocument = document != null,
                    )
                }
            }
        }
    }

    // ---------- Exportordner ----------

    fun setExportFolder(tree: Uri) {
        prefs.edit().putString(KEY_FOLDER, tree.toString()).apply()
        _state.update { it.copy(exportFolder = tree) }
    }

    // ---------- Bereich ----------

    fun check(inputs: RangeInputs) {
        launchResolved(inputs) { range ->
            _state.update { it.copy(status = rangeText(range), busy = false, progress = null) }
        }
    }

    fun export(kind: ExportKind, inputs: RangeInputs, fileNameInput: String) {
        launchResolved(inputs) { range ->
            val name = buildFileName(fileNameInput, range, kind)
            val folder = _state.value.exportFolder
            val target = folder?.let { createInFolder(it, kind, name) }
            if (target != null) {
                write(target, kind, range)
            } else {
                // Kein Ordner gewählt oder Berechtigung verloren -> Speicherort erfragen.
                pending = kind to range
                _state.update { it.copy(status = rangeText(range), busy = false, progress = null) }
                _events.send(UiEvent.AskSaveLocation(kind, name))
            }
        }
    }

    /** Ergebnis des "Speichern unter"-Dialogs. */
    fun onSaveLocationChosen(uri: Uri?) {
        val (kind, range) = pending ?: return
        pending = null
        if (uri != null) viewModelScope.launch { write(uri, kind, range) }
    }

    private fun launchResolved(inputs: RangeInputs, onResolved: suspend (PageRange) -> Unit) {
        val doc = document ?: return
        setBusy(str(R.string.searching))
        viewModelScope.launch {
            try {
                val range = withContext(pdfDispatcher) {
                    PageRangeResolver(
                        total = doc.numberOfPages,
                        pageText = { page -> pageText(doc, page) },
                        progress = { page, total -> postProgress(page, total) },
                    ).resolve(
                        inputs.start, inputs.end, inputs.fromFraction, inputs.toFraction,
                        inputs.toEnd, inputs.includeEndMatch,
                    )
                }
                onResolved(range)
            } catch (e: RangeException) {
                _state.update { it.copy(status = e.message, busy = false, progress = null) }
            } catch (e: Exception) {
                _state.update { it.copy(status = errorText(e), busy = false, progress = null) }
            }
        }
    }

    private suspend fun write(uri: Uri, kind: ExportKind, range: PageRange) {
        val doc = document ?: return
        val ctx = getApplication<Application>()
        setBusy(str(R.string.writing))
        try {
            withContext(pdfDispatcher) {
                val out = ctx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Zieldatei kann nicht geschrieben werden")
                out.use { stream ->
                    when (kind) {
                        ExportKind.PDF -> {
                            val parts = Splitter().apply {
                                setStartPage(range.start)
                                setEndPage(range.end)
                                setSplitAtPage(range.count)
                            }.split(doc)
                            try {
                                parts.first().save(stream)
                            } finally {
                                parts.forEach { it.close() }
                            }
                        }
                        ExportKind.TXT -> stream.bufferedWriter().let { w ->
                            for (p in range.start..range.end) {
                                postProgress(p, range.end)
                                w.write("===== Seite $p =====\n")
                                w.write(pageText(doc, p))
                                w.write("\n")
                            }
                            w.flush()
                        }
                    }
                }
            }
            val where = StoragePaths.describeFile(ctx, uri)
            _state.update {
                it.copy(
                    status = str(R.string.saved_to, range.start, range.end, range.count, where),
                    busy = false, progress = null, lastOutput = uri, lastOutputMime = kind.mime,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = errorText(e), busy = false, progress = null) }
        }
    }

    private fun createInFolder(tree: Uri, kind: ExportKind, name: String): Uri? = try {
        DocumentFile.fromTreeUri(getApplication(), tree)
            ?.takeIf { it.canWrite() }
            ?.createFile(kind.mime, name)
            ?.uri
    } catch (e: Exception) {
        null
    }

    // ---------- Hilfen ----------

    /** Muss auf [pdfDispatcher] laufen. */
    private fun pageText(doc: PDDocument, page: Int): String =
        pageTextCache.getOrPut(page) {
            val s = stripper ?: PDFTextStripper().also { stripper = it }
            s.startPage = page
            s.endPage = page
            s.getText(doc)
        }

    private fun postProgress(page: Int, total: Int) {
        _state.update {
            it.copy(progress = page to total, status = str(R.string.search_progress, page, total))
        }
    }

    private fun setBusy(message: String) {
        _state.update { it.copy(busy = true, progress = null, status = message) }
    }

    private fun rangeText(r: PageRange) =
        str(R.string.range_info, r.start, r.end, r.count, r.total, r.startInfo, r.endInfo)

    private fun errorText(e: Exception) = str(R.string.error, e.message ?: e.javaClass.simpleName)

    private fun buildFileName(input: String, range: PageRange, kind: ExportKind): String {
        val base = input.trim()
            .removeSuffix(".${kind.ext}").removeSuffix(".pdf").removeSuffix(".txt")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "${sourceName}_S${range.start}-${range.end}" }
        return "$base.${kind.ext}"
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) name = it.getString(0) }
        } catch (e: Exception) {
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return (name ?: "dokument").replace(Regex("""\.pdf$""", RegexOption.IGNORE_CASE), "")
    }

    private companion object {
        const val KEY_FOLDER = "export_folder"
    }
}
