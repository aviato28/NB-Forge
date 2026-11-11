package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A box in the page's default user space: left, bottom, right, top. */
class Box(val l: Float, val b: Float, val r: Float, val t: Float) {
    val w: Float get() = r - l
    val h: Float get() = t - b
    val cx: Float get() = (l + r) / 2
    val cy: Float get() = (b + t) / 2
    fun intersects(o: Box) = o.l < r && o.r > l && o.b < t && o.t > b
    fun union(o: Box) = Box(min(l, o.l), min(b, o.b), max(r, o.r), max(t, o.t))
    fun contains(x: Float, y: Float) = x in l..r && y in b..t
    fun inside(o: Box) = l >= o.l - 0.01f && r <= o.r + 0.01f && b >= o.b - 0.01f && t <= o.t + 0.01f
    override fun toString() = "Box($l,$b,$r,$t)"
}

/** Row-vector affine matrix `[a b c d e f]` as in PDF: `a.then(b)` applies a first. */
data class Aff(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun then(o: Aff) = Aff(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d, e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f)
    fun x(px: Float, py: Float) = px * a + py * c + e
    fun y(px: Float, py: Float) = px * b + py * d + f
    fun inverse(): Aff? {
        val det = a * d - b * c
        if (abs(det) < 1e-9f) return null
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Aff(ia, ib, ic, id, -(e * ia + f * ic), -(e * ib + f * id))
    }
    fun bounds(x0: Float, y0: Float, x1: Float, y1: Float): Box {
        val xs = floatArrayOf(x(x0, y0), x(x1, y0), x(x0, y1), x(x1, y1)); val ys = floatArrayOf(y(x0, y0), y(x1, y0), y(x0, y1), y(x1, y1))
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }
    companion object {
        val I = Aff(1f, 0f, 0f, 1f, 0f, 0f)
        fun move(tx: Float, ty: Float) = Aff(1f, 0f, 0f, 1f, tx, ty)
        fun scale(sx: Float, sy: Float) = Aff(sx, 0f, 0f, sy, 0f, 0f)
        /** Counter-clockwise by [rad] in a y-up space. */
        fun rotate(rad: Float): Aff { val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad); return Aff(c, s, -s, c, 0f, 0f) }
    }
}

/**
 * One character the page shows, in the order of the content stream (index = its identity for edits).
 * [box] / [baseX] / [baseY] are in the page's working frame: the page's user space turned by the page's skew (see [PageScan.skew]),
 * so slightly tilted scans group into straight lines; [pageBox] is where the character really is.
 */
class Glyph(
    val index: Int, val code: Int, val codeLen: Int, val text: String, val font: PDFont, val fontId: Int, val fontName: String,
    /** Size on the page in points (font size scaled by the text matrix and CTM). */
    val size: Float, val color: Int,
    val baseX: Float, val baseY: Float, val box: Box,
    /** False for text we can't edit safely (Type 3, rotated, mirrored, outlines-only render modes). */
    val editable: Boolean, val note: String? = null,
    val pageBox: Box = box,
    /** The font gives no text for this character (no ToUnicode, nothing recoverable): [text] is U+FFFD and the block can only be replaced. */
    val unmapped: Boolean = false,
    /** The text was recovered from the embedded font instead of a ToUnicode map. */
    val recovered: Boolean = false,
    internal val angle: Float = 0f,
    internal val m: Aff = Aff.I, internal val lowY: Float = 0f, internal val highY: Float = 0f, internal val width: Float = 0f, internal val rise: Float = 0f,
) {
    /** The same character seen in the page's working frame, locked when its angle differs from the page's skew by more than [ROT_TOL]. */
    internal fun refit(skew: Float): Glyph {
        val straight = abs(angle - skew) < ROT_TOL
        val ed = editable && straight
        val nt = if (editable && !straight) "rotated text" else note
        if (skew == 0f) return if (ed == editable) this else Glyph(index, code, codeLen, text, font, fontId, fontName, size, color, baseX, baseY, box, ed, nt, pageBox, unmapped, recovered, angle, m, lowY, highY, width, rise)
        val mm = m.then(Aff.rotate(-skew))
        return Glyph(index, code, codeLen, text, font, fontId, fontName, size, color, mm.x(0f, rise), mm.y(0f, rise), mm.bounds(0f, lowY, width, highY), ed, nt, pageBox, unmapped, recovered, angle, m, lowY, highY, width, rise)
    }

    companion object { const val ROT_TOL = 0.03f; const val SKEW_MAX = 0.04f }
}

/** A picture drawn on the page. [ctm] maps the unit square to the page; [index] counts pictures in drawing order. */
class ImageRef(val index: Int, val ctm: Aff, val box: Box, val inline: Boolean, val pixelsW: Int, val pixelsH: Int, val inForm: Boolean, val xobject: PDImageXObject? = null)

/** A vector path that is painted (not just a clip). */
class PathRef(val index: Int, val box: Box, val filled: Boolean, val stroked: Boolean, val inForm: Boolean)

/** Everything on one page that Edit existing can select. */
class PageScan(
    val glyphs: List<Glyph>, val images: List<ImageRef>, val paths: List<PathRef>, val hasType3: Boolean, val invisibleText: Int,
    /** The page's text is tilted by this many radians (scanner skew, under about 2 degrees); glyph working frames are turned back by it. */
    val skew: Float = 0f,
) {
    val editableGlyphs: List<Glyph> get() = glyphs.filter { it.editable }
    /** Visible characters we leave alone (outlined, rotated, Type 3, vertical). */
    val lockedGlyphs: Int get() = glyphs.count { !it.editable && it.note != null }

    /** A box in the working frame -> the area it covers on the page. */
    fun toPage(b: Box): Box = if (skew == 0f) b else Aff.rotate(skew).bounds(b.l, b.b, b.r, b.t)
    /** A point in the working frame -> the page. */
    fun toPage(x: Float, y: Float): Pair<Float, Float> = if (skew == 0f) x to y else Aff.rotate(skew).let { it.x(x, y) to it.y(x, y) }
}

/** What to do to a picture: [delete] it, or draw it with [target] (a page-space matrix from the current one), or [replace] it. */
class ImageOp(val delete: Boolean = false, val target: ((Aff) -> Aff)? = null, val replacement: ((PDResources) -> COSName?)? = null)

/** The changes one rewrite pass makes. */
class ContentChanges(
    val removeGlyphs: Set<Int> = emptySet(), val removePaths: Set<Int> = emptySet(), val imageOps: Map<Int, ImageOp> = emptyMap(),
) {
    val isEmpty: Boolean get() = removeGlyphs.isEmpty() && removePaths.isEmpty() && imageOps.isEmpty()
}

/**
 * Walks a page's content stream (and the Form XObjects it draws) the way [com.namanbansal.forge.core.pdf.edit.Redactor] does:
 * graphics state, text matrices, fonts, colours. [scan] lists the glyphs, pictures and painted paths with a stable index each
 * (their order of appearance); [rewrite] removes glyphs / paths and moves, replaces or deletes pictures by those indices only,
 * so neighbours are untouched. Forms are rewritten copy-on-write.
 */
object ContentWalker {
    private const val MAX_DEPTH = 8
    private val PATH_BUILD = setOf("m", "l", "c", "v", "y", "re", "h")
    private val PAINT = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n")

    private class GS(
        var ctm: Aff, var font: PDFont? = null, var fs: Float = 0f, var tc: Float = 0f, var tw: Float = 0f, var th: Float = 1f,
        var tl: Float = 0f, var rise: Float = 0f, var mode: Int = 0, var fill: Int = 0xFF000000.toInt(), var fillCs: PDColorSpace? = null, var lineW: Float = 1f,
    ) { fun copy() = GS(ctm, font, fs, tc, tw, th, tl, rise, mode, fill, fillCs, lineW) }

    private class Pass(val doc: PDDocument, val changes: ContentChanges) {
        val glyphs = ArrayList<Glyph>(); val images = ArrayList<ImageRef>(); val paths = ArrayList<PathRef>()
        var type3 = false; var invisible = 0
        /** Page-space area pictures were moved into while inside a form: the form's clip must grow to show them. */
        var needBox: Box? = null
        val seen = IdentityHashMap<Any, Boolean>()
        val fontIds = IdentityHashMap<Any, Int>()
        val recovery = GlyphRecovery()
        fun fontId(f: PDFont): Int = fontIds.getOrPut(f.cosObject) { fontIds.size }
    }

    fun scan(doc: PDDocument, page: PDPage): PageScan {
        val ps = Pass(doc, ContentChanges())
        run(ps, page)
        val skew = skewOf(ps.glyphs)
        return PageScan(ps.glyphs.map { it.refit(skew) }, ps.images, ps.paths, ps.type3, ps.invisible, skew)
    }

    /** The page's text tilt: the median angle of its nearly upright text, or 0 when it is straight (under 0.2 degrees). */
    private fun skewOf(glyphs: List<Glyph>): Float {
        val angles = glyphs.filter { it.editable && it.text.isNotBlank() }.map { it.angle }.sorted()
        if (angles.isEmpty()) return 0f
        val med = angles[angles.size / 2]
        return if (abs(med) < 0.0035f) 0f else med
    }

    /** Rewrites the page content with [changes]; returns false when nothing needed to change. */
    fun rewrite(doc: PDDocument, page: PDPage, changes: ContentChanges): Boolean {
        if (changes.isEmpty) return false
        val ps = Pass(doc, changes)
        val out = run(ps, page) ?: return false
        val ns = PDStream(doc)
        ns.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.setContents(ns)
        return true
    }

    private fun run(ps: Pass, page: PDPage): List<Any>? {
        if (page.contents == null) return null
        val res = page.resources ?: return null
        val tokens = PDFStreamParser(page).also { it.parse() }.tokens
        val (out, changed) = process(ps, tokens, res, GS(Aff.I), 0)
        return if (changed) out else null
    }

    private fun num(o: Any?): Float = (o as? COSNumber)?.floatValue() ?: 0f

    private fun rgbInt(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun cmyk(c: Float, m: Float, y: Float, k: Float) = rgbInt((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))

    private fun setColor(gs: GS, ops: List<Any>) {
        val v = ops.filterIsInstance<COSNumber>().map { it.floatValue() }
        if (ops.any { it is COSName }) return
        val cs = gs.fillCs
        gs.fill = when {
            cs != null && v.size >= cs.numberOfComponents -> runCatching { val rgb = cs.toRGB(v.toFloatArray()); rgbInt(rgb[0], rgb[1], rgb[2]) }.getOrDefault(gs.fill)
            v.size == 1 -> rgbInt(v[0], v[0], v[0])
            v.size == 3 -> rgbInt(v[0], v[1], v[2])
            v.size == 4 -> cmyk(v[0], v[1], v[2], v[3])
            else -> gs.fill
        }
    }

    private fun process(ps: Pass, tokens: List<Any>, res: PDResources, start: GS, depth: Int): Pair<List<Any>, Boolean> {
        val ch = ps.changes
        val out = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        val stack = ArrayDeque<GS>()
        var gs = start.copy()
        var tm = Aff.I; var tlm = Aff.I
        var changed = false
        val pathBuf = ArrayList<Any>(); var havePath = false; var clip = false
        var px0 = Float.MAX_VALUE; var py0 = Float.MAX_VALUE; var px1 = -Float.MAX_VALUE; var py1 = -Float.MAX_VALUE
        val inForm = depth > 0
        fun pt(x: Float, y: Float) { val tx = gs.ctm.x(x, y); val ty = gs.ctm.y(x, y); px0 = min(px0, tx); px1 = max(px1, tx); py0 = min(py0, ty); py1 = max(py1, ty) }
        fun flushPath() { out.addAll(pathBuf); pathBuf.clear(); havePath = false; clip = false }
        fun pass(op: Operator) { out.addAll(operands); out.add(op); operands.clear() }
        fun op(n: String) = Operator.getOperator(n)

        fun show(bytes: ByteArray, sink: MutableList<COSBase>): Boolean {
            val font = gs.font ?: return false
            val type3 = font is PDType3Font
            if (type3) ps.type3 = true
            val input = ByteArrayInputStream(bytes)
            val keep = ByteArrayOutputStream()
            var gap = 0f; var any = false
            fun flushKeep() { if (keep.size() > 0) { sink += COSString(keep.toByteArray()); keep.reset() } }
            val vertical = runCatching { font.isVertical }.getOrDefault(false)
            val desc = runCatching { font.fontDescriptor }.getOrNull()
            val asc = (desc?.ascent?.div(1000f) ?: 0f).let { if (it in 0.5f..1.3f) it else 0.8f }
            val dsc = (desc?.descent?.div(1000f) ?: 0f).let { if (it in -0.6f..-0.01f) it else if (it in 0.01f..0.6f) -it else -0.22f }
            val fname = runCatching { font.name }.getOrNull() ?: "?"
            while (input.available() > 0) {
                val before = input.available()
                val code = try { font.readCode(input) } catch (_: Exception) { break }
                val len = (before - input.available()).coerceAtLeast(1)
                val startIdx = bytes.size - before
                val dx = runCatching { font.getDisplacement(code).x }.getOrDefault(0f)
                val space = len == 1 && code == 32
                val extra = gs.tc + if (space) gs.tw else 0f
                val adv = (dx * gs.fs + extra) * gs.th
                val index = ps.glyphs.size
                val m = tm.then(gs.ctm)
                val gw = dx * gs.fs * gs.th
                val box = m.bounds(0f, gs.rise + dsc * gs.fs, gw, gs.rise + asc * gs.fs)
                var uni = runCatching { font.toUnicode(code) }.getOrNull()
                var recovered = false
                if (uni.isNullOrEmpty() && len == 1 && code == 32) uni = " "
                if (uni.isNullOrEmpty() && !type3) { uni = ps.recovery.recover(font, code); recovered = !uni.isNullOrEmpty() }
                val unmapped = uni.isNullOrEmpty()
                val angle = atan2(m.b, m.a)
                val upright = abs(angle) < Glyph.SKEW_MAX && m.d > 0f && m.a > 0f
                val size = gs.fs * hypot(m.c, m.d)
                var note: String? = null
                val editable = when {
                    type3 -> { note = "Type 3 font"; false }
                    vertical -> { note = "vertical text"; false }
                    gs.mode == 3 -> { ps.invisible++; false }
                    gs.mode != 0 && gs.mode != 2 -> { note = "outlined text"; false }
                    !upright -> { note = "rotated text"; false }
                    gs.fs == 0f || size <= 0.5f -> false
                    else -> true
                }
                val shown = if (unmapped) "\uFFFD" else uni!!
                ps.glyphs += Glyph(
                    index, code, len, shown, font, ps.fontId(font), fname, size, gs.fill, m.x(0f, gs.rise), m.y(0f, gs.rise), box, editable, note,
                    pageBox = box, unmapped = unmapped, recovered = recovered, angle = angle, m = m, lowY = gs.rise + dsc * gs.fs, highY = gs.rise + asc * gs.fs, width = gw, rise = gs.rise,
                )
                if (index in ch.removeGlyphs) {
                    flushKeep(); any = true
                    gap += -(dx * 1000f + 1000f * extra / gs.fs)
                } else {
                    if (gap != 0f) { sink += COSFloat(gap); gap = 0f }
                    keep.write(bytes, startIdx, min(len, bytes.size - startIdx))
                }
                tm = Aff.move(adv, 0f).then(tm)
            }
            flushKeep()
            if (gap != 0f) sink += COSFloat(gap)
            return any
        }

        fun showArray(arr: COSArray): COSArray? {
            val sink = ArrayList<COSBase>(); var any = false
            for (el in arr) {
                when (el) {
                    is COSString -> if (show(el.bytes, sink)) any = true
                    is COSNumber -> { sink += el; tm = Aff.move(-el.floatValue() / 1000f * gs.fs * gs.th, 0f).then(tm) }
                    else -> sink += el
                }
            }
            return if (any) COSArray().also { a -> sink.forEach { a.add(it) } } else null
        }

        fun nextLine() { tlm = Aff.move(0f, -gs.tl).then(tlm); tm = tlm }

        /** Emits a picture operator with the edit for picture [idx], or as it was. */
        fun picture(idx: Int, drawTokens: () -> Unit, ctm: Aff) {
            val o = ch.imageOps[idx]
            when {
                o == null -> drawTokens()
                o.delete -> { changed = true; operands.clear() }
                else -> {
                    changed = true
                    val repl = o.replacement
                    val target = o.target?.invoke(ctm)
                    val inv = ctm.inverse()
                    if (target == null || inv == null) { if (repl == null) drawTokens() else operands.clear(); return }
                    val corr = target.then(inv)
                    target.bounds(0f, 0f, 1f, 1f).let { tb -> ps.needBox = ps.needBox?.union(tb) ?: tb }
                    out.add(op("q")); listOf(corr.a, corr.b, corr.c, corr.d, corr.e, corr.f).forEach { out.add(COSFloat(it)) }; out.add(op("cm"))
                    if (repl != null) {
                        val name = repl(res)
                        if (name != null) { out.add(name); out.add(op("Do")); operands.clear() } else drawTokens()
                    } else drawTokens()
                    out.add(op("Q"))
                }
            }
        }

        for (tok in tokens) {
            if (tok !is Operator) { operands += tok; continue }
            val name = tok.name
            if (name in PATH_BUILD) {
                if (!havePath) { havePath = true; px0 = Float.MAX_VALUE; py0 = Float.MAX_VALUE; px1 = -Float.MAX_VALUE; py1 = -Float.MAX_VALUE }
                when (name) {
                    "m", "l" -> pt(num(operands.getOrNull(0)), num(operands.getOrNull(1)))
                    "c" -> { pt(num(operands.getOrNull(0)), num(operands.getOrNull(1))); pt(num(operands.getOrNull(2)), num(operands.getOrNull(3))); pt(num(operands.getOrNull(4)), num(operands.getOrNull(5))) }
                    "v", "y" -> { pt(num(operands.getOrNull(0)), num(operands.getOrNull(1))); pt(num(operands.getOrNull(2)), num(operands.getOrNull(3))) }
                    "re" -> { val x = num(operands.getOrNull(0)); val y = num(operands.getOrNull(1)); val w = num(operands.getOrNull(2)); val h = num(operands.getOrNull(3)); pt(x, y); pt(x + w, y); pt(x, y + h); pt(x + w, y + h) }
                }
                pathBuf.addAll(operands); pathBuf.add(tok); operands.clear(); continue
            }
            if (name == "W" || name == "W*") { pathBuf.add(tok); clip = true; operands.clear(); continue }
            if (name in PAINT) {
                if (havePath) {
                    val counted = !clip && name != "n" && px0 <= px1
                    if (counted) {
                        val idx = ps.paths.size
                        val pad = if (name in setOf("f", "F", "f*")) 0.2f else max(0.5f, gs.lineW * hypot(gs.ctm.a, gs.ctm.b) / 2f)
                        ps.paths += PathRef(idx, Box(px0 - pad, py0 - pad, px1 + pad, py1 + pad), name !in setOf("S", "s"), name in setOf("S", "s", "B", "B*", "b", "b*"), inForm)
                        if (idx in ch.removePaths) { pathBuf.clear(); havePath = false; changed = true; operands.clear(); continue }
                    }
                    flushPath()
                }
                pass(tok); continue
            }
            if (havePath) flushPath()
            when (name) {
                "q" -> { stack.addLast(gs.copy()); pass(tok) }
                "Q" -> { stack.removeLastOrNull()?.let { gs = it }; pass(tok) }
                "cm" -> { gs.ctm = Aff(num(operands.getOrNull(0)), num(operands.getOrNull(1)), num(operands.getOrNull(2)), num(operands.getOrNull(3)), num(operands.getOrNull(4)), num(operands.getOrNull(5))).then(gs.ctm); pass(tok) }
                "w" -> { gs.lineW = num(operands.getOrNull(0)); pass(tok) }
                "g" -> { val v = num(operands.getOrNull(0)); gs.fill = rgbInt(v, v, v); gs.fillCs = PDDeviceGray.INSTANCE; pass(tok) }
                "rg" -> { gs.fill = rgbInt(num(operands.getOrNull(0)), num(operands.getOrNull(1)), num(operands.getOrNull(2))); gs.fillCs = PDDeviceRGB.INSTANCE; pass(tok) }
                "k" -> { gs.fill = cmyk(num(operands.getOrNull(0)), num(operands.getOrNull(1)), num(operands.getOrNull(2)), num(operands.getOrNull(3))); gs.fillCs = null; pass(tok) }
                "cs" -> {
                    val n = operands.lastOrNull() as? COSName
                    gs.fillCs = n?.let { runCatching { res.getColorSpace(it) }.getOrNull() }
                    gs.fillCs?.let { cs -> runCatching { val init = cs.initialColor.components; val rgb = cs.toRGB(init); gs.fill = rgbInt(rgb[0], rgb[1], rgb[2]) } }
                    pass(tok)
                }
                "sc", "scn" -> { setColor(gs, operands); pass(tok) }
                "BT" -> { tm = Aff.I; tlm = Aff.I; pass(tok) }
                "Tf" -> {
                    val n = operands.getOrNull(0) as? COSName
                    gs.font = n?.let { runCatching { res.getFont(it) }.getOrNull() }
                    gs.fs = num(operands.getOrNull(1)); pass(tok)
                }
                "Tc" -> { gs.tc = num(operands.getOrNull(0)); pass(tok) }
                "Tw" -> { gs.tw = num(operands.getOrNull(0)); pass(tok) }
                "Tz" -> { gs.th = num(operands.getOrNull(0)) / 100f; pass(tok) }
                "TL" -> { gs.tl = num(operands.getOrNull(0)); pass(tok) }
                "Ts" -> { gs.rise = num(operands.getOrNull(0)); pass(tok) }
                "Tr" -> { gs.mode = num(operands.getOrNull(0)).toInt(); pass(tok) }
                "Td" -> { tlm = Aff.move(num(operands.getOrNull(0)), num(operands.getOrNull(1))).then(tlm); tm = tlm; pass(tok) }
                "TD" -> { gs.tl = -num(operands.getOrNull(1)); tlm = Aff.move(num(operands.getOrNull(0)), num(operands.getOrNull(1))).then(tlm); tm = tlm; pass(tok) }
                "Tm" -> { tlm = Aff(num(operands.getOrNull(0)), num(operands.getOrNull(1)), num(operands.getOrNull(2)), num(operands.getOrNull(3)), num(operands.getOrNull(4)), num(operands.getOrNull(5))); tm = tlm; pass(tok) }
                "T*" -> { nextLine(); pass(tok) }
                "Tj" -> {
                    val s = operands.lastOrNull() as? COSString
                    val sink = ArrayList<COSBase>()
                    if (s != null && show(s.bytes, sink)) { out.add(COSArray().also { a -> sink.forEach { a.add(it) } }); out.add(op("TJ")); changed = true; operands.clear() } else pass(tok)
                }
                "TJ" -> {
                    val arr = operands.lastOrNull() as? COSArray
                    val repl = if (arr != null) showArray(arr) else null
                    if (repl != null) { out.add(repl); out.add(op("TJ")); changed = true; operands.clear() } else pass(tok)
                }
                "'" -> {
                    nextLine()
                    val s = operands.lastOrNull() as? COSString
                    val sink = ArrayList<COSBase>()
                    if (s != null && show(s.bytes, sink)) { out.add(op("T*")); out.add(COSArray().also { a -> sink.forEach { a.add(it) } }); out.add(op("TJ")); changed = true; operands.clear() } else pass(tok)
                }
                "\"" -> {
                    gs.tw = num(operands.getOrNull(0)); gs.tc = num(operands.getOrNull(1)); nextLine()
                    val s = operands.lastOrNull() as? COSString
                    val sink = ArrayList<COSBase>()
                    if (s != null && show(s.bytes, sink)) {
                        out.add(operands[0]); out.add(op("Tw")); out.add(operands[1]); out.add(op("Tc")); out.add(op("T*"))
                        out.add(COSArray().also { a -> sink.forEach { a.add(it) } }); out.add(op("TJ")); changed = true; operands.clear()
                    } else pass(tok)
                }
                "BI" -> {
                    val idx = ps.images.size
                    val params = tok.imageParameters
                    val pw = params?.getInt("W", params.getInt("Width", 0)) ?: 0; val ph = params?.getInt("H", params.getInt("Height", 0)) ?: 0
                    ps.images += ImageRef(idx, gs.ctm, gs.ctm.bounds(0f, 0f, 1f, 1f), true, pw, ph, inForm)
                    picture(idx, { pass(tok) }, gs.ctm)
                }
                "Do" -> {
                    val nm = operands.lastOrNull() as? COSName
                    val xo = nm?.let { runCatching { res.getXObject(it) }.getOrNull() }
                    when {
                        xo is PDImageXObject -> {
                            val idx = ps.images.size
                            ps.images += ImageRef(idx, gs.ctm, gs.ctm.bounds(0f, 0f, 1f, 1f), false, xo.width, xo.height, inForm, xo)
                            picture(idx, { pass(tok) }, gs.ctm)
                        }
                        xo is PDFormXObject && depth < MAX_DEPTH && ps.seen[xo.cosObject] != true -> {
                            val fm = xo.matrix
                            val child = Aff(fm.getValue(0, 0), fm.getValue(0, 1), fm.getValue(1, 0), fm.getValue(1, 1), fm.getValue(2, 0), fm.getValue(2, 1)).then(gs.ctm)
                            ps.seen[xo.cosObject] = true
                            val fres = xo.resources ?: res
                            val inner = PDFStreamParser(xo).also { it.parse() }.tokens
                            val outerNeed = ps.needBox; ps.needBox = null
                            val (nt, c) = process(ps, inner, fres, gs.copy().also { it.ctm = child }, depth + 1)
                            val need = ps.needBox
                            ps.needBox = if (need == null) outerNeed else outerNeed?.union(need) ?: need
                            ps.seen.remove(xo.cosObject)
                            if (!c) pass(tok)
                            else {
                                val cs = ps.doc.document.createCOSStream()
                                cs.addAll(xo.cosObject)
                                cs.removeItem(COSName.LENGTH); cs.removeItem(COSName.FILTER); cs.removeItem(COSName.DECODE_PARMS)
                                cs.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(nt) }
                                val copy = PDFormXObject(cs)
                                val bb = xo.bBox
                                val inv = child.inverse()
                                if (need != null && bb != null && inv != null) {
                                    val nb = inv.bounds(need.l, need.b, need.r, need.t)
                                    copy.bBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(min(bb.lowerLeftX, nb.l), min(bb.lowerLeftY, nb.b), max(bb.upperRightX, nb.r) - min(bb.lowerLeftX, nb.l), max(bb.upperRightY, nb.t) - min(bb.lowerLeftY, nb.b))
                                }
                                out.add(res.add(copy)); out.add(tok); operands.clear(); changed = true
                            }
                        }
                        else -> pass(tok)
                    }
                }
                else -> pass(tok)
            }
        }
        if (havePath) flushPath()
        out.addAll(operands)
        return out to changed
    }

}
