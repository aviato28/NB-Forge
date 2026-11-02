package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** One output of a split: [label] goes into the file name (`report_<label>.pdf`), [pages] are 0-based source pages. */
data class SplitPart(val label: String, val pages: List<Int>) {
    val summary: String get() = PageRanges.format(pages)
}

/** A top-level bookmark and the 0-based page it points at. */
data class Bookmark(val title: String, val page: Int)

/** Planning (pure) and running of the four Split modes. */
object PdfSplitter {

    /** Hard cap so a typo (every 1 page of a 3000-page file) cannot create thousands of files. */
    const val MAX_PARTS = 500

    fun byRanges(ranges: List<IntRange>): List<SplitPart> = ranges.map { SplitPart(PageRanges.label(it), it.toList()) }

    fun everyN(n: Int, total: Int): List<SplitPart> {
        if (n < 1) throw ToolException("Pages per file must be at least 1.")
        return (0 until total step n).map { start ->
            val range = start..minOf(start + n - 1, total - 1)
            SplitPart(PageRanges.label(range), range.toList())
        }
    }

    fun singlePages(total: Int): List<SplitPart> = (0 until total).map { SplitPart("p${it + 1}", listOf(it)) }

    /**
     * Parts that start at each top-level bookmark and run to the next one. Pages before the first bookmark become a
     * leading "start" part. Bookmarks on the same page collapse into the first one.
     */
    fun byBookmarks(bookmarks: List<Bookmark>, total: Int): List<SplitPart> {
        val marks = bookmarks.filter { it.page in 0 until total }.sortedBy { it.page }.distinctBy { it.page }
        if (marks.isEmpty()) throw ToolException("This PDF has no bookmarks to split by.")
        val parts = ArrayList<SplitPart>()
        var index = 1
        if (marks.first().page > 0) parts += SplitPart("0_start", (0 until marks.first().page).toList())
        marks.forEachIndexed { i, m ->
            val end = if (i + 1 < marks.size) marks[i + 1].page - 1 else total - 1
            parts += SplitPart("${index++}_${safe(m.title)}", (m.page..end).toList())
        }
        return parts
    }

    private fun safe(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ").replace(Regex("\\s+"), " ").trim().take(40).ifBlank { "section" }

    /** Top-level outline entries that resolve to a page of [file]; empty when the PDF has no usable bookmarks. */
    fun readBookmarks(file: File): List<Bookmark> = try {
        PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
            val outline = doc.documentCatalog.documentOutline ?: return@use emptyList()
            val out = ArrayList<Bookmark>()
            var item: PDOutlineItem? = outline.firstChild
            var guard = 0
            while (item != null && guard++ < 10_000) {
                val page = try { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } } catch (_: Exception) { null }
                if (page != null && page >= 0) out += Bookmark(item.title?.trim().orEmpty().ifBlank { "Section ${out.size + 1}" }, page)
                item = item.nextSibling
            }
            out
        }
    } catch (_: IOException) {
        emptyList()
    }

    /**
     * Writes every part of [source] as its own PDF. [sink] is handed each part and a writer: it must call the writer with
     * the stream the part should go to (this keeps MediaStore saving, which is suspending, out of this class).
     */
    suspend fun run(
        source: File,
        parts: List<SplitPart>,
        protection: PdfProtection?,
        checkCancelled: suspend () -> Unit,
        onProgress: (Float) -> Unit,
        sink: suspend (part: SplitPart, write: (OutputStream) -> Unit) -> Unit,
    ) {
        if (parts.isEmpty()) throw ToolException("There is nothing to split.")
        if (parts.size > MAX_PARTS) throw ToolException("That would create ${parts.size} files. Choose settings that make at most $MAX_PARTS.")
        PdfAssembler(listOf(source)).use { asm ->
            parts.forEachIndexed { i, part ->
                checkCancelled()
                sink(part) { out ->
                    asm.write(part.pages.map { PageSpec.FromSource(0, it) }, out, protection)
                }
                onProgress((i + 1).toFloat() / parts.size)
            }
        }
    }
}
