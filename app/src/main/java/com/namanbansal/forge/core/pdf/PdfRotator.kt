package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Lossless page rotation: only each page's /Rotate entry changes, nothing is re-rendered or recompressed.
 * Plain Kotlin (no Compose, no Android UI) so it is unit-tested directly.
 */
object PdfRotator {

    /** Normalise any multiple of 90 (negative allowed) to 0, 90, 180 or 270. */
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * Rotates pages of [source] and writes the result to [out].
     * @param rotations page index (0-based) -> clockwise degrees to ADD to the page's current rotation. Zero entries are skipped.
     * @param checkCancelled called between pages; throw (CancellationException) to abort.
     * @param onProgress 0..1
     * @param protection re-applied to the saved copy when the input was password protected
     * @return number of pages whose rotation changed
     */
    fun rotate(
        source: File,
        out: OutputStream,
        rotations: Map<Int, Int>,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        protection: PdfProtection? = null,
    ): Int {
        val bad = rotations.entries.firstOrNull { it.value % 90 != 0 }
        if (bad != null) throw ToolException("Pages can only be rotated in steps of 90 degrees.")
        var changed = 0
        try {
            PDDocument.load(source, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
                val total = doc.numberOfPages
                if (rotations.keys.any { it !in 0 until total }) throw ToolException("A selected page doesn't exist in this PDF.")
                var index = 0
                for (page in doc.pages) {
                    checkCancelled()
                    val add = normalize(rotations[index] ?: 0)
                    if (add != 0) {
                        page.rotation = normalize(page.rotation + add)
                        changed++
                    }
                    index++
                    if (index % 8 == 0 || index == total) onProgress(index.toFloat() / total * 0.9f)
                }
                checkCancelled()
                protection?.applyTo(doc)
                doc.save(out)
                onProgress(1f)
            }
        } catch (e: ToolException) {
            throw e
        } catch (e: IOException) {
            throw ToolException("Could not rotate this PDF: ${e.message ?: "the file could not be processed"}.", e)
        }
        return changed
    }
}
