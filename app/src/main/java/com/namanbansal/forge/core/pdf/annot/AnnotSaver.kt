package com.namanbansal.forge.core.pdf.annot

import android.net.Uri
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.WriteResult
import com.namanbansal.forge.core.pdf.PdfProtection
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** What a save did, in words for the snackbar, plus the plain (unencrypted) file to reload the viewer from. */
class SaveOutcome(val result: WriteResult, val reloadFile: File, private val note: String? = null) {
    val message: String get() = note ?: when (result) {
        is WriteResult.Overwritten -> "Saved · backup in Downloads/Forge/Backups"
        is WriteResult.SavedCopy -> "Saved as a copy in Downloads/Forge"
    }
    val overwritten: Boolean get() = result is WriteResult.Overwritten
    /** Where the saved copy lives when the original was left alone (null after an overwrite). */
    val copy: SavedOutput? get() = (result as? WriteResult.SavedCopy)?.copy
}

/** An edited PDF built in the work folder: [reload] is plain (what the viewer shows), [toStore] is what gets written (encrypted again if the original was). */
class RenderedPdf(val reload: File, val toStore: File) {
    fun discard() { reload.delete(); if (toStore != reload) toStore.delete() }
}

/** Where an edited PDF goes. */
sealed interface SaveTarget {
    /** Back up the original, overwrite it, or save a copy if that isn't possible (the long-standing behaviour). */
    data object Original : SaveTarget
    /** A new file in Downloads/Forge; the original is left alone. */
    data class Downloads(val reason: String) : SaveTarget
    /** A file the user chose in the system picker ("Save as…"). */
    data class Chosen(val uri: Uri, val displayName: String) : SaveTarget
}

/**
 * Writes the annotate session into the PDF and stores it through [DocumentWriter] (back up the original, overwrite when
 * writable, otherwise a copy). The password of an encrypted original is kept ([PdfProtection]).
 */
class AnnotSaver(private val writer: DocumentWriter, private val workDir: File, private val saver: OutputSaver? = null) {

    /** Builds the edited file without storing it anywhere (used by Share / Print / Open with "with your changes"). */
    suspend fun render(source: File, protection: PdfProtection?, baseline: List<Annot>, current: List<Annot>, extra: ((File) -> Unit)? = null): RenderedPdf {
        workDir.mkdirs()
        val plain = File(workDir, "annotated-${UUID.randomUUID()}.pdf")
        val toStore = if (protection == null) plain else File(workDir, "annotated-${UUID.randomUUID()}-enc.pdf")
        try {
            withContext(Dispatchers.IO) {
                PdfAnnotIo.write(source, plain, baseline, current, null)
                extra?.invoke(plain) // Fill & Sign edits are written into the same working file before it is encrypted again
                if (protection != null) PDDocument.load(plain).use { d -> protection.applyTo(d); toStore.outputStream().use { d.save(it) } }
            }
            return RenderedPdf(plain, toStore)
        } catch (t: Throwable) {
            plain.delete(); toStore.delete()
            throw t
        }
    }

    suspend fun save(uri: Uri, displayName: String, source: File, protection: PdfProtection?, baseline: List<Annot>, current: List<Annot>,
                     extra: ((File) -> Unit)? = null, copyOnly: Boolean = false, copyReason: String = "", target: SaveTarget = SaveTarget.Original): SaveOutcome {
        val rendered = render(source, protection, baseline, current, extra)
        try {
            val toStore = rendered.toStore
            return when {
                target is SaveTarget.Chosen -> {
                    val s = saver ?: throw IOException("Saving to a chosen place isn't available.")
                    s.writeTo(target.uri) { out -> toStore.inputStream().use { it.copyTo(out) } }
                    SaveOutcome(WriteResult.SavedCopy(SavedOutput(target.uri, target.displayName, toStore.length(), "application/pdf"), "Saved where you chose."), rendered.reload, "Saved to ${target.displayName}")
                }
                target is SaveTarget.Downloads -> SaveOutcome(writer.writeCopy(displayName, toStore, target.reason), rendered.reload)
                copyOnly -> SaveOutcome(writer.writeCopy(displayName, toStore, copyReason), rendered.reload)
                else -> SaveOutcome(writer.write(uri, displayName, toStore), rendered.reload)
            }
        } catch (t: Throwable) {
            rendered.reload.delete()
            throw t
        } finally {
            if (rendered.toStore != rendered.reload) rendered.toStore.delete()
        }
    }
}

/** Keeps unsaved annotations on disk (one file per document) so they survive the app being killed. */
class PendingStore(private val dir: File) {
    private fun file(key: String) = File(dir, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".json")

    fun save(key: String, annots: List<Annot>) {
        try { dir.mkdirs(); val f = file(key); val tmp = File(dir, f.name + ".tmp"); tmp.writeText(AnnotJson.encode(annots)); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() } } catch (_: Exception) { /* best effort */ }
    }

    fun load(key: String): List<Annot>? = try { file(key).takeIf { it.exists() }?.readText()?.let { AnnotJson.decode(it) } } catch (_: Exception) { null }

    fun clear(key: String) { runCatching { file(key).delete() } }
}
