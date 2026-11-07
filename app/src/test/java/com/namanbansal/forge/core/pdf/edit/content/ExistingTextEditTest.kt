package com.namanbansal.forge.core.pdf.edit.content

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfTextSearch
import com.namanbansal.forge.core.pdf.edit.EditJson
import com.namanbansal.forge.core.pdf.edit.EditSession
import com.namanbansal.forge.core.pdf.edit.EditState
import com.namanbansal.forge.core.pdf.edit.ExistingApplier
import com.namanbansal.forge.core.pdf.edit.ExistingEdit
import com.namanbansal.forge.core.pdf.edit.ExistingKind
import com.namanbansal.forge.core.pdf.edit.ExistingReport
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.line
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.paragraph
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

val TEST_FONTS = FontOpener { File("src/main/assets/$it").inputStream() }

internal fun scanBlocks(f: File, page: Int = 0): List<TextBlock> = PDDocument.load(f).use { d ->
    val p = d.getPage(page); val cb = p.cropBox
    TextBlocks.detect(ContentWalker.scan(d, p), Box(cb.lowerLeftX, cb.lowerLeftY, cb.upperRightX, cb.upperRightY))
}

internal fun pageText(f: File, page: Int = 0): String = PDDocument.load(f).use { d ->
    PdfTextSearch.Collector().also { it.setStartPage(page + 1); it.setEndPage(page + 1); it.getText(d) }.text().replace(Regex("\\s+"), " ").trim()
}

internal fun textEdit(b: TextBlock, text: String, id: String = "e1", page: Int = 0, f: (ExistingEdit) -> ExistingEdit = { it }) =
    f(ExistingEdit(id, page, ExistingKind.TEXT, b.key, NormRect(0f, 0f, 1f, 1f), text = text, origText = b.text))

@RunWith(RobolectricTestRunner::class)
class ExistingTextEditTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private val para1 = "Alpha paragraph says that the old budget was approved by the committee last spring after a very long discussion about costs"
    private val para2 = "Bravo paragraph stays exactly where it is and must never be touched by edits to its neighbour above because it is separate"

    private fun twoParas(): File = ExistingFixtures.doc(tmp.newFile("two.pdf")) { _, _, cs ->
        cs.line(ExistingFixtures.HELV_B, 20f, 72f, 730f, "Budget Review")
        cs.paragraph(ExistingFixtures.TIMES, 12f, 72f, 690f, 400f, para1, rgb = Triple(0.8f, 0f, 0f))
        cs.paragraph(ExistingFixtures.TIMES, 12f, 72f, 560f, 400f, para2)
    }

    private fun fontNames(f: File): Set<String> = PDDocument.load(f).use { d ->
        PdfTextSearch.Collector().also { it.setStartPage(1); it.setEndPage(1); it.getText(d) }.positions.filterNotNull().map { it.font.name }.toSet()
    }

    @Test fun roundTripReplacesTextKeepsNeighboursFontColourAndSize() {
        val f = twoParas()
        val b = scanBlocks(f).first { it.text.startsWith("Alpha") }
        assertEquals(para1, b.text)
        val newText = "Alpha paragraph now reports that the new budget was rejected by the board this autumn"
        val bravoBefore = scanBlocks(f).first { it.text.startsWith("Bravo") }.firstBaseline
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, newText)), TEST_FONTS) { null }
        val txt = pageText(f)
        assertTrue(txt, txt.contains(newText))
        assertFalse(txt, txt.contains("old budget"))
        assertTrue(txt, txt.contains(para2)); assertTrue(txt.contains("Budget Review"))
        assertTrue(fontNames(f).containsAll(setOf("Times-Roman", "Helvetica-Bold")))
        val after = scanBlocks(f)
        val nb = after.first { it.text.startsWith("Alpha") }
        assertEquals(newText, nb.text)
        assertEquals(0xFFCC0000.toInt(), nb.color); assertEquals(12f, nb.size, 0.2f)
        assertEquals(b.left, nb.left, 0.6f); assertEquals(b.firstBaseline, nb.firstBaseline, 0.6f)
        assertEquals(para2, after.first { it.text.startsWith("Bravo") }.text)
        assertEquals(bravoBefore, after.first { it.text.startsWith("Bravo") }.firstBaseline, 0.01f)
    }

    @Test fun rewrapStaysWithinBlockWidthWithRealMetrics() {
        val f = twoParas()
        val b = scanBlocks(f).first { it.text.startsWith("Alpha") }
        val long = (1..8).joinToString(" ") { "Alpha paragraph with much more words than before to wrap around" }
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, long)), TEST_FONTS) { null }
        val nb = scanBlocks(f).first { it.text.startsWith("Alpha") }
        assertTrue(nb.lineCount > b.lineCount)
        for (l in nb.lines) assertTrue("${l.width} > ${b.maxWidth}", l.width <= b.maxWidth + 0.5f)
        assertEquals(b.leading, nb.leading, 0.2f)
    }

    @Test fun growthIntoTheNextBlockIsReported() {
        val f = twoParas()
        val bs = scanBlocks(f)
        val b = bs.first { it.text.startsWith("Alpha") }
        val doc = PDDocument.load(f)
        val choice = TextFonts.choose(doc, TEST_FONTS, b, emptyMap(), "x", null, null)
        val short = TextLayout.layout(b.text, choice.coder, b.size, b.leading, b.align, b.left, b.maxWidth, b.firstBaseline, b.firstIndent)
        assertNull(TextLayout.overlap(b, short, bs))
        val big = TextLayout.layout((1..6).joinToString(" ") { para1 }, choice.coder, b.size, b.leading, b.align, b.left, b.maxWidth, b.firstBaseline, b.firstIndent)
        val hit = TextLayout.overlap(b, big, bs)
        assertNotNull(hit); assertTrue(hit!!.text.startsWith("Bravo"))
        doc.close()
    }

    @Test fun centredTitleStaysCentredAndSizeAndAlignCanChange() {
        val f = ExistingFixtures.doc(tmp.newFile("t.pdf")) { _, _, cs ->
            val t = "Annual Report"; val w = ExistingFixtures.HELV_B.getStringWidth(t) / 1000f * 24f
            cs.line(ExistingFixtures.HELV_B, 24f, (612f - w) / 2, 720f, t)
        }
        val b = scanBlocks(f).single()
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, "Quarterly Results Overview")), TEST_FONTS) { null }
        val nb = scanBlocks(f).single()
        assertEquals("Quarterly Results Overview", nb.text); assertEquals(Align.CENTER, nb.align)
        assertEquals(306f, nb.lines[0].cx, 1.5f)
        ExistingApplier.applyInPlace(f, listOf(textEdit(nb, "Quarterly Results", f = { it.copy(sizePt = 30f, align = Align.RIGHT, hasColor = true, color = 0xFF1F4FD8.toInt()) })), TEST_FONTS) { null }
        val n2 = scanBlocks(f).single()
        assertEquals(30f, n2.size, 0.3f); assertEquals(0xFF1F4FD8.toInt(), n2.color)
    }

    @Test fun boldAndItalicOnAStandardFontUseTheMatchingStandardFont() {
        val f = twoParas()
        val b = scanBlocks(f).first { it.text.startsWith("Bravo") }
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, b.text) { it.copy(bold = 1, italic = 1) }), TEST_FONTS) { null }
        assertTrue(fontNames(f).contains("Times-BoldItalic"))
    }

    @Test fun deletingABlockRemovesItAndLeavesTheRest() {
        val f = twoParas()
        val b = scanBlocks(f).first { it.text.startsWith("Alpha") }
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, "") { it.copy(delete = true) }), TEST_FONTS) { null }
        val t = pageText(f)
        assertFalse(t.contains("Alpha")); assertTrue(t.contains(para2)); assertTrue(t.contains("Budget Review"))
    }

    @Test fun subsetMissingALetterFallsBackToNotoWithANotice() {
        val f = tmp.newFile("subset.pdf")
        PDDocument().use { d ->
            val p = com.tom_roush.pdfbox.pdmodel.PDPage(); d.addPage(p)
            val font = TEST_FONTS.open("fonts/NotoSerif-Regular.ttf").use { PDType0Font.load(d, it, true) }
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(font, 14f); cs.newLineAtOffset(72f, 700f); cs.showText("Quick brown fox"); cs.endText()
            }
            d.save(f)
        }
        val b = scanBlocks(f).single()
        assertTrue(b.fontName.contains("+")) // a real subset
        // letters that are in the subset: the font is reused, no notice
        val ok = tmp.newFile("ok.pdf"); f.copyTo(ok, true)
        val r1 = ExistingReport()
        PDDocument.load(ok).use { d -> ExistingApplier.apply(d, listOf(textEdit(b, "Quick fox brown")), TEST_FONTS, { null }, r1); d.save(ok) }
        assertTrue(r1.notices.isEmpty()); assertTrue(pageText(ok).contains("Quick fox brown"))
        assertTrue(fontNames(ok).all { it.contains("NotoSerif") && it.contains("+") }) // only the original subset
        // z is not in the subset: Noto Serif is embedded instead and the notice says so
        val r2 = ExistingReport()
        PDDocument.load(f).use { d -> ExistingApplier.apply(d, listOf(textEdit(b, "Lazy zebra quiz")), TEST_FONTS, { null }, r2); d.save(f) }
        assertEquals(listOf("Used Noto Serif — the original font doesn't include some of these letters."), r2.notices.toList())
        assertTrue(pageText(f), pageText(f).contains("Lazy zebra quiz"))
        assertFalse(pageText(f).contains("Quick"))
    }

    @Test fun serifAndMonoFallbackAreChosenFromTheFontFlags() {
        val serif = TextFonts.faceFor(FontTraits.of(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.TIMES_ROMAN))
        val mono = TextFonts.faceFor(FontTraits.of(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.COURIER_BOLD))
        val sans = TextFonts.faceFor(FontTraits.of(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA))
        assertEquals(listOf(NotoFace.SERIF, NotoFace.MONO, NotoFace.SANS), listOf(serif, mono, sans))
    }

    @Test fun undoRedoAndPendingPersistence() {
        val s = EditSession()
        val e = ExistingEdit("a", 0, ExistingKind.TEXT, 5, NormRect(0.1f, 0.1f, 0.5f, 0.2f), text = "hi", origText = "hello", sizePt = 14f, hasColor = true, color = 0xFF112233.toInt(), bold = 1)
        val img = ExistingEdit("b", 1, ExistingKind.IMAGE, 0, NormRect(0f, 0f, 0.5f, 0.5f), newRect = NormRect(0.2f, 0.2f, 0.4f, 0.4f), turns = 1, replaceImageId = "img1")
        s.upsertExisting(e); s.upsertExisting(img); s.upsertExisting(e.copy(text = "again"))
        assertEquals(2, s.current.existing.size); assertEquals("again", s.findExisting(0, ExistingKind.TEXT, 5)?.text)
        val back = EditJson.decode(EditJson.encode(s.current))
        assertEquals(s.current, back)
        s.undo(); assertEquals("hi", s.findExisting(0, ExistingKind.TEXT, 5)?.text)
        s.undo(); s.undo(); assertTrue(s.current.isEmpty); assertFalse(s.dirty.value)
        s.redo(); assertTrue(s.dirty.value); assertTrue(EditState().isEmpty)
    }
}
