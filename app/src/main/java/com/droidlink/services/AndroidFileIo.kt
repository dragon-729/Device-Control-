package com.droidlink.services

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [FileIo] backed by the Storage Access Framework: only files the user explicitly picked are reachable. */
class AndroidFileIo(private val resolver: ContentResolver) : FileIo {

    override fun displayName(handle: String): String =
        queryColumn(handle, OpenableColumns.DISPLAY_NAME) ?: Uri.parse(handle).lastPathSegment ?: "file"

    override fun size(handle: String): Long {
        queryColumn(handle, OpenableColumns.SIZE)?.toLongOrNull()?.let { if (it >= 0) return it }
        return try {
            resolver.openAssetFileDescriptor(Uri.parse(handle), "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    override fun openInput(handle: String): InputStream =
        resolver.openInputStream(Uri.parse(handle)) ?: throw IOException("Cannot open the selected file")

    override fun openOutput(handle: String): OutputStream =
        resolver.openOutputStream(Uri.parse(handle), "wt") ?: throw IOException("Cannot write to the selected location")

    override fun delete(handle: String) {
        try {
            DocumentsContract.deleteDocument(resolver, Uri.parse(handle))
        } catch (e: Exception) {
            // Not deletable through SAF (e.g. provider without delete support): leave as is.
        }
    }

    private fun queryColumn(handle: String, column: String): String? = try {
        resolver.query(Uri.parse(handle), arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
