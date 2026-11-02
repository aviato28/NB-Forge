package com.namanbansal.forge.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** A file written to `Downloads/Forge/`. */
data class SavedOutput(val uri: Uri, val displayName: String, val size: Long, val mime: String)

/**
 * Writes tool results into `Downloads/Forge/` through MediaStore (no storage permission needed on API 30+).
 * Outputs are always new files; existing ones are never overwritten.
 */
class OutputSaver(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /**
     * Creates `<original>_<action>.<ext>` (de-duplicated) and streams [write] into it.
     * The entry stays invisible (IS_PENDING) until fully written; on any failure or cancellation it is deleted.
     */
    suspend fun save(
        originalName: String,
        action: String,
        ext: String,
        mime: String,
        write: suspend (OutputStream) -> Unit,
    ): SavedOutput = withContext(Dispatchers.IO) {
        val name = OutputNaming.build(originalName, action, ext) { exists(it) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads/Forge")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the output file")
            out.use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            SavedOutput(uri, name, sizeOf(uri), mime)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun exists(displayName: String): Boolean = try {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(displayName, RELATIVE_PATH),
            null,
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) {
        false
    }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /** True if [uri] still points at a readable file (used to grey out stale Recent entries). */
    suspend fun isReadable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Copies [source] into a user-chosen destination (the "Save as" action). */
    suspend fun copyTo(source: Uri, destination: Uri) = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(source) ?: throw IOException("Could not read the file")
        val output = resolver.openOutputStream(destination, "w") ?: throw IOException("Could not write the file")
        input.use { i -> output.use { o -> i.copyTo(o) } }
    }

    companion object {
        const val RELATIVE_PATH = "Download/Forge/"
        const val FOLDER_LABEL = "Downloads/Forge"
    }
}
