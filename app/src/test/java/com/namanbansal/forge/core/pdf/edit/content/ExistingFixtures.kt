package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/** Generated pages for the Edit existing tests (US-Letter-ish 612 x 792, text in user space). */
object ExistingFixtures {
    const val LOREM = "The quick brown fox jumps over the lazy dog while the five boxing wizards jump quickly and seven big quacking zebras vex the judge"

    fun wrap(font: PDFont, text: String, size: Float, width: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (w in text.split(' ')) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (font.getStringWidth(t) / 1000f * size <= width || line.isEmpty()) line = t else { out += line; line = w }
        }
        out += line
        return out
    }

    fun PDPageContentStream.line(font: PDFont, size: Float, x: Float, y: Float, text: String, rgb: Triple<Float, Float, Float>? = null) {
        if (rgb != null) setNonStrokingColor(rgb.first, rgb.second, rgb.third) else setNonStrokingColor(0f, 0f, 0f)
        beginText(); setFont(font, size); newLineAtOffset(x, y); showText(text); endText()
    }

    fun PDPageContentStream.paragraph(font: PDFont, size: Float, x: Float, top: Float, width: Float, text: String, leading: Float = size * 1.2f,
                                      rgb: Triple<Float, Float, Float>? = null, justify: Boolean = false): Int {
        val lines = wrap(font, text, size, width)
        var y = top
        lines.forEachIndexed { i, l ->
            if (justify && i < lines.size - 1 && l.contains(' ')) {
                val words = l.split(' ')
                val total = words.sumOf { font.getStringWidth(it).toDouble() } / 1000.0 * size
                val gap = ((width - total) / (words.size - 1)).toFloat()
                var cx = x
                for (w in words) { line(font, size, cx, y, w, rgb); cx += font.getStringWidth(w) / 1000f * size + gap }
            } else line(font, size, x, y, l, rgb)
            y -= leading
        }
        return lines.size
    }

    fun doc(file: File, build: (PDDocument, PDPage, PDPageContentStream) -> Unit): File {
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            PDPageContentStream(d, p).use { cs -> build(d, p, cs) }
            d.save(file)
        }
        return file
    }

    val HELV: PDFont get() = PDType1Font.HELVETICA
    val HELV_B: PDFont get() = PDType1Font.HELVETICA_BOLD
    val TIMES: PDFont get() = PDType1Font.TIMES_ROMAN
}
