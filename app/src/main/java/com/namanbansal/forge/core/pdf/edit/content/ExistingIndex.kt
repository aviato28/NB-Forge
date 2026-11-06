package com.namanbansal.forge.core.pdf.edit.content

import android.graphics.Bitmap
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A text block as the viewer shows it (page fractions), with what the toolbar needs. */
class ExBlock(val key: Int, val rect: NormRect, val text: String, val raw: TextBlock) {
    val bold: Boolean get() = raw.traits.bold
    val italic: Boolean get() = raw.traits.italic
}
class ExImage(val key: Int, val rect: NormRect, val inForm: Boolean, val inline: Boolean, val aspect: Float, val ctmTurns: Int = 0)
class ExPath(val key: Int, val rect: NormRect, val filled: Boolean, val stroked: Boolean)

/** What Edit existing finds on one page. [hint] explains why there is nothing (or little) to edit. */
class PageObjects(val page: Int, val widthPt: Float, val heightPt: Float, val blocks: List<ExBlock>, val images: List<ExImage>, val paths: List<ExPath>, val hint: String?) {
    companion object {
        const val SCANNED = "This page is a scanned image — text can't be edited yet (OCR is coming)."
        const val TYPE3 = "This page's text uses Type 3 fonts and can't be edited. Nothing was changed."
        const val OUTLINES = "This page's text is drawn as outlines or is hidden, so it can't be edited."
        const val SOME_LOCKED = "Some text on this page (rotated, outlined or Type 3) can't be edited."
    }
}

/** A line of the new text as it will sit on the page, in page fractions, for the on-screen preview. */
class PreviewLine(val text: String, val x: Float, val baseline: Float, val width: Float)
class TextPreview(val lines: List<PreviewLine>, val sizePt: Float, val leadingPt: Float, val notice: String?, val overlapText: String?, val box: NormRect, val fontName: String)

/**
 * The objects Edit existing offers on each page, found with [ContentWalker] and [TextBlocks]. Keeps one PDFBox document open
 * (behind a lock, call from a background thread) and the last few pages scanned. Also lays out edited text with the real fonts
 * for the on-screen preview, so what you see is what is written on save.
 */
class ExistingIndex(file: File, private val fonts: FontOpener) : Closeable {
    private val doc: PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES))
    private val lock = Any()
    private class Scanned(val scan: PageScan, val blocks: List<TextBlock>, val objects: PageObjects, val seen: Map<Int, Map<String, ByteArray>>)
    private val cache = object : LinkedHashMap<Int, Scanned>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Scanned>?) = size > 6 }
    private val noto = HashMap<String, PDType0Font>()
    val pageCount: Int = doc.numberOfPages

    private fun scanned(p: Int): Scanned = cache.getOrPut(p) {
        val page = doc.getPage(p)
        val geo = PdfAnnotIo.Geo(page)
        val cb = page.cropBox
        val box = Box(cb.lowerLeftX, cb.lowerLeftY, cb.upperRightX, cb.upperRightY)
        val scan = try { ContentWalker.scan(doc, page) } catch (e: Exception) { PageScan(emptyList(), emptyList(), emptyList(), false, 0) }
        val blocks = TextBlocks.detect(scan, box)
        fun n(b: Box) = geo.norm(b.l, b.b, b.r, b.t)
        val pageArea = box.w * box.h
        val bigImage = scan.images.any { it.box.w * it.box.h >= 0.5f * pageArea }
        val hint = when {
            blocks.isNotEmpty() -> if (scan.hasType3 || scan.lockedGlyphs > 0) PageObjects.SOME_LOCKED else null
            scan.hasType3 -> PageObjects.TYPE3
            bigImage || scan.invisibleText > 0 -> PageObjects.SCANNED
            scan.lockedGlyphs > 0 -> PageObjects.OUTLINES
            else -> null
        }
        val objs = PageObjects(
            p, geo.dispW, geo.dispH,
            blocks.map { ExBlock(it.key, n(it.box), it.text, it) },
            scan.images.map { ExImage(it.index, n(it.box), it.inForm, it.inline, if (it.pixelsH > 0) it.pixelsW.toFloat() / it.pixelsH else 1f) },
            scan.paths.map { ExPath(it.index, n(it.box), it.filled, it.stroked) },
            hint,
        )
        Scanned(scan, blocks, objs, TextFonts.seen(scan))
    }

    fun page(p: Int): PageObjects? = synchronized(lock) { if (p !in 0 until pageCount) null else runCatching { scanned(p).objects }.getOrNull() }

    /** The picture [key] of [page], scaled to at most [maxPx] on its long side; null for inline pictures or unreadable ones. */
    fun imageBitmap(p: Int, key: Int, maxPx: Int = 512): Bitmap? = synchronized(lock) {
        runCatching {
            val img = scanned(p).scan.images.getOrNull(key)?.xobject ?: return@runCatching null
            val full = img.image ?: return@runCatching null
            val k = min(1f, maxPx.toFloat() / max(full.width, full.height))
            if (k < 1f) Bitmap.createScaledBitmap(full, max(1, (full.width * k).toInt()), max(1, (full.height * k).toInt()), true) else full
        }.getOrNull()
    }

    /** Lays out [text] for [block] with the style changes, with real font metrics; the preview of an edit. */
    fun preview(p: Int, block: ExBlock, text: String, sizePt: Float, align: Int, bold: Boolean?, italic: Boolean?): TextPreview? = synchronized(lock) {
        runCatching {
            val sc = scanned(p)
            val b = block.raw
            val choice = TextFonts.choose(doc, fonts, b, sc.seen[b.fontId].orEmpty(), text, bold, italic, noto)
            val size = if (sizePt > 0f) sizePt else b.size
            val lay = TextLayout.layout(text, choice.coder, size, b.leading * size / b.size, if (align >= 0) align else b.align, b.left, b.maxWidth, b.firstBaseline, b.firstIndent)
            val geo = PdfAnnotIo.Geo(doc.getPage(p))
            val cb = doc.getPage(p).cropBox
            // lines in page fractions: x of the line start, baseline y (user space -> fractions through the same mapping as boxes)
            val lines = lay.lines.map { l ->
                val a = geo.norm(l.x, l.baseline, l.x + l.width, l.baseline)
                PreviewLine(l.text, a.left, a.top, a.right - a.left)
            }
            val ov = TextLayout.overlap(b, lay, sc.blocks)
            TextPreview(lines, size, b.leading * size / b.size, choice.notice, ov?.text, geo.norm(lay.box.l, lay.box.b, lay.box.r, lay.box.t), choice.name)
        }.getOrNull()
    }

    override fun close() { synchronized(lock) { runCatching { doc.close() } } }
}
