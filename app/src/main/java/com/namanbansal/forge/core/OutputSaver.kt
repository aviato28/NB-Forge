package com.namanbansal.forge.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.FilterOutputStream
import java.io.OutputStream

/** A file written to `Downloads/Forge/`. */
data class SavedOutput(val uri: Uri, val displayName: String, val size: Long, val mime: String, val folder: String = OutputSaver.FOLDER_LABEL)

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
        onSaved: (SavedOutput) -> Unit = {},
        subDir: String = "",
        /** Images go to `Pictures/Forge/` (gallery) instead of Downloads. */
        images: Boolean = false,
        /** Use this exact file name (de-duplicated) instead of `<original>_<action>.<ext>` (unzip keeps entry names). */
        rawName: String? = null,
        write: suspend (OutputStream) -> Unit,
    ): SavedOutput = create(mime, subDir, onSaved, { path, coll ->
        if (rawName != null) OutputNaming.unique(rawName) { exists(it, path, coll) } else OutputNaming.build(originalName, action, ext) { exists(it, path, coll) }
    }, write, images)

    /**
     * Saves a file under its own name (`invoice.pdf`, de-duplicated as `invoice (1).pdf`), for "Save to phone".
     * Same pending / rollback behaviour as [save].
     */
    suspend fun saveKeepingName(
        name: String,
        mime: String,
        subDir: String = "",
        write: suspend (OutputStream) -> Unit,
    ): SavedOutput = create(mime, subDir, {}, { path, coll -> OutputNaming.keep(name) { exists(it, path, coll) } }, write)

    private suspend fun create(
        mime: String,
        subDir: String,
        onSaved: (SavedOutput) -> Unit,
        nameFor: (String, Uri) -> String,
        write: suspend (OutputStream) -> Unit,
        images: Boolean = false,
    ): SavedOutput = withContext(Dispatchers.IO) {
        val base = if (images) PICTURES_PATH else RELATIVE_PATH
        val path = base + subDir
        val collection = if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = nameFor(path, collection)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create a file in Downloads/Forge")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the output file")
            val counting = CountingOutputStream(out)
            counting.use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // Reported from inside the IO block: a cancellation arriving right now would otherwise drop the result
            // and leave a finished file nobody knows about.
            SavedOutput(uri, name, counting.count, mime, if (images) PICTURES_LABEL + subDir.trimEnd('/').let { if (it.isEmpty()) "" else "/$it" } else FOLDER_LABEL + subDir.trimEnd('/').let { if (it.isEmpty()) "" else "/$it" }).also(onSaved)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** "Save to phone" for a file another app sent: copies [source] into Downloads/Forge under its own name. */
    suspend fun saveFromUri(source: Uri, name: String, mime: String): SavedOutput = saveKeepingName(name, mime) { out ->
        val input = resolver.openInputStream(source) ?: throw IOException("Could not read the file")
        input.use { it.copyTo(out) }
    }

    /**
     * Renames the file at [uri] to [newName] (the extension is kept when the user leaves it off). Returns the name used, or
     * null when the provider refuses. Never throws.
     */
    suspend fun rename(uri: Uri, newName: String): String? = withContext(Dispatchers.IO) {
        val name = OutputNaming.renamed(newName) ?: return@withContext null
        try {
            val n = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
            if (n > 0) name else android.provider.DocumentsContract.renameDocument(resolver, uri, name)?.let { name }
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes the file itself (Files tab "Delete file", after the user confirmed). True when it is gone. */
    suspend fun deleteFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try { resolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
    }

    private fun exists(displayName: String, path: String, collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI): Boolean = try {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(displayName, path),
            null,
        )?.use { it.count > 0 } ?: false
    } catch (_: Exception) {
        false
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

    /** Removes a file this saver created (used to roll back a half-finished multi-file run). */
    fun delete(uri: Uri) { resolver.delete(uri, null, null) }

    /** Streams [write] into a user-chosen destination (the "Save as…" picker result). */
    suspend fun writeTo(destination: Uri, write: suspend (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(destination, "w") ?: throw IOException("Could not write the file")
        output.use { write(it) }
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
        const val PICTURES_PATH = "Pictures/Forge/"
        const val PICTURES_LABEL = "Pictures/Forge"
    }
}

/** Counts bytes written, so the saved size is exact without asking the provider. */
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) { out.write(b); count++ }

    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}
