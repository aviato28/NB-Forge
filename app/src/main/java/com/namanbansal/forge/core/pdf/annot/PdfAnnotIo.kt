package com.namanbansal.forge.core.pdf.annot

import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.core.pdf.viewer.LinkGeometry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.handlers.PDInkAppearanceHandler
import java.io.File
import java.io.IOException

/**
 * Reads and writes the standard PDF annotations the annotate mode edits: Highlight / Underline / StrikeOut (text markup with
 * QuadPoints), Ink (InkList, with /CA for the highlighter pen) and Text (sticky notes). Coordinates are converted between PDF
 * user space (lower-left origin, crop box, page rotation) and page fractions with [LinkGeometry]'s mapping.
 * Every written annotation gets an appearance stream so it shows in viewers that do not draw annotations from their dictionary.
 */
object PdfAnnotIo {
    private const val INK = "Ink"
    private const val TEXT = "Text"

    private fun supported(subtype: String?) = when (subtype) {
        PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT, INK, TEXT -> true
        else -> false
    }

    private class Geo(page: PDPage) {
        val box: PDRectangle = page.cropBox
        val rot: Int = ((page.rotation % 360) + 360) % 360
        /** Width of the page as displayed (swapped on sideways pages), in points. */
        val dispW: Float = if (rot == 90 || rot == 270) box.height else box.width
        val dispH: Float = if (rot == 90 || rot == 270) box.width else box.height

        fun norm(llx: Float, lly: Float, urx: Float, ury: Float): NormRect =
            LinkGeometry.normalize(llx, lly, urx, ury, box.lowerLeftX, box.lowerLeftY, box.width, box.height, rot)

        fun normPt(x: Float, y: Float): NormPt = norm(x, y, x, y).let { NormPt(it.left, it.top) }

        /** Page fractions -> user space. */
        fun user(u: Float, v: Float): Pair<Float, Float> {
            val fx: Float; val fy: Float
            when (rot) {
                90 -> { fy = 1f - u; fx = v }
                180 -> { fx = 1f - u; fy = 1f - v }
                270 -> { fy = u; fx = 1f - v }
                else -> { fx = u; fy = v }
            }
            return (box.lowerLeftX + fx * box.width) to (box.lowerLeftY + (1f - fy) * box.height)
        }
    }

    private fun annotsArray(page: PDPage): COSArray? = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray

    private fun subtype(d: COSDictionary): String? = d.getNameAsString(COSName.SUBTYPE)

    private fun colorOf(a: PDAnnotation, fallback: Int): Int = try {
        val c = a.color
        if (c == null || c.components.isEmpty()) fallback else (0xFF000000.toInt() or (c.toRGB() and 0xFFFFFF))
    } catch (_: Exception) { fallback }

    /** Every supported annotation of [doc], with the position it has in its page so a save can find it again. */
    fun read(doc: PDDocument): List<Annot> {
        val out = ArrayList<Annot>()
        for (p in 0 until doc.numberOfPages) {
            val page = try { doc.getPage(p) } catch (_: Exception) { continue }
            val arr = annotsArray(page) ?: continue
            val geo = Geo(page)
            for (i in 0 until arr.size()) {
                val d = arr.getObject(i) as? COSDictionary ?: continue
                if (!supported(subtype(d))) continue
                try { readOne(d, p, i, geo)?.let { out += it } } catch (_: Exception) { /* a damaged annotation is left alone */ }
            }
        }
        return out
    }

    private fun readOne(d: COSDictionary, page: Int, index: Int, geo: Geo): Annot? {
        val flags = d.getInt(COSName.F, 0)
        if (flags and (2 or 32) != 0) return null // hidden or no-view
        val id = "e${page}_$index"
        val ref = ExistingRef(page, index)
        val a = PDAnnotationMarkup(d)
        val opacity = a.constantOpacity.coerceIn(0f, 1f)
        return when (val sub = subtype(d)) {
            PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> {
                val q = PDAnnotationTextMarkup(d).quadPoints ?: return null
                val quads = (0 until q.size / 8).map { k ->
                    val xs = (0..3).map { q[k * 8 + it * 2] }; val ys = (0..3).map { q[k * 8 + it * 2 + 1] }
                    geo.norm(xs.min(), ys.min(), xs.max(), ys.max())
                }
                if (quads.isEmpty()) return null
                val type = when (sub) { PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT -> AnnotType.HIGHLIGHT; PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> AnnotType.UNDERLINE; else -> AnnotType.STRIKEOUT }
                Annot(id, page, type, colorOf(a, if (type == AnnotType.HIGHLIGHT) AnnotPalette.YELLOW else AnnotPalette.RED), opacity, quads = quads, text = a.contents.orEmpty(), ref = ref)
            }
            INK -> {
                val list = a.inkList ?: return null
                val strokes = list.map { s -> (0 until s.size / 2).map { geo.normPt(s[it * 2], s[it * 2 + 1]) } }.filter { it.isNotEmpty() }
                if (strokes.isEmpty()) return null
                val w = (a.borderStyle?.width ?: 1f).coerceAtLeast(0.1f) / geo.dispW
                Annot(id, page, AnnotType.INK, colorOf(a, AnnotPalette.BLUE), opacity, strokes = strokes, width = w, text = a.contents.orEmpty(), ref = ref)
            }
            TEXT -> {
                if (d.containsKey(COSName.getPDFName("IRT"))) return null // a reply, not a note
                val r = a.rectangle ?: return null
                val tl = geo.norm(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
                Annot(id, page, AnnotType.NOTE, colorOf(a, AnnotPalette.YELLOW), 1f, anchor = NormPt(tl.left, tl.top), text = a.contents.orEmpty(), ref = ref)
            }
            else -> null
        }
    }

    /**
     * Makes a copy of [src] without the supported annotations (they are drawn by the app's own overlay instead, so edits and
     * undo are exact). Returns what was removed, or null when there was nothing to remove and [dst] was not written.
     */
    fun strip(src: File, dst: File): List<Annot>? {
        PDDocument.load(src, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
            val found = read(doc)
            if (found.isEmpty()) return null
            for ((p, list) in found.groupBy { it.page }) {
                val arr = annotsArray(doc.getPage(p)) ?: continue
                list.map { it.ref!!.index }.sortedDescending().forEach { arr.remove(it) }
            }
            dst.outputStream().use { doc.save(it) }
            return found
        }
    }

    /**
     * Writes [current] into a copy of [src]: annotations in [baseline] that are gone are removed, existing ones whose colour or text
     * changed are updated in place (appearance rebuilt), new ones are added. Untouched annotations are not modified at all.
     * [protection] re-applies the password of an encrypted original.
     */
    fun write(src: File, dst: File, baseline: List<Annot>, current: List<Annot>, protection: PdfProtection? = null) {
        try {
            PDDocument.load(src, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
                val base = baseline.associateBy { it.id }
                val keep = current.map { it.id }.toSet()
                val removed = baseline.filter { it.id !in keep }
                val changed = current.filter { it.ref != null && base[it.id]?.let { b -> b != it } == true }
                val added = current.filter { it.ref == null }

                for (a in changed) {
                    val ref = a.ref ?: continue
                    val d = annotsArray(doc.getPage(ref.page))?.getObject(ref.index) as? COSDictionary ?: continue
                    updateInPlace(doc, d, a)
                }
                for ((p, list) in removed.groupBy { it.page }) {
                    val arr = annotsArray(doc.getPage(p)) ?: continue
                    val popups = ArrayList<COSBase>()
                    for (a in list) (arr.getObject(a.ref!!.index) as? COSDictionary)?.getDictionaryObject(COSName.getPDFName("Popup"))?.let { popups += it }
                    list.map { it.ref!!.index }.sortedDescending().forEach { arr.remove(it) }
                    for (pop in popups) { var i = 0; while (i < arr.size()) { if (arr.getObject(i) === pop) arr.remove(i) else i++ } }
                }
                for ((p, list) in added.groupBy { it.page }) {
                    val page = doc.getPage(p)
                    val geo = Geo(page)
                    val annots = page.annotations
                    for (a in list) build(doc, a, geo)?.let { annots.add(it) }
                    page.annotations = annots
                }
                protection?.applyTo(doc)
                dst.outputStream().use { doc.save(it) }
            }
        } catch (e: ToolException) { throw e
        } catch (e: IOException) { throw ToolException("Couldn't write the annotations into this PDF: ${e.message ?: "unknown error"}.", e)
        }
    }

    private fun rgb(color: Int) = PDColor(floatArrayOf(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f), PDDeviceRGB.INSTANCE)

    private fun updateInPlace(doc: PDDocument, d: COSDictionary, a: Annot) {
        val m = PDAnnotationMarkup(d)
        m.color = rgb(a.color)
        m.contents = a.text.ifEmpty { null }
        d.removeItem(COSName.AP)
        construct(doc, PDAnnotation.createAnnotation(d))
    }

    /** Four corners of one text-markup box in user space, in the order QuadPoints are written: top-left, top-right, bottom-left, bottom-right. */
    private class Quad(val p: List<Pair<Float, Float>>)

    private fun quadsOf(qp: FloatArray, raw: Boolean): List<Quad> = (0 until qp.size / 8).map { k ->
        if (raw) return@map Quad((0..3).map { qp[k * 8 + it * 2] to qp[k * 8 + it * 2 + 1] })
        val xs = (0..3).map { qp[k * 8 + it * 2] }; val ys = (0..3).map { qp[k * 8 + it * 2 + 1] }
        // other apps order the corners differently: rebuild an axis-aligned box in user space
        Quad(listOf(xs.min() to ys.max(), xs.max() to ys.max(), xs.min() to ys.min(), xs.max() to ys.min()))
    }

    /**
     * Our own appearance stream for Highlight / Underline / StrikeOut: a form with the annotation's rectangle as its box, drawn in
     * page coordinates. (PDFBox's generated one pads and shrinks the boxes, which shows in other viewers.) Highlights use the
     * Multiply blend so the text stays readable; opacity comes from /CA.
     */
    private fun markupAppearance(doc: PDDocument, ann: PDAnnotation, sub: String, color: Int, opacity: Float, quads: List<Quad>, rect: PDRectangle) {
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = com.tom_roush.pdfbox.pdmodel.PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant = opacity; gs.nonStrokingAlphaConstant = opacity
            if (sub == PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT) gs.blendMode = BlendMode.MULTIPLY
            cs.setGraphicsStateParameters(gs)
            val r = ((color shr 16) and 0xFF) / 255f; val g = ((color shr 8) and 0xFF) / 255f; val b = (color and 0xFF) / 255f
            cs.setNonStrokingColor(r, g, b); cs.setStrokingColor(r, g, b)
            for (q in quads) {
                val (tl, tr, bl, br) = q.p
                val h = kotlin.math.hypot(tl.first - bl.first, tl.second - bl.second).coerceAtLeast(0.01f)
                val ux = (tl.first - bl.first) / h; val uy = (tl.second - bl.second) / h // "up" in the box
                when (sub) {
                    PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT -> {
                        cs.moveTo(tl.first, tl.second); cs.lineTo(tr.first, tr.second); cs.lineTo(br.first, br.second); cs.lineTo(bl.first, bl.second); cs.closePath(); cs.fill()
                    }
                    PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> {
                        val lw = (h * 0.09f).coerceAtLeast(0.8f); val o = lw / 2 + h * 0.02f
                        cs.setLineWidth(lw)
                        cs.moveTo(bl.first + ux * o, bl.second + uy * o); cs.lineTo(br.first + ux * o, br.second + uy * o); cs.stroke()
                    }
                    else -> {
                        val lw = (h * 0.08f).coerceAtLeast(0.8f)
                        cs.setLineWidth(lw)
                        cs.moveTo((tl.first + bl.first) / 2, (tl.second + bl.second) / 2); cs.lineTo((tr.first + br.first) / 2, (tr.second + br.second) / 2); cs.stroke()
                    }
                }
            }
        }
        val dict = PDAppearanceDictionary()
        dict.setNormalAppearance(ap)
        ann.appearance = dict
    }

    /** [ownQuads]: the quads were written by [build] in displayed order, so their corners can be used as they are. */
    private fun construct(doc: PDDocument, m: PDAnnotation, ownQuads: Boolean = false) {
        try {
            val sub = subtype(m.cosObject)
            if (m is PDAnnotationTextMarkup && sub != null) {
                val qp = m.quadPoints ?: return
                markupAppearance(doc, m, sub, colorOf(m, AnnotPalette.YELLOW), m.constantOpacity, quadsOf(qp, ownQuads), m.rectangle ?: return)
            } else if (sub == INK) PDInkAppearanceHandler(m, doc).generateAppearanceStreams() else m.constructAppearances(doc)
        } catch (_: Exception) { /* the annotation is still valid, viewers that draw from its dictionary show it */ }
    }

    private fun build(doc: PDDocument, a: Annot, geo: Geo): PDAnnotation? {
        val rect: PDRectangle
        val ann: PDAnnotationMarkup
        when (a.type) {
            AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKEOUT -> {
                if (a.quads.isEmpty()) return null
                val sub = when (a.type) { AnnotType.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT; AnnotType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE; else -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT }
                val tm = PDAnnotationTextMarkup(sub)
                val qp = FloatArray(a.quads.size * 8)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                a.quads.forEachIndexed { k, q ->
                    val tl = geo.user(q.left, q.top); val tr = geo.user(q.right, q.top); val bl = geo.user(q.left, q.bottom); val br = geo.user(q.right, q.bottom)
                    // top-left, top-right, bottom-left, bottom-right in user space: the order every major viewer reads.
                    val pts = listOf(tl, tr, bl, br)
                    pts.forEachIndexed { j, p -> qp[k * 8 + j * 2] = p.first; qp[k * 8 + j * 2 + 1] = p.second; minX = minOf(minX, p.first); maxX = maxOf(maxX, p.first); minY = minOf(minY, p.second); maxY = maxOf(maxY, p.second) }
                }
                tm.quadPoints = qp
                rect = PDRectangle(minX, minY, maxX - minX, maxY - minY)
                ann = tm
            }
            AnnotType.INK -> {
                val m = PDAnnotationMarkup()
                m.cosObject.setName(COSName.SUBTYPE, INK)
                val strokes = a.strokes.filter { it.isNotEmpty() }
                if (strokes.isEmpty()) return null
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                val list = Array(strokes.size) { s ->
                    val pts = StrokeMath.smoothed(strokes[s])
                    FloatArray(pts.size * 2).also { f ->
                        pts.forEachIndexed { i, pt ->
                            val (x, y) = geo.user(pt.x, pt.y)
                            f[i * 2] = x; f[i * 2 + 1] = y
                            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                        }
                    }
                }
                m.inkList = list
                val w = (a.width * geo.dispW).coerceAtLeast(0.5f)
                m.borderStyle = PDBorderStyleDictionary().also { it.width = w; it.style = PDBorderStyleDictionary.STYLE_SOLID }
                val pad = w / 2 + 1f
                rect = PDRectangle(minX - pad, minY - pad, maxX - minX + 2 * pad, maxY - minY + 2 * pad)
                ann = m
            }
            AnnotType.NOTE -> {
                val at = a.anchor ?: return null
                val size = AnnotPalette.NOTE_SIZE * geo.dispW
                val tl = geo.user(at.x, at.y)
                val br = geo.user(at.x + AnnotPalette.NOTE_SIZE, at.y + AnnotPalette.NOTE_SIZE * geo.dispW / geo.dispH)
                val x0 = minOf(tl.first, br.first); val y0 = minOf(tl.second, br.second)
                rect = PDRectangle(x0, y0, size, size)
                ann = PDAnnotationText().also { it.name = PDAnnotationText.NAME_NOTE }
            }
        }
        ann.rectangle = rect
        ann.color = rgb(a.color)
        ann.constantOpacity = a.opacity.coerceIn(0f, 1f)
        if (a.text.isNotEmpty()) ann.contents = a.text
        ann.isPrinted = true
        ann.subject = when (a.type) { AnnotType.HIGHLIGHT -> "Highlight"; AnnotType.UNDERLINE -> "Underline"; AnnotType.STRIKEOUT -> "Strikeout"; AnnotType.INK -> if (a.isHighlighterInk) "Highlighter" else "Pen"; AnnotType.NOTE -> "Note" }
        construct(doc, ann, ownQuads = true)
        if (a.type == AnnotType.NOTE) ann.rectangle = rect // the icon handler resizes it; keep the size the user sees
        return ann
    }
}
