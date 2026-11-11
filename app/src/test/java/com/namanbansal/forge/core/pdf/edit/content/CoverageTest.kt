package com.namanbansal.forge.core.pdf.edit.content

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.pdf.edit.ExistingApplier
import com.namanbansal.forge.core.pdf.edit.content.CoverageFixtures.BLUE
import com.namanbansal.forge.core.pdf.edit.content.CoverageFixtures.Span
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Round 2F: the cases the owner hit ("some patches it doesn't recognise"), built as generated PDFs. */
@RunWith(RobolectricTestRunner::class)
class CoverageTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun scan(f: File): PageScan = PDDocument.load(f).use { d -> ContentWalker.scan(d, d.getPage(0)) }

    // ---- no-Unicode glyphs ------------------------------------------------------------------------------------------------

    private val invoice = listOf("Invoice 2026-0042 total due 1,250.00", "Payable to Forge Tools Ltd within thirty days")

    @Test fun cidFontWithoutToUnicodeIsReadBackFromTheEmbeddedFont() {
        val f = CoverageFixtures.cidNoToUnicode(tmp.newFile("cid.pdf"), invoice)
        // PDFBox itself has no text for these glyphs: the owner's "it doesn't recognise it"
        PDDocument.load(f).use { d -> assertTrue(d.getPage(0).resources.fontNames.all { n -> d.getPage(0).resources.getFont(n).toUnicode(1) == null }) }
        val s = scan(f)
        assertTrue("every character is editable now", s.glyphs.all { it.editable })
        assertTrue(s.glyphs.any { it.recovered }); assertTrue(s.glyphs.none { it.unmapped })
        val blocks = scanBlocks(f)
        assertEquals(invoice.joinToString(" "), blocks.joinToString(" ") { it.text }.replace(Regex("\\s+"), " "))
        assertTrue(blocks.none { it.unreadable })
    }

    @Test fun editingRecoveredTextRewritesItAndRemovesTheOldGlyphs() {
        val f = CoverageFixtures.cidNoToUnicode(tmp.newFile("cid.pdf"), invoice)
        val b = scanBlocks(f).first { it.text.startsWith("Invoice") }
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, "Invoice 2026-0042 total due 980.50")), TEST_FONTS) { null }
        val after = pageText(f)
        assertTrue(after, after.contains("Invoice 2026-0042 total due 980.50"))
        assertFalse(after, after.contains("1,250.00"))
        assertTrue(after, after.contains("Payable to Forge Tools Ltd"))
    }

    @Test fun simpleTrueTypeWithoutEncodingIsReadOrReplaceable() {
        val f = CoverageFixtures.trueTypeNoEncoding(tmp.newFile("tt.pdf"), invoice)
        val s = scan(f)
        assertTrue("never locked as 'no Unicode mapping'", s.glyphs.all { it.editable })
        val b = scanBlocks(f).first()
        // either the cmap gave the text back, or the block is offered as replace-only: both are editable
        assertTrue(b.unreadable || b.text.startsWith("Invoice"))
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, "Replaced invoice line")), TEST_FONTS) { null }
        assertTrue(pageText(f), pageText(f).contains("Replaced invoice line"))
    }

    @Test fun textWithNothingToRecoverIsReplaceOnlyNotLocked() {
        val f = CoverageFixtures.cidNoToUnicode(tmp.newFile("lost.pdf"), invoice, embedded = false)
        val s = scan(f)
        assertTrue(s.glyphs.isNotEmpty())
        assertTrue("editable even though nothing says what the letters are", s.glyphs.all { it.editable && it.unmapped })
        val blocks = scanBlocks(f)
        assertTrue(blocks.isNotEmpty() && blocks.all { it.unreadable })
        val b = blocks.first()
        assertTrue(b.text.contains('�'))
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, "Typed instead of the unreadable line")), TEST_FONTS) { null }
        val txt = pageText(f)
        assertTrue(txt, txt.contains("Typed instead of the unreadable line"))
        val after = scan(f)
        assertTrue("the old glyphs are gone", after.glyphs.count { it.unmapped } < s.glyphs.count { it.unmapped })
    }

    // ---- mixed styles ----------------------------------------------------------------------------------------------------------

    private val HB = PDType1Font.HELVETICA_BOLD
    private fun mixed(): File = CoverageFixtures.mixedParagraph(
        tmp.newFile("mixed.pdf"),
        listOf(
            Span("Please review the contract and send it back by"), Span("Friday", HB), Span("so that we can sign it. All details are on the portal at"),
            Span("https://example.com/contract/details/page", rgb = BLUE), Span("and the team will answer every question you may have about it."),
        ),
    )

    @Test fun boldWordAndColouredLinkStayInOneParagraph() {
        val f = mixed()
        val blocks = scanBlocks(f)
        assertEquals(1, blocks.size)
        val b = blocks.single()
        assertTrue("several lines", b.lineCount >= 3)
        assertTrue(b.mixed)
        val runs = runsOf(b.styles)
        assertTrue(runs.any { it.style.traits.bold && b.text.substring(it.start, it.end).trim() == "Friday" })
        assertTrue(runs.any { it.style.color == 0xFF0D40D9.toInt() || b.text.substring(it.start, it.end).contains("https://example.com") })
    }

    @Test fun aLineEntirelyInLinkColourDoesNotSplitTheParagraph() {
        val f = CoverageFixtures.mixedParagraph(
            tmp.newFile("link.pdf"),
            listOf(Span("Read the full terms of the agreement at the address given below before you sign anything at all, thank you."), Span("https://example.com/a/very/long/link/that/fills/at/least/one/line/of/the/box/by/itself/and/more", rgb = BLUE)),
            width = 300f,
        )
        val blocks = scanBlocks(f)
        assertEquals(blocks.joinToString { it.text }, 1, blocks.size)
        assertTrue(blocks.single().mixed)
    }

    @Test fun editingOneWordKeepsTheBoldWordAndTheLinkColour() {
        val f = mixed()
        val b = scanBlocks(f).single()
        val newText = b.text.replace("Please review", "Kindly check")
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, newText)), TEST_FONTS) { null }
        val after = scanBlocks(f).single()
        assertEquals(newText, after.text)
        val runs = runsOf(after.styles)
        val bold = runs.first { b2 -> after.text.substring(b2.start, b2.end).trim() == "Friday" }
        assertTrue("Friday is still bold", bold.style.traits.bold)
        val link = runs.first { after.text.substring(it.start, it.end).contains("https://example.com") }
        assertEquals("the link keeps its colour", 0xFF0D40D9.toInt(), link.style.color, )
        val plain = runs.first { after.text.substring(it.start, it.end).startsWith("Kindly") }
        assertFalse("the typed words are not bold", plain.style.traits.bold)
        assertEquals(0xFF000000.toInt(), plain.style.color)
    }

    @Test fun forcingBoldFlattensTheBlockToOneLook() {
        val f = mixed()
        val b = scanBlocks(f).single()
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, b.text) { it.copy(bold = 1) }), TEST_FONTS) { null }
        val after = scanBlocks(f).single()
        assertTrue(after.styles.all { it.traits.bold })
    }

    @Test fun styleMapKeepsEndsAndGivesTypedTextTheLookBeforeIt() {
        val a = RunStyle(PDType1Font.HELVETICA, 1, "H", FontTraits.of(PDType1Font.HELVETICA), 0)
        val bld = RunStyle(PDType1Font.HELVETICA_BOLD, 2, "HB", FontTraits.of(PDType1Font.HELVETICA_BOLD), 0)
        val orig = "go Friday now"
        val styles = orig.mapIndexed { i, _ -> if (i in 3..8) bld else a }
        val m = StyleMap.map(orig, styles, "go Friday later now")!!
        assertEquals(orig.length + 6, m.size)
        assertTrue(m.slice(3..8).all { it === bld })
        assertTrue("typed after the bold word takes its look", m[9] === bld)
        assertTrue(m.last() === a)
        assertEquals(null, StyleMap.map(orig, emptyList(), "x"))
    }

    // ---- slightly rotated text -----------------------------------------------------------------------------------------------------

    private val scanLines = listOf("This paragraph was scanned a little crooked and", "the lines are all tilted by the same small angle", "so Forge has to straighten them before editing.")

    @Test fun slightlySkewedParagraphIsOneEditableBlock() {
        for (deg in listOf(0.5f, 1f, 1.5f, -1f)) {
            val f = CoverageFixtures.skewed(tmp.newFile("skew$deg.pdf"), deg, scanLines)
            val s = scan(f)
            assertTrue("$deg°: all editable", s.glyphs.all { it.editable })
            assertEquals(Math.toRadians(deg.toDouble()).toFloat(), s.skew, 0.004f)
            val blocks = scanBlocks(f)
            assertEquals("$deg°: ${blocks.map { it.text }}", 1, blocks.size)
            assertEquals(scanLines.joinToString(" "), blocks.single().text)
        }
    }

    @Test fun skewedParagraphKeepsItsTiltWhenRewritten() {
        val f = CoverageFixtures.skewed(tmp.newFile("skew.pdf"), 1.2f, scanLines)
        val b = scanBlocks(f).single()
        val before = scan(f).skew
        val newText = "This paragraph was edited after it was scanned a little crooked and the lines still follow the tilt."
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, newText)), TEST_FONTS) { null }
        val s = scan(f)
        assertEquals("tilt kept", before, s.skew, 0.004f)
        assertTrue(s.glyphs.all { it.editable })
        val after = scanBlocks(f)
        assertEquals(1, after.size)
        assertEquals(newText, after.single().text)
        // the first line still starts where it did (compared in the page's own frame)
        assertEquals(b.left, after.single().left, 1.0f)
        assertEquals(b.firstBaseline, after.single().firstBaseline, 1.0f)
    }

    @Test fun textTiltedMoreThanAFewDegreesStaysLockedAsRotated() {
        val f = CoverageFixtures.skewed(tmp.newFile("tilt.pdf"), 8f, scanLines)
        val s = scan(f)
        assertTrue(s.glyphs.none { it.editable })
        assertTrue(s.glyphs.all { it.note == "rotated text" })
        assertTrue(scanBlocks(f).isEmpty())
    }

    // ---- locked areas and why -------------------------------------------------------------------------------------------------

    @Test fun outlinedTextIsLockedWithASpecificReasonAndTheRestStaysEditable() {
        val f = CoverageFixtures.outlined(tmp.newFile("outl.pdf"))
        PDDocument.load(f).use { }
        val idx = ExistingIndex(f, TEST_FONTS)
        try {
            val o = idx.page(0)!!
            assertTrue(o.blocks.any { it.text.startsWith("Normal text") })
            assertEquals(listOf(LockReason.OUTLINES), o.locked.map { it.reason })
            assertTrue("the box sits on the outlined word, not on the normal text", o.locked.single().rect.top > o.blocks.first().rect.bottom)
            val dbg = idx.debugBoxes(0)
            assertTrue(dbg.any { it.editable }); assertTrue(dbg.any { !it.editable && it.note == "outlined text" })
        } finally { idx.close() }
    }

    @Test fun everyReasonHasPlainWords() {
        for (r in LockReason.entries) { assertTrue(r.title.isNotBlank()); assertTrue(r.detail.length > 40) }
    }

    @Test fun freeTextAnnotationsAreListedForTheOutline() {
        val f = CoverageFixtures.withFreeText(tmp.newFile("ft.pdf"))
        val idx = ExistingIndex(f, TEST_FONTS)
        try {
            val o = idx.page(0)!!
            assertEquals(1, o.freeText.size)
            assertEquals("Comment box text", o.freeText.single().text)
            assertTrue(o.blocks.single().text.startsWith("Page text"))
            assertNotNull(o.freeText.single().rect)
        } finally { idx.close() }
    }
}
