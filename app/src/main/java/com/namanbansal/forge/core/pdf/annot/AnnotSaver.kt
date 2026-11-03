package com.namanbansal.forge.core.pdf.annot

import android.net.Uri
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.WriteResult
import com.namanbansal.forge.core.pdf.PdfProtection
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What a save did, in words for the snackbar, plus the plain (unencrypted) file to reload the viewer from. */
class SaveOutcome(val result: WriteResult, val reloadFile: File) {
    val message: String get() = when (result) {
        is WriteResult.Overwritten -> "Saved · backup in Downloads/Forge/Backups"
        is WriteResult.SavedCopy -> "Saved as a copy in Downloads/Forge"
    }
    val overwritten: Boolean get() = result is WriteResult.Overwritten
}

/**
 * Writes the annotate session into the PDF and stores it through [DocumentWriter] (back up the original, overwrite when
 * writable, otherwise a copy). The password of an encrypted original is kept ([PdfProtection]).
 */
class AnnotSaver(private val writer: DocumentWriter, private val workDir: File) {
    suspend fun save(uri: Uri, displayName: String, source: File, protection: PdfProtection?, baseline: List<Annot>, current: List<Annot>): SaveOutcome {
        workDir.mkdirs()
        val plain = File(workDir, "annotated-${UUID.randomUUID()}.pdf")
        val toStore = if (protection == null) plain else File(workDir, "annotated-${UUID.randomUUID()}-enc.pdf")
        try {
            withContext(Dispatchers.IO) {
                PdfAnnotIo.write(source, plain, baseline, current, null)
                if (protection != null) PDDocument.load(plain).use { d -> protection.applyTo(d); toStore.outputStream().use { d.save(it) } }
            }
            val result = writer.write(uri, displayName, toStore)
            return SaveOutcome(result, plain)
        } catch (t: Throwable) {
            plain.delete()
            throw t
        } finally {
            if (toStore != plain) toStore.delete()
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
