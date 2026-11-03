package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** One output page: a page of one of the assembler's sources (optionally turned), or a new blank page. */
sealed interface PageSpec {
    /** Page [page] (0-based) of source number [source]; [addRotation] degrees clockwise are added to its own rotation. */
    data class FromSource(val source: Int, val page: Int, val addRotation: Int = 0) : PageSpec
    /** A blank page of the given size in PDF points. */
    data class Blank(val width: Float, val height: Float) : PageSpec
}

/**
 * Builds new PDFs out of pages of existing ones, in any order, with duplicates, turns and blank pages. This single
 * operation backs Extract, Split, Page manager and Insert, so they all behave the same way (lossless: page content is
 * copied, not re-rendered). The sources stay open until [close] so one source can feed many outputs (Split).
 * Internal links that point at other pages are dropped (they would drag the whole source into the output).
 */
class PdfAssembler(sources: List<File>, private val names: List<String> = sources.map { it.name }) : Closeable {
    private val docs = ArrayList<PDDocument>()

    init {
        try {
            sources.forEachIndexed { i, f ->
                docs += try {
                    PDDocument.load(f, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES))
                } catch (e: IOException) {
                    throw ToolException("\"${names.getOrElse(i) { f.name }}\" couldn't be read. It may be damaged or password protected.", e)
                }
            }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    fun pageCount(source: Int): Int = docs[source].numberOfPages

    /** Size (width, height in points, rotation applied) of a source page; used to size blank pages like their neighbours. */
    fun pageSize(source: Int, page: Int): Pair<Float, Float> {
        val p = docs[source].getPage(page)
        val box = p.cropBox
        return if (p.rotation % 180 != 0) box.height to box.width else box.width to box.height
    }

    /** Writes [specs] as a new PDF to [out]. [protection] (if any) is applied to it. */
    fun write(
        specs: List<PageSpec>,
        out: OutputStream,
        protection: PdfProtection? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ) {
        if (specs.isEmpty()) throw ToolException("There are no pages to save.")
        try {
            PDDocument(MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { dest ->
                specs.forEachIndexed { i, spec ->
                    checkCancelled()
                    when (spec) {
                        is PageSpec.FromSource -> {
                            val src = docs.getOrNull(spec.source) ?: throw ToolException("A page refers to a file that isn't open.")
                            if (spec.page !in 0 until src.numberOfPages) throw ToolException("Page ${spec.page + 1} doesn't exist in this PDF.")
                            val imported = dest.importPage(src.getPage(spec.page))
                            dropInternalLinks(imported)
                            if (spec.addRotation % 360 != 0) imported.rotation = PdfRotator.normalize(imported.rotation + spec.addRotation)
                        }
                        is PageSpec.Blank -> dest.addPage(PDPage(PDRectangle(spec.width, spec.height)))
                    }
                    if (i % 8 == 0 || i == specs.lastIndex) onProgress((i + 1).toFloat() / specs.size * 0.9f)
                }
                checkCancelled()
                protection?.applyTo(dest)
                dest.save(out)
                onProgress(1f)
            }
        } catch (e: ToolException) {
            throw e
        } catch (e: IOException) {
            throw ToolException("Could not build the new PDF: ${e.message ?: "the file could not be processed"}.", e)
        }
    }

    private fun dropInternalLinks(page: PDPage) {
        val annots = try { page.annotations } catch (_: Exception) { return }
        if (annots.isEmpty()) return
        val keep = annots.filterNot { a ->
            a is PDAnnotationLink && (try { a.destination != null || a.action is PDActionGoTo } catch (_: Exception) { true })
        }
        if (keep.size != annots.size) page.annotations = keep
    }

    override fun close() {
        docs.forEach { runCatching { it.close() } }
        docs.clear()
    }

    companion object {
        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f
        const val LETTER_WIDTH = 612f
        const val LETTER_HEIGHT = 792f

        /** Convenience for one-shot use. */
        fun build(
            sources: List<File>, specs: List<PageSpec>, out: OutputStream, protection: PdfProtection? = null,
            checkCancelled: () -> Unit = {}, onProgress: (Float) -> Unit = {}, names: List<String> = sources.map { it.name },
        ) = PdfAssembler(sources, names).use { it.write(specs, out, protection, checkCancelled, onProgress) }
    }
}
