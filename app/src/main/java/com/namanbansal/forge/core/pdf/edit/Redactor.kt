package com.namanbansal.forge.core.pdf.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfTextSearch
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What a redaction did, in words for the result message. */
class RedactionReport {
    var glyphs = 0
    var imagesClipped = 0
    var imagesDropped = 0
    var pathsRemoved = 0
    var pathsCrossing = 0
    var annotationsRemoved = 0
    var infoRemoved = false
    val notes = LinkedHashSet<String>()
    /** Text removed per page (0-based), as runs. */
    val removedText = HashMap<Int, MutableList<String>>()

    val summary: String get() = buildString {
        val parts = ArrayList<String>()
        if (glyphs > 0) parts += "$glyphs characters of text"
        if (imagesClipped + imagesDropped > 0) parts += "${imagesClipped + imagesDropped} picture area(s)"
        if (pathsRemoved > 0) parts += "$pathsRemoved drawing(s)"
        if (annotationsRemoved > 0) parts += "$annotationsRemoved annotation(s)"
        append(if (parts.isEmpty()) "Nothing was found under the marked areas, they were blacked out." else "Removed " + parts.joinToString(", ") + ".")
        if (infoRemoved) append(" Document info and metadata removed.")
        notes.forEach { append(' ').append(it) }
    }
}

/** For tests: lets a test tamper with the document after the rewrite, to prove that a failed check aborts. */
class RedactHooks(val afterRewrite: (PDDocument) -> Unit = {})

/**
 * True redaction. For every marked area: text-showing operators whose glyphs fall inside are removed (split out of Tj / TJ with
 * the following text kept in place), pictures are re-encoded with the area blacked out (or dropped when fully covered), vector
 * paths fully inside are dropped, annotations inside are deleted and a black box is drawn. Form XObjects are handled
 * recursively (copy-on-write, so other pages that share a form keep theirs). The result is checked by extracting the text again.
 */
object Redactor {
    private const val MAX_DEPTH = 8

    /** Areas in user space: l, b, r, t. */
    private class Box(val l: Float, val b: Float, val r: Float, val t: Float) {
        fun intersects(o: Box) = o.l < r && o.r > l && o.b < t && o.t > b
        fun contains(o: Box) = o.l >= l - 0.01f && o.r <= r + 0.01f && o.b >= b - 0.01f && o.t <= t + 0.01f
    }

    private data class Aff(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
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
        companion object { val I = Aff(1f, 0f, 0f, 1f, 0f, 0f); fun move(tx: Float, ty: Float) = Aff(1f, 0f, 0f, 1f, tx, ty) }
    }

    private class GS(var ctm: Aff, var font: PDFont? = null, var fs: Float = 0f, var tc: Float = 0f, var tw: Float = 0f, var th: Float = 1f, var tl: Float = 0f, var rise: Float = 0f) {
        fun copy() = GS(ctm, font, fs, tc, tw, th, tl, rise)
    }

    private class Ctx(val doc: PDDocument, val areas: List<Box>, val page: Int, val report: RedactionReport) {
        val seen = IdentityHashMap<Any, Boolean>()
        fun hit(b: Box) = areas.any { it.intersects(b) }
        fun inside(b: Box) = areas.any { it.contains(b) }
    }

    private val PATH_BUILD = setOf("m", "l", "c", "v", "y", "re", "h")
    private val PAINT = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n")

    // ---- entry points ----------------------------------------------------------------------------------------------------

    /**
     * Redacts [marks] in [file] in place and verifies the result. On any problem (including a failed verification) the file is
     * left exactly as it was and a [ToolException] says why.
     */
    fun redactInPlace(file: File, marks: List<RedactMark>, options: RedactOptions, hooks: RedactHooks = RedactHooks()): RedactionReport {
        val tmp = File(file.parentFile, file.name + ".redact.tmp")
        try {
            val report = RedactionReport()
            PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
                val before = pageTexts(doc, marks.map { it.page }.toSet())
                redact(doc, marks, options, report)
                hooks.afterRewrite(doc)
                tmp.outputStream().use { doc.save(it) }
                verify(tmp, doc, marks, report, before)
            }
            if (!tmp.renameTo(file)) file.writeBytes(tmp.readBytes())
            return report
        } catch (e: ToolException) { throw e
        } catch (e: OutOfMemoryError) { throw ToolException("This page is too large to redact safely on this phone. Nothing was changed.")
        } catch (e: Exception) { throw ToolException("Couldn't redact this PDF safely (${e.message ?: e.javaClass.simpleName}). Nothing was changed.", e)
        } finally { tmp.delete() }
    }

    private fun pageTexts(doc: PDDocument, pages: Set<Int>): Map<Int, String> =
        pages.filter { it in 0 until doc.numberOfPages }.associateWith { p ->
            PdfTextSearch.Collector().also { it.setStartPage(p + 1); it.setEndPage(p + 1); it.getText(doc) }.text()
        }

    fun redact(doc: PDDocument, marks: List<RedactMark>, options: RedactOptions, report: RedactionReport) {
        for ((p, list) in marks.groupBy { it.page }) {
            if (p !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(p)
            val geo = PdfAnnotIo.Geo(page)
            val areas = list.map { m ->
                val a = geo.user(m.rect.left, m.rect.top); val b = geo.user(m.rect.right, m.rect.bottom)
                Box(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
            }
            val ctx = Ctx(doc, areas, p, report)
            redactContent(ctx, page)
            redactAnnotations(ctx, page)
            page.cosObject.removeItem(COSName.getPDFName("Thumb"))
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.setNonStrokingColor(0f, 0f, 0f)
                for (a in areas) { cs.addRect(a.l, a.b, a.r - a.l, a.t - a.b); cs.fill() }
            }
        }
        if (options.removeDocumentInfo) {
            doc.documentInformation = PDDocumentInformation()
            doc.documentCatalog.metadata = null
            for (i in 0 until doc.numberOfPages) doc.getPage(i).metadata = null
            report.infoRemoved = true
        }
    }

    // ---- content streams -----------------------------------------------------------------------------------------------

    private fun redactContent(ctx: Ctx, page: PDPage) {
        if (page.contents == null) return
        val res = page.resources ?: return
        val tokens = PDFStreamParser(page).also { it.parse() }.tokens
        noteType3(ctx, res)
        val (out, changed) = process(ctx, tokens, res, GS(Aff.I), 0)
        if (!changed) return
        val ns = PDStream(ctx.doc)
        ns.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.setContents(ns)
    }

    private fun noteType3(ctx: Ctx, res: PDResources) {
        val has = try { res.fontNames.any { runCatching { res.getFont(it) }.getOrNull() is PDType3Font } } catch (_: Exception) { false }
        if (has) ctx.report.notes += "Page ${ctx.page + 1} uses Type 3 fonts: their text was removed by position, which can't be fully verified."
    }

    private fun num(o: Any?): Float = (o as? COSNumber)?.floatValue() ?: 0f

    private fun process(ctx: Ctx, tokens: List<Any>, res: PDResources, start: GS, depth: Int): Pair<List<Any>, Boolean> {
        val out = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        val stack = ArrayDeque<GS>()
        var gs = start.copy()
        var tm = Aff.I; var tlm = Aff.I
        var changed = false
        val pathBuf = ArrayList<Any>(); var havePath = false; var clip = false
        var px0 = Float.MAX_VALUE; var py0 = Float.MAX_VALUE; var px1 = -Float.MAX_VALUE; var py1 = -Float.MAX_VALUE
        fun pt(x: Float, y: Float) { val tx = gs.ctm.x(x, y); val ty = gs.ctm.y(x, y); px0 = min(px0, tx); px1 = max(px1, tx); py0 = min(py0, ty); py1 = max(py1, ty) }
        fun flushPath() { out.addAll(pathBuf); pathBuf.clear(); havePath = false; clip = false }
        fun pass(op: Operator) { out.addAll(operands); out.add(op); operands.clear() }
        fun op(n: String) = Operator.getOperator(n)

        /** Shows [bytes] at the current text position: returns the pieces to write, and moves the text matrix as the font would. */
        fun show(bytes: ByteArray, sink: MutableList<COSBase>): Boolean {
            val font = gs.font ?: return false
            val input = ByteArrayInputStream(bytes)
            val keep = ByteArrayOutputStream()
            var gap = 0f; var any = false
            val run = StringBuilder()
            fun flushKeep() { if (keep.size() > 0) { sink += COSString(keep.toByteArray()); keep.reset() } }
            fun flushRun() { if (run.isNotEmpty()) { ctx.report.removedText.getOrPut(ctx.page) { ArrayList() } += run.toString(); run.setLength(0) } }
            val vertical = runCatching { font.isVertical }.getOrDefault(false)
            if (vertical) ctx.report.notes += "Page ${ctx.page + 1} has vertical text, which can't be redacted safely."
            while (input.available() > 0) {
                val before = input.available()
                val code = try { font.readCode(input) } catch (_: Exception) { break }
                val len = (before - input.available()).coerceAtLeast(1)
                val startIdx = bytes.size - before
                val dx = runCatching { font.getDisplacement(code).x }.getOrDefault(0f)
                val space = len == 1 && code == 32
                val extra = gs.tc + if (space) gs.tw else 0f
                val adv = (dx * gs.fs + extra) * gs.th
                var hit = false
                if (!vertical && gs.fs != 0f) {
                    val gw = dx * gs.fs * gs.th
                    val m = tm.then(gs.ctm)
                    hit = ctx.hit(m.bounds(gw * 0.1f, gs.rise + gs.fs * 0.05f, gw * 0.9f, gs.rise + gs.fs * 0.75f))
                }
                if (hit) {
                    flushKeep(); any = true; ctx.report.glyphs++
                    gap += -(dx * 1000f + 1000f * extra / gs.fs)
                    run.append(runCatching { font.toUnicode(code) }.getOrNull() ?: "?")
                } else {
                    flushRun()
                    if (gap != 0f) { sink += COSFloat(gap); gap = 0f }
                    keep.write(bytes, startIdx, min(len, bytes.size - startIdx))
                }
                tm = Aff.move(adv, 0f).then(tm)
            }
            flushRun(); flushKeep()
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
                    val b = Box(px0 - 0.5f, py0 - 0.5f, px1 + 0.5f, py1 + 0.5f)
                    if (!clip && name != "n" && px0 <= px1 && ctx.inside(b)) { pathBuf.clear(); havePath = false; ctx.report.pathsRemoved++; changed = true; operands.clear(); continue }
                    if (!clip && name != "n" && px0 <= px1 && ctx.hit(b)) ctx.report.pathsCrossing++
                    flushPath()
                }
                pass(tok); continue
            }
            if (havePath) flushPath()
            when (name) {
                "q" -> { stack.addLast(gs.copy()); pass(tok) }
                "Q" -> { stack.removeLastOrNull()?.let { gs = it }; pass(tok) }
                "cm" -> { gs.ctm = Aff(num(operands.getOrNull(0)), num(operands.getOrNull(1)), num(operands.getOrNull(2)), num(operands.getOrNull(3)), num(operands.getOrNull(4)), num(operands.getOrNull(5))).then(gs.ctm); pass(tok) }
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
                    val b = gs.ctm.bounds(0f, 0f, 1f, 1f)
                    if (ctx.hit(b)) { ctx.report.imagesDropped++; ctx.report.notes += "An inline picture touching a marked area was removed whole."; changed = true; operands.clear() } else pass(tok)
                }
                "Do" -> {
                    val nm = operands.lastOrNull() as? COSName
                    val xo = nm?.let { runCatching { res.getXObject(it) }.getOrNull() }
                    when {
                        xo is PDImageXObject -> {
                            val b = gs.ctm.bounds(0f, 0f, 1f, 1f)
                            if (!ctx.hit(b)) pass(tok)
                            else {
                                changed = true
                                val replacement = if (ctx.inside(b)) null else clipImage(ctx, xo, gs.ctm, res)
                                if (replacement == null) { ctx.report.imagesDropped++; operands.clear() }
                                else { ctx.report.imagesClipped++; out.add(replacement); out.add(tok); operands.clear() }
                            }
                        }
                        xo is PDFormXObject && depth < MAX_DEPTH && ctx.seen[xo.cosObject] != true -> {
                            val fm = xo.matrix
                            val child = Aff(fm.getValue(0, 0), fm.getValue(0, 1), fm.getValue(1, 0), fm.getValue(1, 1), fm.getValue(2, 0), fm.getValue(2, 1)).then(gs.ctm)
                            val bb = xo.bBox
                            val reach = if (bb == null) true else ctx.hit(child.bounds(bb.lowerLeftX, bb.lowerLeftY, bb.upperRightX, bb.upperRightY))
                            if (!reach) pass(tok)
                            else {
                                ctx.seen[xo.cosObject] = true
                                val fres = xo.resources ?: res
                                noteType3(ctx, fres)
                                val inner = PDFStreamParser(xo).also { it.parse() }.tokens
                                val (nt, ch) = process(ctx, inner, fres, gs.copy().also { it.ctm = child }, depth + 1)
                                ctx.seen.remove(xo.cosObject)
                                if (!ch) pass(tok)
                                else {
                                    val cs = ctx.doc.document.createCOSStream()
                                    cs.addAll(xo.cosObject)
                                    cs.removeItem(COSName.LENGTH); cs.removeItem(COSName.FILTER); cs.removeItem(COSName.DECODE_PARMS)
                                    cs.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(nt) }
                                    out.add(res.add(PDFormXObject(cs))); out.add(tok); operands.clear(); changed = true
                                }
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

    /** A copy of the picture with every marked area painted black; the new resource name, or null when it can't be kept. */
    private fun clipImage(ctx: Ctx, img: PDImageXObject, ctm: Aff, res: PDResources): COSName? {
        if (img.isStencil) { ctx.report.notes += "A stencil picture touching a marked area was removed whole."; return null }
        val inv = ctm.inverse() ?: return null
        val src = try { img.image } catch (e: Exception) { return null } ?: return null
        val hadMask = img.cosObject.getDictionaryObject(COSName.SMASK) != null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val w = bmp.width; val h = bmp.height
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = android.graphics.Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
        val unit = ctm.bounds(0f, 0f, 1f, 1f)
        var painted = false
        for (a in ctx.areas) {
            if (!a.intersects(unit)) continue
            val us = floatArrayOf(inv.x(a.l, a.b), inv.x(a.r, a.b), inv.x(a.l, a.t), inv.x(a.r, a.t))
            val vs = floatArrayOf(inv.y(a.l, a.b), inv.y(a.r, a.b), inv.y(a.l, a.t), inv.y(a.r, a.t))
            val x0 = floor(us.min() * w).toInt().coerceIn(0, w); val x1 = ceil(us.max() * w).toInt().coerceIn(0, w)
            val y0 = floor((1f - vs.max()) * h).toInt().coerceIn(0, h); val y1 = ceil((1f - vs.min()) * h).toInt().coerceIn(0, h)
            if (x1 > x0 && y1 > y0) { canvas.drawRect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint); painted = true }
        }
        if (!painted) return null
        if (!hadMask) bmp.setHasAlpha(false)
        val jpeg = img.suffix == "jpg" || img.suffix == "jpeg"
        val fresh = if (jpeg) JPEGFactory.createFromImage(ctx.doc, bmp, 0.92f) else LosslessFactory.createFromImage(ctx.doc, bmp)
        return res.add(fresh)
    }

    // ---- annotations ---------------------------------------------------------------------------------------------------

    private fun redactAnnotations(ctx: Ctx, page: PDPage) {
        val annots = try { page.annotations } catch (_: Exception) { return }
        if (annots.isEmpty()) return
        val keep = ArrayList<PDAnnotation>(); var removedAny = false
        for (a in annots) {
            val r: PDRectangle? = a.rectangle
            val hit = r != null && ctx.hit(Box(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY))
            if (hit) { removedAny = true; ctx.report.annotationsRemoved++; if (a is PDAnnotationWidget) dropField(ctx.doc, a) } else keep += a
        }
        if (removedAny) page.annotations = keep
    }

    /** A deleted widget must not leave its value behind in the form: take its field out of the tree. */
    private fun dropField(doc: PDDocument, w: PDAnnotationWidget) {
        try {
            val form = doc.documentCatalog.acroForm ?: return
            val field = form.fieldTree.firstOrNull { f -> f is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField && f.widgets.any { it.cosObject === w.cosObject } } ?: return
            val parent = field.parent
            if (parent == null) form.fields = form.fields.filter { it.cosObject !== field.cosObject }
            else if (parent is PDNonTerminalField) parent.children = parent.children.filter { it.cosObject !== field.cosObject }
        } catch (_: Exception) { /* the widget itself is gone; a leftover field value is caught by the text check */ }
    }

    // ---- verification --------------------------------------------------------------------------------------------------

    /**
     * Extracts the text of every redacted page of the saved file again: no character may sit inside a marked area, and none of
     * the removed strings may occur more often than the page could still hold. Throws (and the caller discards the file) otherwise.
     */
    private fun verify(file: File, source: PDDocument, marks: List<RedactMark>, report: RedactionReport, before: Map<Int, String>) {
        PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { out ->
            for ((p, list) in marks.groupBy { it.page }) {
                if (p !in 0 until out.numberOfPages) continue
                val geo = PdfAnnotIo.Geo(out.getPage(p))
                val areas = list.map { m ->
                    val a = geo.user(m.rect.left, m.rect.top); val b = geo.user(m.rect.right, m.rect.bottom)
                    Box(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
                }
                val c = PdfTextSearch.Collector().also { it.setStartPage(p + 1); it.setEndPage(p + 1); it.getText(out) }
                for ((i, tp) in c.positions.withIndex()) {
                    tp ?: continue
                    val m = tp.textMatrix
                    val theta = kotlin.math.atan2(m.getValue(0, 1), m.getValue(0, 0))
                    val ux = kotlin.math.cos(theta); val uy = kotlin.math.sin(theta)
                    val h = tp.heightDir.coerceAtLeast(1f); val w = tp.widthDirAdj
                    val cx = m.translateX + ux * w / 2 - uy * 0.375f * h; val cy = m.translateY + uy * w / 2 + ux * 0.375f * h
                    if (areas.any { cx > it.l && cx < it.r && cy > it.b && cy < it.t } && !c.text()[i].isWhitespace())
                        throw ToolException("Redaction check failed on page ${p + 1}: some text under a marked area is still in the file. Nothing was saved and your original is untouched.")
                }
                val after = c.text(); val was = before[p].orEmpty()
                for (r in report.removedText[p].orEmpty().map { it.trim() }.filter { it.length >= 3 }.distinct()) {
                    val removedOcc = report.removedText[p].orEmpty().count { it.trim() == r }
                    if (count(after, r) > count(was, r) - removedOcc)
                        throw ToolException("Redaction check failed on page ${p + 1}: removed text still appears in the file. Nothing was saved and your original is untouched.")
                }
            }
        }
    }

    private fun count(hay: String, needle: String): Int { var n = 0; var i = hay.indexOf(needle); while (i >= 0) { n++; i = hay.indexOf(needle, i + 1) }; return n }
}
