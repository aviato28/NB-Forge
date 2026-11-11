package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Generated stand-ins for the PDFs that bank, invoice and email apps produce, one per Round 2F case (there are no owner samples
 * yet): a TrueType subset without ToUnicode, a CID font without ToUnicode (recoverable and not), a paragraph with a bold word and
 * coloured links, scanner-skewed text, FreeText and outline-only text.
 */
object CoverageFixtures {
    val NOTO = File("src/main/assets/fonts/NotoSans-Regular.ttf")
    val BLUE = Triple(0.05f, 0.25f, 0.85f)

    /**
 * Writes [lines] with an embedded Noto CID font (Identity-H), then removes every /ToUnicode, like many invoice generators do.
 * A [subset] font keeps no cmap or glyph names (PDFBox's subsetter), so nothing can say what its letters are; the whole font keeps its cmap.
 */
    fun cidNoToUnicode(file: File, lines: List<String>, subset: Boolean = false): File {
        PDDocument().use { d ->
            val page = PDPage(PDRectangle.LETTER); d.addPage(page)
            val font = PDType0Font.load(d, NOTO.inputStream(), subset)
            PDPageContentStream(d, page).use { cs ->
                lines.forEachIndexed { i, t -> cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(72f, 700f - i * 14.4f); cs.showText(t); cs.endText() }
            }
            d.save(file)
        }
        PDDocument.load(file).use { d ->
            val res = d.getPage(0).resources
            for (n in res.fontNames) res.getFont(n).cosObject.removeItem(COSName.TO_UNICODE)
            d.save(file)
        }
        return file
    }

    /** A simple (single byte) embedded TrueType font with no Encoding and no ToUnicode, flagged symbolic. */
    fun trueTypeNoEncoding(file: File, lines: List<String>): File {
        PDDocument().use { d ->
            val page = PDPage(PDRectangle.LETTER); d.addPage(page)
            val font = com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont.load(d, NOTO.inputStream(), com.tom_roush.pdfbox.pdmodel.font.encoding.WinAnsiEncoding.INSTANCE)
            PDPageContentStream(d, page).use { cs ->
                lines.forEachIndexed { i, t -> cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(72f, 700f - i * 14.4f); cs.showText(t); cs.endText() }
            }
            d.save(file)
        }
        PDDocument.load(file).use { d ->
            val res = d.getPage(0).resources
            for (n in res.fontNames) {
                val f = res.getFont(n).cosObject
                f.removeItem(COSName.TO_UNICODE); f.removeItem(COSName.ENCODING)
                (f.getDictionaryObject(COSName.FONT_DESC) as com.tom_roush.pdfbox.cos.COSDictionary).setInt(COSName.FLAGS, 4)
            }
            d.save(file)
        }
        return file
    }

    class Span(val text: String, val font: PDFont = PDType1Font.HELVETICA, val rgb: Triple<Float, Float, Float> = Triple(0f, 0f, 0f))

    /** A paragraph of mixed spans (a bold word, a link...) wrapped to [width]; every word is shown in its own span's font and colour. */
    fun mixedParagraph(file: File, spans: List<Span>, size: Float = 12f, width: Float = 380f, left: Float = 72f, top: Float = 700f, extra: (PDPageContentStream) -> Unit = {}): File {
        PDDocument().use { d ->
            val page = PDPage(PDRectangle.LETTER); d.addPage(page)
            PDPageContentStream(d, page).use { cs ->
                class W(val t: String, val s: Span)
                val words = spans.flatMap { sp -> sp.text.split(' ').filter { it.isNotEmpty() }.map { W(it, sp) } }
                fun wd(w: W) = w.s.font.getStringWidth(w.t) / 1000f * size
                val space = PDType1Font.HELVETICA.getStringWidth(" ") / 1000f * size
                var y = top; var x = left
                for (w in words) {
                    if (x > left && x + wd(w) > left + width) { x = left; y -= size * 1.2f }
                    cs.setNonStrokingColor(w.s.rgb.first, w.s.rgb.second, w.s.rgb.third)
                    cs.beginText(); cs.setFont(w.s.font, size); cs.newLineAtOffset(x, y); cs.showText(w.t); cs.endText()
                    x += wd(w) + space
                }
                extra(cs)
            }
            d.save(file)
        }
        return file
    }

    /** Lines of text set straight, then the whole page content turned by [degrees] about the page origin, like a crooked scan. */
    fun skewed(file: File, degrees: Float, lines: List<String>, font: PDFont = PDType1Font.TIMES_ROMAN): File {
        PDDocument().use { d ->
            val page = PDPage(PDRectangle.LETTER); d.addPage(page)
            PDPageContentStream(d, page).use { cs ->
                val th = Math.toRadians(degrees.toDouble())
                lines.forEachIndexed { i, t ->
                    val x = 72f; val y = 700f - i * 14.4f
                    val px = (x * Math.cos(th) - y * Math.sin(th)).toFloat(); val py = (x * Math.sin(th) + y * Math.cos(th)).toFloat()
                    cs.beginText(); cs.setFont(font, 12f)
                    cs.setTextMatrix(Matrix(Math.cos(th).toFloat(), Math.sin(th).toFloat(), -Math.sin(th).toFloat(), Math.cos(th).toFloat(), px, py))
                    cs.showText(t); cs.endText()
                }
            }
            d.save(file)
        }
        return file
    }

    /** A page with a FreeText annotation and ordinary page text. */
    fun withFreeText(file: File): File {
        ExistingFixtures.doc(file) { _, _, cs -> cs.apply { beginText(); setFont(PDType1Font.HELVETICA, 12f); newLineAtOffset(72f, 700f); showText("Page text that is editable"); endText() } }
        PDDocument.load(file).use { d ->
            val ann = PDAnnotationMarkup()
            ann.cosObject.setName(COSName.SUBTYPE, "FreeText")
            ann.rectangle = PDRectangle(72f, 500f, 200f, 40f)
            ann.contents = "Comment box text"
            val page = d.getPage(0)
            val list = page.annotations
            list.add(ann)
            page.annotations = list
            d.save(file)
        }
        return file
    }

    /** Outline-only text (render mode 1) next to normal text. */
    fun outlined(file: File): File = ExistingFixtures.doc(file) { _, _, cs ->
        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(72f, 700f); cs.showText("Normal text stays editable"); cs.endText()
        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 24f); cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.STROKE)
        cs.newLineAtOffset(72f, 600f); cs.showText("OUTLINED"); cs.endText()
    }
}
