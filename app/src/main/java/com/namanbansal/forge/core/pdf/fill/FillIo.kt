package com.namanbansal.forge.core.pdf.fill

import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.namanbansal.forge.core.pdf.form.FormValues
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException

/**
 * Writes Fill & Sign edits into a PDF: form values (appearances regenerated, optionally flattened), text boxes and check marks
 * as FreeText annotations with appearance streams, signatures as Stamp annotations whose appearance holds the picture as an
 * image XObject (or drawn into the page content when flattened).
 */
object FillIo {
    const val STAMP_NAME = "ForgeSignature"

    /** Why this document can't be filled, or null when it can (permissions of an encrypted PDF). */
    fun permissionProblem(permissionBytes: Int?): String? {
        if (permissionBytes == null) return null
        val p = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(permissionBytes)
        return if (!p.canFillInForm() && !p.canModifyAnnotations()) "The author of this PDF doesn't allow filling in forms or adding to it." else null
    }

    /** Applies [state] to the file in place (via a temp file). [images] returns the PNG of a signature id. */
    fun applyInPlace(file: File, state: FillState, options: FillSaveOptions, images: (String) -> ByteArray?) {
        if (state.isEmpty) return
        val tmp = File(file.parentFile, file.name + ".fill.tmp")
        try {
            apply(file, tmp, state, options, images)
            if (!tmp.renameTo(file)) { file.writeBytes(tmp.readBytes()) }
        } finally { tmp.delete() }
    }

    fun apply(src: File, dst: File, state: FillState, options: FillSaveOptions, images: (String) -> ByteArray?) {
        try {
            PDDocument.load(src, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
                if (state.overrides.isNotEmpty()) setValues(doc, state.overrides)
                if (options.flattenForm) doc.documentCatalog.acroForm?.let { runCatching { it.flatten() }.onFailure { e -> throw ToolException("Couldn't flatten the form: ${e.message ?: "unknown error"}.", e) } }
                val byPage = state.items.groupBy { it.page }
                for ((p, list) in byPage) {
                    if (p !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(p)
                    val geo = PdfAnnotIo.Geo(page)
                    val annots = page.annotations
                    for (item in list) {
                        when (item.kind) {
                            ItemKind.SIGNATURE -> {
                                val png = images(item.sigId) ?: throw ToolException("A signature you placed is no longer in your library.")
                                val img = PDImageXObject.createFromByteArray(doc, png, "signature")
                                if (options.flattenSignatures) flattenImage(doc, page, geo, item, img) else annots.add(stamp(doc, geo, item, img))
                            }
                            else -> annots.add(freeText(doc, geo, item))
                        }
                    }
                    page.annotations = annots
                }
                dst.outputStream().use { doc.save(it) }
            }
        } catch (e: ToolException) { throw e
        } catch (e: IOException) { throw ToolException("Couldn't write your changes into this PDF: ${e.message ?: "unknown error"}.", e) }
    }

    // ---- form values -----------------------------------------------------------------------------------------------

    private fun setValues(doc: PDDocument, values: Map<String, String>) {
        val form = doc.documentCatalog.acroForm ?: throw ToolException("This PDF has no form to fill.")
        // make sure the default appearance font exists, then let PDFBox draw each field itself
        if (form.defaultResources == null) form.defaultResources = PDResources()
        val dr = form.defaultResources
        if (dr.getFont(COSName.getPDFName("Helv")) == null) runCatching { dr.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
        form.setNeedAppearances(false)
        for ((name, value) in values) {
            val f = form.getField(name) ?: continue
            if (f.isReadOnly) continue
            try {
                when (f) {
                    is PDCheckBox -> if (value == FormValues.OFF) f.unCheck() else f.check()
                    is PDRadioButton -> if (value == FormValues.OFF) f.setValue("Off") else f.setValue(value)
                    is PDListBox -> value.split('\n').filter { it.isNotEmpty() }.let { v -> if (v.size == 1) f.setValue(v[0]) else f.setValue(v) }
                    is PDComboBox -> f.setValue(value)
                    is PDTextField -> f.setValue(if (f.maxLen > 0) value.take(f.maxLen) else value)
                    else -> Unit
                }
            } catch (e: Exception) {
                // an odd field must not lose the rest of the form: leave it as it was
            }
        }
    }

    // ---- geometry --------------------------------------------------------------------------------------------------

    /** The box of [r] (page fractions) in user space. */
    internal fun userRect(geo: PdfAnnotIo.Geo, r: NormRect): PDRectangle {
        val a = geo.user(r.left, r.top); val b = geo.user(r.right, r.bottom)
        return PDRectangle(minOf(a.first, b.first), minOf(a.second, b.second), kotlin.math.abs(a.first - b.first), kotlin.math.abs(a.second - b.second))
    }

    /**
     * Makes everything drawn by [body] in the *displayed* frame ((0,0) bottom-left of the displayed box, [dispW] x [dispH]) land
     * upright on the displayed page whatever the page rotation. The current origin must be the lower-left of the user-space box.
     */
    internal fun upright(cs: PDPageContentStream, boxW: Float, boxH: Float, rot: Int, body: (dispW: Float, dispH: Float) -> Unit) {
        val m = when (rot) {
            90 -> Matrix(0f, 1f, -1f, 0f, boxW, 0f)
            180 -> Matrix(-1f, 0f, 0f, -1f, boxW, boxH)
            270 -> Matrix(0f, -1f, 1f, 0f, 0f, boxH)
            else -> Matrix()
        }
        cs.saveGraphicsState()
        cs.transform(m)
        if (rot == 90 || rot == 270) body(boxH, boxW) else body(boxW, boxH)
        cs.restoreGraphicsState()
    }

    /** Draws [img] in a displayed frame of [dw] x [dh], turned [turns] quarter turns clockwise (the frame already has the turned size). */
    internal fun drawTurned(cs: PDPageContentStream, img: PDImageXObject, dw: Float, dh: Float, turns: Int) {
        val t = ((turns % 4) + 4) % 4
        val iw = if (t % 2 == 0) dw else dh; val ih = if (t % 2 == 0) dh else dw
        cs.saveGraphicsState()
        cs.transform(Matrix.getTranslateInstance(dw / 2, dh / 2))
        if (t != 0) cs.transform(Matrix.getRotateInstance(-Math.PI / 2 * t, 0f, 0f))
        cs.drawImage(img, -iw / 2, -ih / 2, iw, ih)
        cs.restoreGraphicsState()
    }

    // ---- signatures --------------------------------------------------------------------------------------------------

    private fun stamp(doc: PDDocument, geo: PdfAnnotIo.Geo, item: FillItem, img: PDImageXObject): PDAnnotationRubberStamp {
        val rect = userRect(geo, item.rect)
        val ann = PDAnnotationRubberStamp()
        ann.rectangle = rect
        ann.name = STAMP_NAME
        ann.contents = "Signature"
        ann.isPrinted = true
        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(rect.width, rect.height)
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs -> upright(cs, rect.width, rect.height, geo.rot) { dw, dh -> drawTurned(cs, img, dw, dh, item.turns) } }
        ann.appearance = PDAppearanceDictionary().also { it.setNormalAppearance(ap) }
        return ann
    }

    private fun flattenImage(doc: PDDocument, page: PDPage, geo: PdfAnnotIo.Geo, item: FillItem, img: PDImageXObject) {
        val rect = userRect(geo, item.rect)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(Matrix.getTranslateInstance(rect.lowerLeftX, rect.lowerLeftY))
            upright(cs, rect.width, rect.height, geo.rot) { dw, dh -> drawTurned(cs, img, dw, dh, item.turns) }
        }
    }

    // ---- text boxes and quick marks -----------------------------------------------------------------------------

    /** Characters Helvetica (WinAnsi) can show; anything else becomes "?" so a save never fails on an odd character. */
    fun printable(s: String): String = s.map { ch -> if (ch == '\n' || (ch.code in 32..126) || (ch.code in 160..255)) ch else '?' }.joinToString("")

    fun wrap(text: String, widthPt: Float, sizePt: Float): List<String> {
        val font = PDType1Font.HELVETICA
        fun w(s: String) = font.getStringWidth(s) / 1000f * sizePt
        val out = ArrayList<String>()
        for (para in printable(text).split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (w(tryLine) <= widthPt || line.isEmpty()) line = tryLine else { out += line; line = word }
                while (w(line) > widthPt && line.length > 1) { // a single word wider than the box: break it
                    var k = line.length - 1
                    while (k > 1 && w(line.substring(0, k)) > widthPt) k--
                    out += line.substring(0, k); line = line.substring(k)
                }
            }
            out += line
        }
        return out
    }

    private fun freeText(doc: PDDocument, geo: PdfAnnotIo.Geo, item: FillItem): PDAnnotationMarkup {
        val rect = userRect(geo, item.rect)
        val ann = PDAnnotationMarkup()
        ann.cosObject.setName(COSName.SUBTYPE, "FreeText")
        ann.rectangle = rect
        val sizePt = FillPalette.sizes[item.size.coerceIn(0, 2)] * geo.dispW
        val r = ((item.color shr 16) and 0xFF) / 255f; val g = ((item.color shr 8) and 0xFF) / 255f; val b = (item.color and 0xFF) / 255f
        val isMark = item.kind == ItemKind.CHECK || item.kind == ItemKind.CROSS
        ann.contents = when (item.kind) { ItemKind.CHECK -> "✓"; ItemKind.CROSS -> "✗"; else -> item.text }
        ann.cosObject.setString(COSName.getPDFName("DA"), "/Helv ${"%.2f".format(java.util.Locale.US, sizePt)} Tf $r $g $b rg")
        ann.cosObject.setInt(COSName.getPDFName("Q"), 0)
        ann.cosObject.setItem(COSName.BORDER, COSArray().also { a -> repeat(3) { a.add(com.tom_roush.pdfbox.cos.COSInteger.ZERO) } })
        ann.cosObject.setString(COSName.getPDFName("Subj"), if (isMark) "Mark" else "Text box")
        ann.isPrinted = true
        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(rect.width, rect.height)
        val res = PDResources()
        res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        ap.resources = res
        PDPageContentStream(doc, ap, ap.stream.createOutputStream(COSName.FLATE_DECODE)).use { cs ->
            upright(cs, rect.width, rect.height, geo.rot) { dw, dh ->
                cs.setNonStrokingColor(r, g, b); cs.setStrokingColor(r, g, b)
                when (item.kind) {
                    ItemKind.CHECK -> {
                        cs.setLineWidth(dh * 0.11f); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                        cs.moveTo(dw * 0.14f, dh * 0.52f); cs.lineTo(dw * 0.40f, dh * 0.18f); cs.lineTo(dw * 0.86f, dh * 0.84f); cs.stroke()
                    }
                    ItemKind.CROSS -> {
                        cs.setLineWidth(dh * 0.11f); cs.setLineCapStyle(1)
                        cs.moveTo(dw * 0.16f, dh * 0.16f); cs.lineTo(dw * 0.84f, dh * 0.84f); cs.stroke()
                        cs.moveTo(dw * 0.16f, dh * 0.84f); cs.lineTo(dw * 0.84f, dh * 0.16f); cs.stroke()
                    }
                    else -> {
                        val pad = sizePt * 0.25f
                        val lines = wrap(item.text, dw - 2 * pad, sizePt)
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, sizePt)
                        cs.newLineAtOffset(pad, dh - pad - sizePt * 0.8f)
                        cs.setLeading(sizePt * 1.2f)
                        for ((i, l) in lines.withIndex()) { if (i > 0) cs.newLine(); cs.showText(l) }
                        cs.endText()
                    }
                }
            }
        }
        ann.appearance = PDAppearanceDictionary().also { it.setNormalAppearance(ap) }
        return ann
    }

    // ---- reading back (tests, and "is this already signed") ---------------------------------------------------------------

    /** Free text boxes of the document: page, contents, user-space rect and whether they carry an appearance. */
    data class FreeTextInfo(val page: Int, val text: String, val rect: PDRectangle, val hasAppearance: Boolean)

    fun readFreeText(file: File): List<FreeTextInfo> = PDDocument.load(file).use { doc ->
        (0 until doc.numberOfPages).flatMap { p ->
            doc.getPage(p).annotations.filter { it.subtype == "FreeText" }.map { FreeTextInfo(p, it.contents.orEmpty(), it.rectangle, it.normalAppearanceStream != null) }
        }
    }

    @Suppress("unused") private fun dict(d: COSDictionary) = d
}
