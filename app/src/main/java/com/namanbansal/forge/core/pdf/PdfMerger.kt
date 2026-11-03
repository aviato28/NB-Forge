package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.IOException
import java.io.OutputStream

class MergeInput(val file: File, val name: String)

/**
 * Concatenates PDFs with PDFBox's [PDFMergerUtility]. Memory-safe: the destination and every source use scratch files
 * (temp-file-only mode) instead of the heap, so many large inputs stay well clear of OOM. Sources are appended one at a
 * time so progress and cancel work between files.
 */
object PdfMerger {

    /** @return total number of pages written. */
    fun merge(
        inputs: List<MergeInput>,
        out: OutputStream,
        blankBetween: Boolean = false,
        protection: PdfProtection? = null,
        tempDir: File? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Int {
        if (inputs.size < 2) throw ToolException("Choose at least two PDFs to merge.")
        fun memory() = MemoryUsageSetting.setupTempFileOnly().also { if (tempDir != null) { tempDir.mkdirs(); it.setTempDir(tempDir) } }
        val util = PDFMergerUtility()
        val opened = ArrayList<PDDocument>()
        val dest = PDDocument(memory())
        try {
            var previous: PDDocument? = null
            inputs.forEachIndexed { i, input ->
                checkCancelled()
                val doc = try {
                    PDDocument.load(input.file, memory())
                } catch (e: IOException) {
                    throw ToolException("\"${input.name}\" couldn't be read. It may be damaged. Remove it and try again.", e)
                }
                opened += doc // must stay open until the destination is saved: pages share their content with it
                if (doc.numberOfPages == 0) throw ToolException("\"${input.name}\" has no pages.")
                if (blankBetween && previous != null) {
                    val last = previous!!.getPage(previous!!.numberOfPages - 1)
                    val box = last.cropBox
                    val swap = last.rotation % 180 != 0
                    dest.addPage(PDPage(if (swap) PDRectangle(box.height, box.width) else PDRectangle(box.width, box.height)))
                }
                try {
                    util.appendDocument(dest, doc)
                } catch (e: IOException) {
                    throw ToolException("\"${input.name}\" couldn't be merged: ${e.message ?: "unsupported content"}.", e)
                }
                previous = doc
                onProgress((i + 1).toFloat() / inputs.size * 0.9f)
            }
            checkCancelled()
            protection?.applyTo(dest)
            val total = dest.numberOfPages
            dest.save(out)
            onProgress(1f)
            return total
        } finally {
            opened.forEach { runCatching { it.close() } }
            runCatching { dest.close() }
        }
    }
}
