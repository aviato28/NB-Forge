package com.namanbansal.forge.core.pdf.viewer

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfTextSearch
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.Closeable
import java.io.File

/**
 * Lazy per-page text, links and the outline of one PDF, for selection, link taps and the contents panel. One PDFBox document
 * is shared behind a lock (PDFBox is not thread-safe); call from a background thread. Pages are extracted on first use and the
 * most recent ones are kept.
 */
class PdfDocIndex(file: File) : Closeable {
    private val doc: PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES))
    private val lock = Any()
    private val texts = object : LinkedHashMap<Int, PageText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?) = size > MAX_TEXT_PAGES
    }
    private val links = HashMap<Int, List<PageLink>>()
    private var outline: List<OutlineItem>? = null

    val pageCount: Int = doc.numberOfPages

    /** Text of 0-based [page]; empty text for scanned / unreadable pages. */
    fun text(page: Int): PageText = synchronized(lock) {
        texts.getOrPut(page) { extract(page) }
    }

    private fun extract(page: Int): PageText {
        val c = PdfTextSearch.Collector()
        try {
            c.setStartPage(page + 1); c.setEndPage(page + 1)
            c.getText(doc)
        } catch (_: Exception) {
            return PageText("", emptyList())
        }
        val text = c.text()
        val pdPage = try { doc.getPage(page) } catch (_: Exception) { return PageText("", emptyList()) }
        val box = pdPage.cropBox
        if (box.width <= 0f || box.height <= 0f) return PageText("", emptyList())
        // PDFBox's "direction adjusted" coordinates follow the text, not the page, so build each glyph box in page space from
        // the text matrix (origin, direction, size) and map it exactly like a link: it then lands right on rotated pages too.
        val rects = c.positions.map { tp ->
            tp ?: return@map null
            val m = tp.textMatrix
            val ox = m.translateX; val oy = m.translateY
            val theta = kotlin.math.atan2(m.getValue(0, 1), m.getValue(0, 0))
            val ux = kotlin.math.cos(theta); val uy = kotlin.math.sin(theta)
            val h = tp.heightDir.coerceAtLeast(1f); val w = tp.widthDirAdj
            // along the text by w, across it from a quarter below the baseline to the full height above it
            val xs = floatArrayOf(ox - (-uy) * 0.25f * h, ox + (-uy) * h, ox + ux * w - (-uy) * 0.25f * h, ox + ux * w + (-uy) * h)
            val ys = floatArrayOf(oy - ux * 0.25f * h, oy + ux * h, oy + uy * w - ux * 0.25f * h, oy + uy * w + ux * h)
            LinkGeometry.normalize(xs.min(), ys.min(), xs.max(), ys.max(), 0f, 0f, box.width, box.height, pdPage.rotation)
        }
        return PageText(text, rects)
    }

    fun links(page: Int): List<PageLink> = synchronized(lock) {
        links.getOrPut(page) { readLinks(page) }
    }

    private fun readLinks(page: Int): List<PageLink> {
        val out = ArrayList<PageLink>()
        try {
            val p = doc.getPage(page)
            val box = p.cropBox
            for (a in p.annotations) {
                if (a !is PDAnnotationLink) continue
                val r = a.rectangle ?: continue
                val action = a.action
                var uri: String? = null; var target: Int? = null
                try {
                    when (action) {
                        is PDActionURI -> uri = action.uri
                        is PDActionGoTo -> target = PdfOutline.pageOfDestination(doc, action.destination)
                        else -> target = PdfOutline.pageOfDestination(doc, a.destination)
                    }
                } catch (_: Exception) { continue }
                if (uri.isNullOrBlank() && target == null) continue
                out += PageLink(
                    LinkGeometry.normalize(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY, box.lowerLeftX, box.lowerLeftY, box.width, box.height, p.rotation),
                    uri?.trim(), target,
                )
            }
        } catch (_: Exception) { /* a page with broken annotations simply has no links */ }
        return out
    }

    fun outline(): List<OutlineItem> = synchronized(lock) { outline ?: PdfOutline.parse(doc).also { outline = it } }

    override fun close() { synchronized(lock) { runCatching { doc.close() } } }

    private companion object { const val MAX_TEXT_PAGES = 12 }
}
