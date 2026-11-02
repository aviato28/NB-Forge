package com.namanbansal.forge.core.pdf

import android.content.Context
import android.net.Uri
import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** A PDF ready for processing and rendering: a local, unencrypted file. */
class PdfSource(val file: File, val pageCount: Int, val wasEncrypted: Boolean)

sealed interface PdfOpenResult {
    class Ready(val source: PdfSource) : PdfOpenResult
    /** Password required and none supplied. */
    data object NeedsPassword : PdfOpenResult
    /** A password was supplied but is wrong. */
    data object WrongPassword : PdfOpenResult
    /** Not a readable PDF. */
    class Unreadable(val reason: String) : PdfOpenResult
}

/**
 * Opens PDFs for Forge tools. Content-uri inputs are streamed into a private working file (works for providers
 * that cannot seek, and PdfRenderer/PDFBox both get a plain file). Password-protected PDFs are decrypted into a
 * second working file so rendering and processing never deal with encryption again.
 */
object PdfLoader {
    const val WORK_DIR = "pdf-work"

    private fun workDir(cacheDir: File) = File(cacheDir, WORK_DIR).apply { mkdirs() }

    /** Streams [uri] into a private file. Returns the copy. */
    suspend fun copyToWork(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw ToolException("Could not open this file.")
        copyStream(input, workDir(context.cacheDir))
    }

    fun copyStream(input: InputStream, dir: File): File {
        dir.mkdirs()
        val out = File(dir, "in-${UUID.randomUUID()}.pdf")
        try {
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out
    }

    /**
     * Tries to open [file] with [password] (null = none). Never throws for expected problems; returns a
     * [PdfOpenResult] instead. [dir] is where a decrypted working copy goes.
     */
    suspend fun open(file: File, dir: File, password: String?): PdfOpenResult = withContext(Dispatchers.IO) {
        try {
            PDDocument.load(file, password ?: "", MemoryUsageSetting.setupMixed(MIXED_MEMORY_BYTES)).use { doc ->
                val pages = doc.numberOfPages
                if (pages <= 0) return@withContext PdfOpenResult.Unreadable("This PDF has no pages.")
                if (!doc.isEncrypted) return@withContext PdfOpenResult.Ready(PdfSource(file, pages, false))
                doc.isAllSecurityToBeRemoved = true
                dir.mkdirs()
                val plain = File(dir, "dec-${UUID.randomUUID()}.pdf")
                try {
                    plain.outputStream().use { doc.save(it) }
                } catch (t: Throwable) {
                    plain.delete()
                    throw t
                }
                PdfOpenResult.Ready(PdfSource(plain, pages, true))
            }
        } catch (_: InvalidPasswordException) {
            if (password.isNullOrEmpty()) PdfOpenResult.NeedsPassword else PdfOpenResult.WrongPassword
        } catch (e: IOException) {
            PdfOpenResult.Unreadable("This doesn't look like a valid PDF, or it is damaged.")
        } catch (e: RuntimeException) {
            PdfOpenResult.Unreadable("This doesn't look like a valid PDF, or it is damaged.")
        }
    }

    /** Spill to temp files beyond this much heap per document (keeps 500-page files far from OOM). */
    const val MIXED_MEMORY_BYTES = 16L * 1024 * 1024
}
