package com.namanbansal.forge.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The few things [DocumentWriter] needs from Android, so every branch can be tested with a fake. */
interface DocumentIo {
    /** Tries to keep write access to [uri] across restarts; true when the grant is (now) held. */
    fun takeWritePermission(uri: Uri): Boolean
    /** True if [uri] can be opened for writing without changing it. */
    fun probeWrite(uri: Uri): Boolean
    fun openInput(uri: Uri): InputStream?
    /** Opens [uri] for writing, truncating it. */
    fun openOverwrite(uri: Uri): OutputStream?
}

/** Where backups and fallback copies go (production: [OutputSaver] in Downloads/Forge). */
interface DocumentSink {
    /** Stores [content] as `Downloads/Forge/Backups/<name>_backup_<timestamp>.pdf`. */
    suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream): SavedOutput
    /** Stores [content] as a new file `Downloads/Forge/<name>_edited.pdf`. */
    suspend fun saveCopy(originalName: String, content: () -> InputStream): SavedOutput
}

/** What [DocumentWriter.write] did. */
sealed interface WriteResult {
    /** The original was replaced; [backup] holds what it contained before. */
    data class Overwritten(val backup: SavedOutput) : WriteResult
    /**
     * The original is untouched (or was restored from [backup]) and the edit was saved as [copy]. [reason] says why in plain words.
     */
    data class SavedCopy(val copy: SavedOutput, val reason: String, val backup: SavedOutput? = null) : WriteResult
}

/**
 * Saves an edited PDF back where it came from, safely (used by the editing tools in Round 2).
 * Order: check write access -> back up the original -> overwrite -> verify. If anything fails the edit is saved as a new
 * copy instead and the original is left as it was (restored from the backup if the overwrite had started).
 */
class DocumentWriter(private val io: DocumentIo, private val sink: DocumentSink, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun write(uri: Uri, displayName: String, bytes: ByteArray): WriteResult = write(uri, displayName, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    suspend fun write(uri: Uri, displayName: String, file: File): WriteResult = write(uri, displayName, file.length()) { file.inputStream() }

    suspend fun write(uri: Uri, displayName: String, length: Long, content: () -> InputStream): WriteResult {
        val canWrite = try { io.takeWritePermission(uri) || io.probeWrite(uri) } catch (_: Exception) { false }
        if (!canWrite) return copy(displayName, content, "Android didn't give Forge permission to change the original, so your edit was saved as a new file.", null)

        val backup = try {
            val input = { io.openInput(uri) ?: throw IOException("cannot read original") }
            sink.saveBackup(displayName, timestamp(), input)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return copy(displayName, content, "Couldn't back up the original first, so it was left alone and your edit was saved as a new file.", null)
        }

        try {
            val out = io.openOverwrite(uri) ?: throw IOException("cannot open for writing")
            out.use { o -> content().use { it.copyTo(o) } }
            val written = io.openInput(uri)?.use { countBytes(it) } ?: -1L
            if (length >= 0 && written != length) throw IOException("size mismatch: wrote $written of $length")
            return WriteResult.Overwritten(backup)
        } catch (e: kotlinx.coroutines.CancellationException) {
            restore(uri, backup)
            throw e
        } catch (_: Exception) {
            val restored = restore(uri, backup)
            val why = if (restored) "Saving over the original failed; it was put back as it was and your edit was saved as a new file."
            else "Saving over the original failed and it may be damaged. A backup is in Downloads/Forge/Backups and your edit was saved as a new file."
            return copy(displayName, content, why, backup)
        }
    }

    private suspend fun copy(name: String, content: () -> InputStream, reason: String, backup: SavedOutput?): WriteResult =
        WriteResult.SavedCopy(sink.saveCopy(name, content), reason, backup)

    private fun restore(uri: Uri, backup: SavedOutput): Boolean = try {
        val src = io.openInput(backup.uri) ?: return false
        val out = io.openOverwrite(uri) ?: return false
        out.use { o -> src.use { it.copyTo(o) } }
        true
    } catch (_: Exception) { false }

    private fun countBytes(i: InputStream): Long { val buf = ByteArray(32 * 1024); var n = 0L; while (true) { val r = i.read(buf); if (r < 0) return n; n += r } }

    private fun timestamp(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(clock()))

    companion object {
        /** The real thing: content resolver for the original, [OutputSaver] for backups and copies. */
        fun create(context: Context, saver: OutputSaver): DocumentWriter = DocumentWriter(AndroidDocumentIo(context.contentResolver), SaverSink(saver, context.contentResolver))
    }
}

private class AndroidDocumentIo(private val cr: ContentResolver) : DocumentIo {
    override fun takeWritePermission(uri: Uri): Boolean = try {
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        cr.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    } catch (_: Exception) { false }

    /** Opens read-write without truncating, so a failed probe never costs the user their file. */
    override fun probeWrite(uri: Uri): Boolean = try { cr.openFileDescriptor(uri, "rw")?.use { true } ?: false } catch (_: Exception) { false }

    override fun openInput(uri: Uri): InputStream? = cr.openInputStream(uri)
    override fun openOverwrite(uri: Uri): OutputStream? = cr.openOutputStream(uri, "wt")
}

private class SaverSink(private val saver: OutputSaver, private val cr: ContentResolver) : DocumentSink {
    override suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream): SavedOutput =
        saver.save(originalName, "backup_$timestamp", "pdf", "application/pdf", subDir = "Backups/") { out -> content().use { it.copyTo(out) } }

    override suspend fun saveCopy(originalName: String, content: () -> InputStream): SavedOutput =
        saver.save(originalName, "edited", "pdf", "application/pdf") { out -> content().use { it.copyTo(out) } }
}
