package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.namanbansal.forge.core.pdf.fill.FillIo
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Writes Edit-mode objects (text boxes, pictures, shapes, whiteouts) into the page content stream: appended to the page with
 * `resetContext` so the graphics state of the original content can't leak in. Text uses the standard-14 fonts; pictures are
 * image XObjects (JPEG passes through untouched, PNG keeps its alpha as a soft mask).
 */
object EditIo {
    fun fontFor(f: EditFont, bold: Boolean): PDFont = when (f) {
        EditFont.SANS -> if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        EditFont.SERIF -> if (bold) PDType1Font.TIMES_BOLD else PDType1Font.TIMES_ROMAN
        EditFont.MONO -> if (bold) PDType1Font.COURIER_BOLD else PDType1Font.COURIER
    }

    /** Writes [state]'s objects (not the redaction marks) into the file in place. [images] returns the bytes of an image id. */
    fun applyObjectsInPlace(file: File, state: EditState, images: (String) -> ByteArray?) {
        if (state.items.isEmpty()) return
        val tmp = File(file.parentFile, file.name + ".edit.tmp")
        try {
            PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
                writeObjects(doc, state.items, images)
                tmp.outputStream().use { doc.save(it) }
            }
            if (!tmp.renameTo(file)) file.writeBytes(tmp.readBytes())
        } catch (e: ToolException) { throw e
        } catch (e: IOException) { throw ToolException("Couldn't write your changes into this PDF: ${e.message ?: "unknown error"}.", e)
        } finally { tmp.delete() }
    }

    fun apply(src: File, dst: File, state: EditState, images: (String) -> ByteArray?) {
        src.copyTo(dst, overwrite = true)
        applyObjectsInPlace(dst, state, images)
    }

    fun writeObjects(doc: PDDocument, items: List<EditObject>, images: (String) -> ByteArray?) {
        val cache = HashMap<String, PDImageXObject>()
        for ((p, list) in items.groupBy { it.page }) {
            if (p !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(p)
            val geo = PdfAnnotIo.Geo(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for (o in list) {
                    val rect = FillIo.userRect(geo, o.rect)
                    cs.saveGraphicsState()
                    cs.transform(Matrix.getTranslateInstance(rect.lowerLeftX, rect.lowerLeftY))
                    FillIo.upright(cs, rect.width, rect.height, geo.rot) { dw, dh -> draw(doc, cs, o, dw, dh, geo.dispW, cache, images) }
                    cs.restoreGraphicsState()
                }
            }
        }
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun draw(doc: PDDocument, cs: PDPageContentStream, o: EditObject, dw: Float, dh: Float, pageW: Float, cache: HashMap<String, PDImageXObject>, images: (String) -> ByteArray?) {
        when (o.kind) {
            EditKind.TEXT -> drawText(cs, o, dw, dh, pageW)
            EditKind.WHITEOUT -> {
                val (r, g, b) = rgb(o.fillColor).let { Triple(it[0], it[1], it[2]) }
                cs.setNonStrokingColor(r, g, b); cs.addRect(0f, 0f, dw, dh); cs.fill()
                if (o.text.isNotBlank()) drawText(cs, o, dw, dh, pageW)
            }
            EditKind.IMAGE -> {
                val img = cache.getOrPut(o.imageId) {
                    val bytes = images(o.imageId) ?: throw ToolException("A picture you placed is no longer available. Remove it and add it again.")
                    PDImageXObject.createFromByteArray(doc, bytes, o.imageId)
                }
                FillIo.drawTurned(cs, img, dw, dh, o.turns)
            }
            EditKind.RECT, EditKind.ELLIPSE -> {
                val sw = o.strokeWidth.coerceAtLeast(0.25f); val inset = sw / 2
                val x = inset; val y = inset; val w = max(0.5f, dw - sw); val h = max(0.5f, dh - sw)
                if (o.filled) cs.setGraphicsStateParameters(PDExtendedGraphicsState().also { it.nonStrokingAlphaConstant = o.fillOpacity.coerceIn(0f, 1f) })
                path(cs, o.kind, x, y, w, h)
                paintShape(cs, o, sw)
            }
            EditKind.LINE, EditKind.ARROW -> {
                val sw = o.strokeWidth.coerceAtLeast(0.25f)
                val (x1, y1, x2, y2) = (if (o.flip) listOf(0f, 0f, dw, dh) else listOf(0f, dh, dw, 0f)).let { if (o.reverse) listOf(it[2], it[3], it[0], it[1]) else it }
                val c = rgb(o.color)
                cs.setStrokingColor(c[0], c[1], c[2]); cs.setNonStrokingColor(c[0], c[1], c[2])
                cs.setLineWidth(sw); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                var ex = x2; var ey = y2
                val head = max(8f, sw * 4f)
                val ang = atan2(y2 - y1, x2 - x1)
                if (o.kind == EditKind.ARROW) { ex = x2 - cos(ang) * head * 0.6f; ey = y2 - sin(ang) * head * 0.6f }
                cs.moveTo(x1, y1); cs.lineTo(ex, ey); cs.stroke()
                if (o.kind == EditKind.ARROW) {
                    val a = 0.45f
                    cs.moveTo(x2, y2)
                    cs.lineTo(x2 - head * cos(ang - a), y2 - head * sin(ang - a))
                    cs.lineTo(x2 - head * cos(ang + a), y2 - head * sin(ang + a))
                    cs.closePath(); cs.fill()
                }
            }
        }
    }

    private fun path(cs: PDPageContentStream, kind: EditKind, x: Float, y: Float, w: Float, h: Float) {
        if (kind == EditKind.RECT) { cs.addRect(x, y, w, h); return }
        val k = 0.5522847f; val rx = w / 2; val ry = h / 2; val cx = x + rx; val cy = y + ry
        cs.moveTo(cx + rx, cy)
        cs.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
        cs.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
        cs.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
        cs.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
        cs.closePath()
    }

    /** Fills (with opacity) and/or strokes the path just built. */
    private fun paintShape(cs: PDPageContentStream, o: EditObject, sw: Float) {
        val s = rgb(o.color); val f = rgb(o.fillColor)
        cs.setLineWidth(sw); cs.setLineJoinStyle(1)
        cs.setStrokingColor(s[0], s[1], s[2])
        if (o.filled) {
            cs.setNonStrokingColor(f[0], f[1], f[2])
            cs.fillAndStroke()
        } else cs.stroke()
    }

    private fun drawText(cs: PDPageContentStream, o: EditObject, dw: Float, dh: Float, pageW: Float) {
        val font = fontFor(o.font, o.bold)
        val size = (o.sizeFrac * pageW).coerceAtLeast(2f)
        val pad = size * 0.25f
        val lines = wrap(font, o.text, dw - 2 * pad, size)
        val c = rgb(o.color)
        cs.setNonStrokingColor(c[0], c[1], c[2])
        var y = dh - pad - size * 0.8f
        for (l in lines) {
            if (l.isNotEmpty()) {
                val w = font.getStringWidth(l) / 1000f * size
                val x = when (o.align) { 1 -> (dw - w) / 2; 2 -> dw - pad - w; else -> pad }
                cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(l); cs.endText()
            }
            y -= size * 1.2f
        }
    }

    /** Characters the standard fonts (WinAnsi) can show; anything else becomes "?" so a save never fails on an odd character. */
    fun printable(s: String): String = FillIo.printable(s)

    fun wrap(font: PDFont, text: String, widthPt: Float, sizePt: Float): List<String> {
        fun w(s: String) = font.getStringWidth(s) / 1000f * sizePt
        val out = ArrayList<String>()
        for (para in printable(text).split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (w(tryLine) <= widthPt || line.isEmpty()) line = tryLine else { out += line; line = word }
                while (w(line) > widthPt && line.length > 1) {
                    var k = line.length - 1
                    while (k > 1 && w(line.substring(0, k)) > widthPt) k--
                    out += line.substring(0, k); line = line.substring(k)
                }
            }
            out += line
        }
        return out
    }

    /** Pure helper: font size (fraction of page width) that makes [text] fit a box of [boxW] x [boxH] points on a page [pageW] wide, at most [maxFrac]. */
    fun fitSizeFrac(text: String, font: EditFont, bold: Boolean, boxW: Float, boxH: Float, pageW: Float, maxFrac: Float = EditStyle.MAX_SIZE): Float {
        val f = fontFor(font, bold)
        var lo = EditStyle.MIN_SIZE; var hi = maxFrac
        fun fits(frac: Float): Boolean {
            val size = frac * pageW; val pad = size * 0.25f
            val lines = wrap(f, text.ifEmpty { " " }, boxW - 2 * pad, size)
            return lines.size * size * 1.2f + pad * 2 <= boxH + 0.01f
        }
        if (!fits(lo)) return lo
        repeat(14) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }
        return lo
    }

}
