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
    /** The PDF has no text for some of these characters: the old text can't be shown, only replaced by typing new text. */
    val unreadable: Boolean get() = raw.unreadable
    /** Several fonts or colours (a bold word, a link): an edit keeps them where the text is unchanged. */
    val mixed: Boolean get() = raw.mixed
    /** Some of the text was read from the embedded font because the PDF has no ToUnicode map. */
    val recoveredFromFont: Boolean get() = raw.glyphs().any { it.recovered }
}

/** Why a piece of text can't be edited. [title] is a short headline, [detail] the plain-words explanation for the sheet. */
enum class LockReason(val title: String, val detail: String) {
    TYPE3("Type 3 font", "This text uses a Type 3 font: every letter is a tiny drawing inside the PDF, with no real text behind it. Forge can't change it. Use Whiteout or Redact to cover it, then add your own text."),
    VERTICAL("Vertical text", "This text runs top to bottom (a vertical writing mode). Forge can only edit horizontal text. Use Whiteout and Add text to replace it."),
    OUTLINES("Text drawn as outlines", "This text is drawn as outlines or an outline-only style, so there are no editable characters. Use Whiteout, then Add text."),
    ROTATED("Rotated text", "This text is turned more than a couple of degrees (or mirrored). Forge edits upright text and text that is only slightly skewed, like a scan. Use Whiteout, then Add text."),
    SCANNED("Scanned page", "This page is a picture of a document (a scan), so there is no text to edit. Forge can't read scanned pages yet; text recognition (OCR) is planned. Use Whiteout and Add text to change it."),
    HIDDEN("Hidden text", "This is invisible text that a scanner's text recognition placed over the picture. Edit the picture instead, or use Whiteout and Add text.");
}

/** A part of the page that can't be edited, and why (page fractions). */
class ExLocked(val rect: NormRect, val reason: LockReason)

/** A FreeText comment box: annotation text, not page content. */
class ExFreeText(val rect: NormRect, val text: String)

/** One character for the developer overlay: where it is, and whether Forge can edit it. */
class DebugBox(val rect: NormRect, val editable: Boolean, val note: String?, val recovered: Boolean, val unmapped: Boolean)
class ExImage(val key: Int, val rect: NormRect, val inForm: Boolean, val inline: Boolean, val aspect: Float, val ctmTurns: Int = 0)
class ExPath(val key: Int, val rect: NormRect, val filled: Boolean, val stroked: Boolean)

/** What Edit existing finds on one page. [hint] explains why there is nothing (or little) to edit. */
class PageObjects(
    val page: Int, val widthPt: Float, val heightPt: Float, val blocks: List<ExBlock>, val images: List<ExImage>, val paths: List<ExPath>, val hint: String?,
    val locked: List<ExLocked> = emptyList(), val freeText: List<ExFreeText> = emptyList(),
) {
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
        fun nw(b: Box) = n(scan.toPage(b)) // a box in the (skew-corrected) text frame
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
            blocks.map { ExBlock(it.key, nw(it.box), it.text, it) },
            scan.images.map { ExImage(it.index, n(it.box), it.inForm, it.inline, if (it.pixelsH > 0) it.pixelsW.toFloat() / it.pixelsH else 1f) },
            scan.paths.map { ExPath(it.index, n(it.box), it.filled, it.stroked) },
            hint,
            lockedAreas(scan, blocks.isEmpty() && (bigImage || scan.invisibleText > 0), geo, ::n),
            freeTexts(page, geo),
        )
        Scanned(scan, blocks, objs, TextFonts.seen(scan))
    }

    private fun reasonOf(g: Glyph): LockReason? = when (g.note) {
        "Type 3 font" -> LockReason.TYPE3
        "vertical text" -> LockReason.VERTICAL
        "outlined text" -> LockReason.OUTLINES
        "rotated text" -> LockReason.ROTATED
        "hidden text" -> LockReason.HIDDEN
        else -> null
    }

    /** The parts of the page whose text is locked, merged per run of characters, plus the whole page when it is a scan. */
    private fun lockedAreas(scan: PageScan, scanned: Boolean, geo: PdfAnnotIo.Geo, n: (Box) -> NormRect): List<ExLocked> {
        val out = ArrayList<ExLocked>()
        var cur: Box? = null; var curReason: LockReason? = null; var curSize = 0f
        fun flush() { val c = cur; val r = curReason; if (c != null && r != null) out += ExLocked(n(c), r); cur = null; curReason = null }
        for (g in scan.glyphs) {
            val r = if (g.editable) null else reasonOf(g)
            if (r == null) continue
            val c = cur
            if (c != null && r == curReason && g.pageBox.l - c.r < 1.5f * max(g.size, curSize) && g.pageBox.cy in c.b..c.t + 0.01f) { cur = c.union(g.pageBox); curSize = max(curSize, g.size) }
            else { flush(); cur = g.pageBox; curReason = r; curSize = g.size }
            if (out.size > 600) break
        }
        flush()
        if (scanned) out += ExLocked(NormRect(0f, 0f, 1f, 1f), if (scan.glyphs.any { it.note == "hidden text" }) LockReason.HIDDEN else LockReason.SCANNED)
        return out
    }

    private fun freeTexts(page: com.tom_roush.pdfbox.pdmodel.PDPage, geo: PdfAnnotIo.Geo): List<ExFreeText> = runCatching {
        page.annotations.filter { it.subtype == "FreeText" }.mapNotNull { a ->
            val r = a.rectangle ?: return@mapNotNull null
            ExFreeText(geo.norm(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY), a.contents.orEmpty())
        }
    }.getOrDefault(emptyList())

    /** Every character of page [p] for the developer overlay (green = editable, red = locked). */
    fun debugBoxes(p: Int): List<DebugBox> = synchronized(lock) {
        runCatching {
            val sc = scanned(p); val geo = PdfAnnotIo.Geo(doc.getPage(p))
            sc.scan.glyphs.filter { it.text.isNotBlank() || !it.editable }.map { g -> DebugBox(geo.norm(g.pageBox.l, g.pageBox.b, g.pageBox.r, g.pageBox.t), g.editable, g.note ?: if (g.editable) null else "not shown", g.recovered, g.unmapped) }
        }.getOrDefault(emptyList())
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
    fun preview(p: Int, block: ExBlock, text: String, sizePt: Float, align: Int, bold: Boolean?, italic: Boolean?, color: Int? = null): TextPreview? = synchronized(lock) {
        runCatching {
            val sc = scanned(p)
            val b = block.raw
            val plan = TextPlanner.plan(doc, fonts, b, sc.seen, text, sizePt, align, bold, italic, color, noto)
            val geo = PdfAnnotIo.Geo(doc.getPage(p))
            // lines in page fractions: start of the line and its baseline (working frame -> page -> fractions)
            val lines = plan.layout.lines.map { l ->
                val (x0, y0) = sc.scan.toPage(l.x, l.baseline); val (x1, y1) = sc.scan.toPage(l.x + l.width, l.baseline)
                val a = geo.norm(x0, y0, x1, y1)
                PreviewLine(l.text, a.left, a.top, a.right - a.left)
            }
            val ov = TextLayout.overlap(b, plan.layout, sc.blocks)
            val lb = sc.scan.toPage(plan.layout.box)
            TextPreview(lines, plan.size, b.leading * plan.size / b.size, plan.notice, ov?.text, geo.norm(lb.l, lb.b, lb.r, lb.t), plan.fontName)
        }.getOrNull()
    }

    override fun close() { synchronized(lock) { runCatching { doc.close() } } }
}
