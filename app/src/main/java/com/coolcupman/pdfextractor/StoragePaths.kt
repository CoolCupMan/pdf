package com.coolcupman.pdfextractor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Lesbare Pfadangaben für Ordner-/Datei-URIs des Storage Access Frameworks. */
object StoragePaths {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun describeFolder(ctx: Context, tree: Uri): String =
        try {
            if (tree.authority == EXTERNAL_STORAGE) {
                readable(ctx, DocumentsContract.getTreeDocumentId(tree))
            } else {
                DocumentFile.fromTreeUri(ctx, tree)?.name ?: tree.toString()
            }
        } catch (e: Exception) {
            tree.toString()
        }

    fun describeFile(ctx: Context, uri: Uri): String =
        try {
            if (uri.authority == EXTERNAL_STORAGE) {
                readable(ctx, DocumentsContract.getDocumentId(uri))
            } else {
                DocumentFile.fromSingleUri(ctx, uri)?.name ?: uri.toString()
            }
        } catch (e: Exception) {
            uri.toString()
        }

    /** "primary:Download/PDF" -> "Interner Speicher/Download/PDF" */
    private fun readable(ctx: Context, docId: String): String {
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "")
        val root = if (volume == "primary") {
            ctx.getString(R.string.internal_storage)
        } else {
            "${ctx.getString(R.string.sd_card)} ($volume)"
        }
        return if (path.isEmpty()) root else "$root/$path"
    }
}
