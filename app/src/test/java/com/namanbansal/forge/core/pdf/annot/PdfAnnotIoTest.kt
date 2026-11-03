package com.namanbansal.forge.core.pdf.annot

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfOpenResult
import com.namanbansal.forge.core.pdf.PdfProtection
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class PdfAnnotIoTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun pdf(rotation: Int = 0, pw: String? = null): File {
        val f = PdfFixtures.create(File(tmp.root, "in${System.nanoTime()}.pdf"), pages = 2, rotations = if (rotation != 0) mapOf(0 to rotation) else emptyMap(), userPassword = pw)
        return f
    }

    private val hl = Annot("h", 0, AnnotType.HIGHLIGHT, AnnotPalette.YELLOW, 1f, quads = listOf(NormRect(0.1f, 0.1f, 0.6f, 0.13f), NormRect(0.1f, 0.14f, 0.4f, 0.17f)))
    private val ul = Annot("u", 0, AnnotType.UNDERLINE, AnnotPalette.RED, 1f, quads = listOf(NormRect(0.2f, 0.3f, 0.7f, 0.33f)))
    private val so = Annot("s", 1, AnnotType.STRIKEOUT, AnnotPalette.BLUE, 1f, quads = listOf(NormRect(0.2f, 0.5f, 0.5f, 0.53f)))
    private val ink = Annot("i", 0, AnnotType.INK, AnnotPalette.GREEN, 1f, strokes = listOf(listOf(NormPt(0.2f, 0.6f), NormPt(0.3f, 0.65f), NormPt(0.4f, 0.6f), NormPt(0.5f, 0.7f))), width = 0.006f)
    private val marker = Annot("m", 1, AnnotType.INK, AnnotPalette.YELLOW, AnnotPalette.HIGHLIGHTER_OPACITY, strokes = listOf(listOf(NormPt(0.2f, 0.2f), NormPt(0.6f, 0.2f))), width = 0.03f)
    private val note = Annot("n", 0, AnnotType.NOTE, AnnotPalette.EMBER, 1f, anchor = NormPt(0.8f, 0.1f), text = "Check this figure")

    private fun save(src: File, list: List<Annot>, baseline: List<Annot> = emptyList(), protection: PdfProtection? = null): File {
        val out = File(tmp.root, "out${System.nanoTime()}.pdf")
        PdfAnnotIo.write(src, out, baseline, list, protection)
        return out
    }

    private fun reread(f: File, pw: String = ""): List<Annot> = PDDocument.load(f, pw).use { PdfAnnotIo.read(it) }

    private fun near(a: Float, b: Float, eps: Float = 0.003f) = assertTrue("$a vs $b", kotlin.math.abs(a - b) <= eps)

    @Test fun textMarkupRoundTripsWithQuadPointsColourAndRect() {
        val out = save(pdf(), listOf(hl, ul, so))
        PDDocument.load(out).use { doc ->
            val h = doc.getPage(0).annotations.filterIsInstance<PDAnnotationTextMarkup>().first { it.subtype == "Highlight" }
            assertEquals(16, h.quadPoints.size)
            // A4: 595 x 842. First quad: left 0.1*595, top at y = 842 * (1-0.1)
            near(h.quadPoints[0], 0.1f * 595.276f, 0.5f); near(h.quadPoints[1], 842f * 0.9f, 1.5f) // top-left
            near(h.quadPoints[2], 0.6f * 595.276f, 0.5f)                                      // top-right x
            near(h.quadPoints[5], 842f * 0.87f, 1.5f)                                         // bottom-left y
            val r = h.rectangle
            assertTrue(r.lowerLeftX <= 0.1f * 595.276f + 0.5f && r.upperRightX >= 0.6f * 595.276f - 0.5f)
            val c = h.color.components
            assertEquals(1f, c[0], 0.01f); assertEquals(0xD6 / 255f, c[1], 0.01f)
            assertEquals(1f, h.constantOpacity, 0f)
        }
        val back = reread(out)
        assertEquals(setOf(AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKEOUT), back.map { it.type }.toSet())
        val h = back.first { it.type == AnnotType.HIGHLIGHT }
        assertEquals(2, h.quads.size)
        near(h.quads[0].left, 0.1f); near(h.quads[0].top, 0.1f); near(h.quads[0].right, 0.6f); near(h.quads[0].bottom, 0.13f)
        assertEquals(AnnotPalette.YELLOW, h.color)
        assertEquals(1, back.first { it.type == AnnotType.STRIKEOUT }.page)
    }

    @Test fun inkAndHighlighterKeepPathsWidthAndOpacity() {
        val out = save(pdf(), listOf(ink, marker))
        PDDocument.load(out).use { doc ->
            val i = PDAnnotationMarkup(doc.getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS).let { (it as com.tom_roush.pdfbox.cos.COSArray).getObject(0) as COSDictionary })
            assertEquals("Ink", i.cosObject.getNameAsString(COSName.SUBTYPE))
            assertEquals(1, i.inkList.size)
            assertTrue(i.inkList[0].size >= 8)
            near(i.borderStyle.width, 0.006f * 595.276f, 0.05f)
            val m = doc.getPage(1).annotations.first() as PDAnnotationMarkup
            assertEquals(AnnotPalette.HIGHLIGHTER_OPACITY, m.constantOpacity, 0.001f)
        }
        val back = reread(out)
        val i = back.first { it.type == AnnotType.INK && !it.isHighlighterInk }
        near(i.strokes[0].first().x, 0.2f); near(i.strokes[0].first().y, 0.6f)
        near(i.strokes[0].last().x, 0.5f); near(i.strokes[0].last().y, 0.7f)
        near(i.width, 0.006f, 0.0005f)
        assertEquals(AnnotPalette.GREEN, i.color)
        val m = back.first { it.isHighlighterInk }
        assertEquals(AnnotPalette.HIGHLIGHTER_OPACITY, m.opacity, 0.001f)
    }

    @Test fun noteKeepsTextPositionAndColour() {
        val out = save(pdf(), listOf(note))
        val back = reread(out).single()
        assertEquals(AnnotType.NOTE, back.type)
        assertEquals("Check this figure", back.text)
        near(back.anchor!!.x, 0.8f); near(back.anchor!!.y, 0.1f)
        assertEquals(AnnotPalette.EMBER, back.color)
        PDDocument.load(out).use { assertEquals("Text", it.getPage(0).annotations.first().subtype) }
    }

    @Test fun everyTypeGetsAnAppearanceStream() {
        val out = save(pdf(), listOf(hl, ul, so, ink, marker, note))
        PDDocument.load(out).use { doc ->
            val all = doc.pages.flatMap { it.annotations }
            assertEquals(6, all.size)
            for (a in all) {
                val ap = a.appearance?.normalAppearance
                assertNotNull("${a.subtype} has no appearance", ap)
                assertTrue("${a.subtype} appearance is empty", ap!!.isStream && ap.appearanceStream.contentStream.length > 0)
            }
        }
    }

    @Test fun rotatedPagesMapBackToTheSameFractions() {
        for (rot in listOf(90, 180, 270)) {
            val out = save(pdf(rotation = rot), listOf(hl, ink, note))
            val back = reread(out).filter { it.page == 0 }
            val h = back.first { it.type == AnnotType.HIGHLIGHT }
            near(h.quads[0].left, 0.1f); near(h.quads[0].top, 0.1f); near(h.quads[0].right, 0.6f); near(h.quads[0].bottom, 0.13f)
            near(back.first { it.type == AnnotType.NOTE }.anchor!!.x, 0.8f, 0.006f)
            near(back.first { it.type == AnnotType.INK }.strokes[0].last().y, 0.7f)
        }
    }

    @Test fun existingAnnotationsAreEditedInPlaceAndRemoved() {
        val first = save(pdf(), listOf(hl, ul, note))
        val existing = reread(first)
        assertEquals(3, existing.size)
        assertTrue(existing.all { it.isExisting })
        val delete = existing.first { it.type == AnnotType.UNDERLINE }
        val recolour = existing.first { it.type == AnnotType.HIGHLIGHT }.copy(color = AnnotPalette.GREEN)
        val retext = existing.first { it.type == AnnotType.NOTE }.copy(text = "Updated")
        val second = save(first, listOf(recolour, retext), baseline = existing)
        val back = reread(second)
        assertEquals(setOf(AnnotType.HIGHLIGHT, AnnotType.NOTE), back.map { it.type }.toSet())
        assertEquals(AnnotPalette.GREEN, back.first { it.type == AnnotType.HIGHLIGHT }.color)
        assertEquals("Updated", back.first { it.type == AnnotType.NOTE }.text)
        assertNull(back.firstOrNull { it.id == delete.id && it.type == AnnotType.UNDERLINE })
        PDDocument.load(second).use { d -> assertTrue(d.getPage(0).annotations.all { it.appearance?.normalAppearance != null }) }
    }

    @Test fun stripLeavesPageWithoutSupportedAnnotationsAndReportsThem() {
        val with = save(pdf(), listOf(hl, ink, note, so))
        val dst = File(tmp.root, "stripped.pdf")
        val found = PdfAnnotIo.strip(with, dst)!!
        assertEquals(4, found.size)
        assertTrue(reread(dst).isEmpty())
        assertNull(PdfAnnotIo.strip(pdf(), File(tmp.root, "none.pdf")))
        assertTrue(!File(tmp.root, "none.pdf").exists())
    }

    @Test fun encryptedDocumentKeepsItsPassword() {
        val enc = pdf(pw = "s3cret")
        val plain = (runBlocking { PdfLoader.open(enc, tmp.newFolder(), "s3cret") } as PdfOpenResult.Ready).source
        assertNotNull(plain.protection)
        val out = save(plain.file, listOf(hl, note), protection = plain.protection)
        PDDocument.load(out, "s3cret").use { assertTrue(it.isEncrypted); assertEquals(2, it.getPage(0).annotations.size) }
        assertTrue(runBlocking { PdfLoader.open(out, tmp.newFolder(), null) } is PdfOpenResult.NeedsPassword)
        assertEquals(2, reread(out, "s3cret").size)
    }

    @Test fun savedAppearanceRendersWithPdfBox() {
        val out = save(pdf(), listOf(hl, ink, note))
        PDDocument.load(out).use { doc ->
            val img = com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(0, 1f)
            // inside the highlight box (y ~ 0.115 of the page) the white page must have turned yellow-ish
            val px = img.getPixel((0.3f * img.width).toInt(), (0.115f * img.height).toInt())
            assertTrue("highlight not painted: ${Integer.toHexString(px)}", (px shr 16 and 0xFF) > 200 && (px and 0xFF) < 120)
        }
    }
}
