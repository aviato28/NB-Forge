package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.util.regex.Pattern

/** A highlight box as fractions (0..1) of the displayed page, origin top-left. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One occurrence of the search text: where (page, 0-based), a snippet for the hit list and the boxes to highlight. */
data class TextHit(val page: Int, val before: String, val match: String, val after: String, val rects: List<NormRect>)

/**
 * Text search for the viewer, built on PDFBox's [PDFTextStripper]: it collects the characters of each page with their
 * positions, finds the query (case-insensitive, whitespace-insensitive, across line breaks) and turns every hit into
 * highlight boxes (one per line the hit touches). Keeps its document open until [close].
 */
class PdfTextSearch(file: File) : Closeable {
    private val doc: PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES))
    val pageCount: Int = doc.numberOfPages

    /**
     * Searches pages in order, calling [onPage] with the hits of each page that has any. Stops after [maxHits].
     * [checkCancelled] is called between pages (throw to abort). @return total hits found.
     */
    fun search(
        query: String,
        maxHits: Int = 500,
        checkCancelled: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onPage: (List<TextHit>) -> Unit,
    ): Int {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return 0
        val pattern = Pattern.compile(Pattern.quote(q).replace(" ", "\\E\\s+\\Q"), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        var total = 0
        for (page in 0 until pageCount) {
            checkCancelled()
            val hits = searchPage(page, pattern)
            if (hits.isNotEmpty()) {
                val room = maxHits - total
                onPage(if (hits.size > room) hits.take(room) else hits)
                total += minOf(hits.size, room)
            }
            onProgress((page + 1).toFloat() / pageCount)
            if (total >= maxHits) break
        }
        return total
    }

    /** Hits on one 0-based page (also used directly by tests). */
    fun searchPage(page: Int, pattern: Pattern): List<TextHit> {
        val collector = Collector()
        try {
            collector.setStartPage(page + 1)
            collector.setEndPage(page + 1)
            collector.getText(doc)
        } catch (_: Exception) {
            return emptyList() // an unreadable page (odd fonts) must not abort the whole search
        }
        val text = collector.text()
        val pos = collector.positions
        val (pw, ph) = collector.pageSize
        if (pw <= 0f || ph <= 0f) return emptyList()
        val matcher = pattern.matcher(text)
        val hits = ArrayList<TextHit>()
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) continue
            val rects = rectsFor(matcher.start(), matcher.end(), pos, pw, ph)
            val before = text.substring(maxOf(0, matcher.start() - 28), matcher.start()).replace(Regex("\\s+"), " ").trimStart()
            val after = text.substring(matcher.end(), minOf(text.length, matcher.end() + 36)).replace(Regex("\\s+"), " ").trimEnd()
            hits += TextHit(page, before, matcher.group().replace(Regex("\\s+"), " "), after, rects)
        }
        return hits
    }

    private fun rectsFor(start: Int, end: Int, pos: List<TextPosition?>, pw: Float, ph: Float): List<NormRect> {
        val rects = ArrayList<NormRect>()
        var cur: FloatArray? = null // l, t, r, b in points
        var curBase = 0f
        for (i in start until end) {
            val tp = pos.getOrNull(i) ?: continue
            val h = tp.heightDir.coerceAtLeast(1f)
            val l = tp.xDirAdj
            val r = tp.xDirAdj + tp.widthDirAdj
            val t = tp.yDirAdj - h
            val b = tp.yDirAdj + h * 0.25f
            val c = cur
            if (c != null && kotlin.math.abs(tp.yDirAdj - curBase) < h * 0.6f) {
                c[0] = minOf(c[0], l); c[1] = minOf(c[1], t); c[2] = maxOf(c[2], r); c[3] = maxOf(c[3], b)
            } else {
                if (c != null) rects += norm(c, pw, ph)
                cur = floatArrayOf(l, t, r, b)
                curBase = tp.yDirAdj
            }
        }
        cur?.let { rects += norm(it, pw, ph) }
        return rects
    }

    private fun norm(c: FloatArray, pw: Float, ph: Float) = NormRect(
        (c[0] / pw).coerceIn(0f, 1f), (c[1] / ph).coerceIn(0f, 1f), (c[2] / pw).coerceIn(0f, 1f), (c[3] / ph).coerceIn(0f, 1f),
    )

    override fun close() { runCatching { doc.close() } }

    /** Gathers one page's text and the [TextPosition] of every character (null for the separators it adds). */
    internal class Collector : PDFTextStripper() {
        private val sb = StringBuilder()
        val positions = ArrayList<TextPosition?>()
        var pageSize: Pair<Float, Float> = 0f to 0f

        init { sortByPosition = true }

        fun text(): String = sb.toString()

        override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
            val box = page.cropBox
            pageSize = if (page.rotation % 180 != 0) box.height to box.width else box.width to box.height
            super.startPage(page)
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.length == textPositions.size) {
                sb.append(text); positions.addAll(textPositions)
            } else { // ligatures etc.: give every character the box of the whole run
                sb.append(text); repeat(text.length) { positions.add(textPositions.firstOrNull()) }
            }
        }

        override fun writeWordSeparator() { sb.append(' '); positions.add(null) }
        override fun writeLineSeparator() { sb.append('\n'); positions.add(null) }
        override fun writeParagraphSeparator() { sb.append('\n'); positions.add(null) }
    }
}
