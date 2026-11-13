package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.pdf.viewer.OutlineItem
import com.namanbansal.forge.core.pdf.viewer.PdfOutline
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** One bookmark in the editor's flat list: [level] 0 = top. [page] is 0-based. */
data class BookmarkEntry(val title: String, val page: Int, val level: Int)

/** The outline as a flat list with levels, and pure edit operations on it (so the editor and its tests share one model). */
object PdfBookmarks {
    const val MAX_LEVEL = 8

    fun flatten(items: List<OutlineItem>, level: Int = 0, out: MutableList<BookmarkEntry> = ArrayList()): List<BookmarkEntry> {
        for (i in items) { out += BookmarkEntry(i.title, i.page ?: 0, level); flatten(i.children, level + 1, out) }
        return out
    }

    fun read(doc: PDDocument): List<BookmarkEntry> = flatten(PdfOutline.parse(doc))

    /** Levels may only step down by one at a time (a child needs a parent); clamps titles and pages too. */
    fun normalize(list: List<BookmarkEntry>, pageCount: Int): List<BookmarkEntry> {
        var prev = -1
        return list.map { b ->
            val level = b.level.coerceIn(0, minOf(prev + 1, MAX_LEVEL)).also { prev = it }
            BookmarkEntry(b.title.ifBlank { "Untitled" }, b.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), level)
        }
    }

    /** Replaces the document's outline with [list] (empty list removes it). */
    fun write(doc: PDDocument, list: List<BookmarkEntry>) {
        val items = normalize(list, doc.numberOfPages)
        if (items.isEmpty()) { doc.documentCatalog.documentOutline = null; return }
        val root = PDDocumentOutline()
        val stack = ArrayList<PDOutlineNode>().apply { add(root) }
        for (b in items) {
            while (stack.size > b.level + 1) stack.removeAt(stack.size - 1)
            val node = PDOutlineItem()
            node.title = b.title
            val dest = PDPageFitDestination(); dest.page = doc.getPage(b.page)
            node.destination = dest
            stack.last().addLast(node)
            stack += node
        }
        for (n in root.children()) openAll(n)
        root.openNode()
        doc.documentCatalog.documentOutline = root
    }

    private fun openAll(item: PDOutlineItem) { if (item.hasChildren()) item.openNode(); for (c in item.children()) openAll(c) }

    // ---- edit operations ----

    /** Index just past [index]'s last descendant. */
    fun subtreeEnd(list: List<BookmarkEntry>, index: Int): Int {
        var i = index + 1
        while (i < list.size && list[i].level > list[index].level) i++
        return i
    }

    fun add(list: List<BookmarkEntry>, title: String, page: Int, after: Int? = null): List<BookmarkEntry> {
        if (after == null || after !in list.indices) return list + BookmarkEntry(title, page, 0)
        val level = list[after].level
        val at = subtreeEnd(list, after)
        return list.toMutableList().apply { add(at, BookmarkEntry(title, page, level)) }
    }

    fun update(list: List<BookmarkEntry>, index: Int, title: String, page: Int): List<BookmarkEntry> =
        list.toMutableList().also { if (index in it.indices) it[index] = it[index].copy(title = title, page = page) }

    fun delete(list: List<BookmarkEntry>, index: Int): List<BookmarkEntry> =
        if (index !in list.indices) list else list.toMutableList().also { for (k in subtreeEnd(list, index) - 1 downTo index) it.removeAt(k) }

    /** Makes the item (and its children) one level deeper; only possible under a previous item. */
    fun indent(list: List<BookmarkEntry>, index: Int): List<BookmarkEntry> {
        if (index !in list.indices || index == 0 || list[index].level > list[index - 1].level || list[index].level >= MAX_LEVEL) return list
        return shift(list, index, 1)
    }

    fun outdent(list: List<BookmarkEntry>, index: Int): List<BookmarkEntry> =
        if (index !in list.indices || list[index].level == 0) list else shift(list, index, -1)

    private fun shift(list: List<BookmarkEntry>, index: Int, d: Int): List<BookmarkEntry> {
        val end = subtreeEnd(list, index)
        return list.mapIndexed { i, b -> if (i in index until end) b.copy(level = b.level + d) else b }
    }

    fun canIndent(list: List<BookmarkEntry>, index: Int) = index in 1 until list.size && list[index].level <= list[index - 1].level && list[index].level < MAX_LEVEL

    /** Moves one item (drag and drop). Levels are re-normalised so the result is always a valid tree. */
    fun move(list: List<BookmarkEntry>, from: Int, to: Int, pageCount: Int): List<BookmarkEntry> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList(); m.add(to, m.removeAt(from))
        return normalize(m, pageCount)
    }

    /** Moves an item with its children up or down among its siblings. */
    fun moveSubtree(list: List<BookmarkEntry>, index: Int, up: Boolean): List<BookmarkEntry> {
        if (index !in list.indices) return list
        val end = subtreeEnd(list, index)
        val block = list.subList(index, end).toList()
        if (up) {
            var p = index - 1
            while (p >= 0 && list[p].level > list[index].level) p--
            if (p < 0 || list[p].level < list[index].level) return list
            return list.take(p) + block + list.subList(p, index) + list.drop(end)
        }
        if (end >= list.size || list[end].level != list[index].level) return list
        val nextEnd = subtreeEnd(list, end)
        return list.take(index) + list.subList(end, nextEnd) + block + list.drop(nextEnd)
    }

    // ---- state encoding (survives process death) ----
    fun encode(list: List<BookmarkEntry>): String = list.joinToString("\n") { "${it.level}\t${it.page}\t" + it.title.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n") }

    fun decode(text: String): List<BookmarkEntry> = if (text.isEmpty()) emptyList() else text.split("\n").mapNotNull { line ->
        val p = line.split("\t", limit = 3)
        if (p.size < 3) null else BookmarkEntry(unescape(p[2]), p[1].toIntOrNull() ?: 0, p[0].toIntOrNull() ?: 0)
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] }); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

/** Finds headings by font size: lines clearly bigger than the body text, ranked into up to three levels. */
object HeadingDetector {
    private class Line(val page: Int, val y: Float, val size: Float, val text: String)

    fun detect(doc: PDDocument, ctx: TransformCtx = TransformCtx({}, {}), maxHeadings: Int = 300): List<BookmarkEntry> {
        val lines = ArrayList<Line>()
        val bodyChars = HashMap<Int, Int>() // size*10 -> characters
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                if (textPositions.isEmpty() || text.isBlank()) return
                val size = textPositions.maxOf { it.fontSizeInPt }
                val key = (size * 10).toInt()
                bodyChars[key] = (bodyChars[key] ?: 0) + text.length
                lines += Line(currentPageNo - 1, textPositions.first().yDirAdj, size, text.trim())
            }
        }
        stripper.sortByPosition = true
        val total = doc.numberOfPages
        for (p in 1..total) {
            ctx.checkCancelled()
            stripper.startPage = p; stripper.endPage = p
            stripper.getText(doc)
            ctx.onProgress(p.toFloat() / total * 0.9f)
        }
        if (lines.isEmpty()) return emptyList()
        val body = (bodyChars.maxByOrNull { it.value }?.key ?: 110) / 10f
        // Merge wrapped heading lines (same page, same size, close together).
        val merged = ArrayList<Line>()
        for (l in lines.filter { it.size >= body * 1.15f }) {
            val last = merged.lastOrNull()
            if (last != null && last.page == l.page && Math.abs(last.size - l.size) < 0.6f && l.y - last.y in 0f..(l.size * 1.6f)) merged[merged.size - 1] = Line(last.page, l.y, last.size, last.text + " " + l.text)
            else merged += l
        }
        val candidates = merged.filter { it.text.length in 2..120 && it.text.any { c -> c.isLetter() } }
        val sizes = candidates.map { Math.round(it.size * 2) / 2f }.distinct().sortedDescending().take(3)
        return candidates.filter { Math.round(it.size * 2) / 2f in sizes }.take(maxHeadings)
            .map { BookmarkEntry(it.text, it.page, sizes.indexOf(Math.round(it.size * 2) / 2f)) }
            .let { normalize(it, total) }
    }

    private fun normalize(list: List<BookmarkEntry>, pages: Int) = PdfBookmarks.normalize(list, pages)
}
