package com.namanbansal.forge.core.pdf

import android.content.Context
import android.graphics.Bitmap
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.friendlyMessage
import java.io.File

/** Outcome of opening one picked PDF for a tool that handles several (Merge, Insert's second file). */
sealed interface IntakeResult {
    /** Open and ready. [cover] is a small first-page image (null if it could not be drawn); [files] must be deleted when done. */
    class Ready(val source: PdfSource, val cover: Bitmap?, val files: List<File>) : IntakeResult
    /** Needs a password ([wrong] = the last try failed); keep [copy] and call [PdfIntake.unlock]. */
    class Locked(val copy: File, val wrong: Boolean) : IntakeResult
    /** Cannot be used; [reason] is friendly and does not include the file name. */
    class Failed(val reason: String) : IntakeResult
}

/** Copy -> open (-> unlock) -> cover thumbnail, with the same password and corruption handling as [PdfToolViewModel]. */
object PdfIntake {
    private const val COVER_PX = 220

    suspend fun open(context: Context, file: PickedFile): IntakeResult {
        if (file.kind != FileKind.PDF) return IntakeResult.Failed("This isn't a PDF.")
        val copy = try {
            PdfLoader.copyToWork(context, file.uri)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return IntakeResult.Failed(t.friendlyMessage())
        }
        return finish(copy, PdfLoader.open(copy, workDir(context), null))
    }

    suspend fun unlock(context: Context, copy: File, password: String): IntakeResult =
        finish(copy, PdfLoader.open(copy, workDir(context), password))

    private fun workDir(context: Context) = File(context.cacheDir, PdfLoader.WORK_DIR)

    private suspend fun finish(copy: File, result: PdfOpenResult): IntakeResult = when (result) {
        is PdfOpenResult.Ready -> IntakeResult.Ready(result.source, cover(result.source.file), listOf(copy, result.source.file).distinct())
        PdfOpenResult.NeedsPassword -> IntakeResult.Locked(copy, wrong = false)
        PdfOpenResult.WrongPassword -> IntakeResult.Locked(copy, wrong = true)
        is PdfOpenResult.Unreadable -> { copy.delete(); IntakeResult.Failed(result.reason) }
    }

    /** First page as a small bitmap; null when it cannot be rendered (the row then shows a placeholder). */
    suspend fun cover(file: File): Bitmap? {
        val thumbs = try { PdfThumbnails(file, maxSidePx = COVER_PX, cacheBytes = 2 shl 20) } catch (_: Exception) { return null }
        return try { thumbs.get(0) } finally { thumbs.close() }
    }
}
