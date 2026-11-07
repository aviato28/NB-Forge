package com.namanbansal.forge.core.pdf.edit.content

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.line
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.paragraph
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TextBlocksTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun blocks(f: File): List<TextBlock> = PDDocument.load(f).use { d ->
        val p = d.getPage(0); val cb = p.cropBox
        TextBlocks.detect(ContentWalker.scan(d, p), Box(cb.lowerLeftX, cb.lowerLeftY, cb.upperRightX, cb.upperRightY))
    }

    @Test fun twoColumnsAreTwoBlocksAndKeepTheirOwnLines() {
        val f = ExistingFixtures.doc(tmp.newFile("cols.pdf")) { _, _, cs ->
            cs.paragraph(ExistingFixtures.TIMES, 11f, 50f, 700f, 230f, ExistingFixtures.LOREM + " " + ExistingFixtures.LOREM)
            cs.paragraph(ExistingFixtures.TIMES, 11f, 330f, 700f, 230f, "Second column text starts here and also wraps over several lines so that it is a real paragraph of text " + ExistingFixtures.LOREM)
        }
        val b = blocks(f)
        assertEquals(2, b.size)
        assertTrue(b.all { it.lineCount >= 3 && it.wrapped && it.align == Align.LEFT })
        val l = b.minBy { it.left }; val r = b.maxBy { it.left }
        assertTrue(l.text.startsWith("The quick brown fox")); assertTrue(r.text.startsWith("Second column"))
        assertEquals(11f, l.size, 0.2f)
        assertTrue(l.leading in 13f..13.5f)
    }

    @Test fun centredTitleAndBodyAreSeparateBlocks() {
        val f = ExistingFixtures.doc(tmp.newFile("title.pdf")) { _, _, cs ->
            val t = "Annual Report 2026"; val w = ExistingFixtures.HELV_B.getStringWidth(t) / 1000f * 24f
            cs.line(ExistingFixtures.HELV_B, 24f, (612f - w) / 2, 720f, t)
            cs.paragraph(ExistingFixtures.TIMES, 11f, 72f, 660f, 468f, ExistingFixtures.LOREM + " " + ExistingFixtures.LOREM)
        }
        val b = blocks(f)
        assertEquals(2, b.size)
        val title = b.first { it.text.startsWith("Annual") }
        assertEquals(Align.CENTER, title.align); assertEquals("Annual Report 2026", title.text)
        assertEquals(24f, title.size, 0.3f)
        assertTrue(title.traits.bold)
        assertEquals(Align.LEFT, b.first { it !== title }.align)
    }

    @Test fun justifiedParagraphIsDetectedAndColourIsKept() {
        val f = ExistingFixtures.doc(tmp.newFile("just.pdf")) { _, _, cs ->
            cs.paragraph(ExistingFixtures.TIMES, 11f, 72f, 700f, 400f, ExistingFixtures.LOREM + " " + ExistingFixtures.LOREM + " " + ExistingFixtures.LOREM, rgb = Triple(0.8f, 0f, 0f), justify = true)
        }
        val b = blocks(f).single()
        assertEquals(Align.JUSTIFY, b.align)
        assertEquals(0xFFCC0000.toInt(), b.color)
        assertTrue(b.wrapped)
    }

    @Test fun mixedSizesSplitIntoBlocksAndShortLabelsStaySingle() {
        val f = ExistingFixtures.doc(tmp.newFile("mixed.pdf")) { _, _, cs ->
            cs.line(ExistingFixtures.HELV_B, 18f, 72f, 720f, "Heading")
            cs.paragraph(ExistingFixtures.HELV, 10f, 72f, 695f, 300f, ExistingFixtures.LOREM)
            cs.line(ExistingFixtures.HELV, 10f, 72f, 500f, "Name:")
            cs.line(ExistingFixtures.HELV, 10f, 200f, 500f, "Jane Doe")
        }
        val b = blocks(f)
        assertEquals(4, b.size)
        assertEquals(18f, b.first { it.text == "Heading" }.size, 0.3f)
        assertTrue(b.first { it.text == "Name:" }.maxWidth < 115f) // stopped by the next thing on the line
        assertTrue(b.first { it.text == "Jane Doe" }.maxWidth > 150f) // can grow to the page margin
    }

    @Test fun shortLastLineEndsAParagraph() {
        val f = ExistingFixtures.doc(tmp.newFile("paras.pdf")) { _, _, cs ->
            val n = cs.paragraph(ExistingFixtures.TIMES, 11f, 72f, 700f, 300f, ExistingFixtures.LOREM + " end.")
            cs.paragraph(ExistingFixtures.TIMES, 11f, 72f, 700f - n * 13.2f, 300f, "Next paragraph here " + ExistingFixtures.LOREM)
        }
        assertEquals(2, blocks(f).size)
    }
}
