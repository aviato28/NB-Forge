package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.RenderProbe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkStampTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun run(src: File, edit: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit): File {
        val out = File(tmp.root, "o-${System.nanoTime()}.pdf")
        PdfOps.load(src).use { edit(it); it.save(out) }
        return out
    }

    private fun inked(f: File, page: Int = 0): Int {
        val b = RenderProbe.render(f, page)
        var n = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) if ((b.getPixel(x, y) and 0xFFFFFF) != 0xFFFFFF) n++
        return n
    }

    @Test fun textWatermarkOnSelectedPagesOnly() {
        val src = PdfFixtures.create(tmp.newFile("w.pdf"), pages = 3)
        var n = 0
        val out = run(src) { n = PdfWatermarker.apply(it, WatermarkSpec(text = "DRAFT COPY", rotation = 0f), setOf(0, 2)) }
        assertEquals(2, n)
        PdfOps.load(out).use { d ->
            val st = com.tom_roush.pdfbox.text.PDFTextStripper()
            st.startPage = 1; st.endPage = 1; assertTrue(st.getText(d).contains("DRAFT COPY"))
            st.startPage = 2; st.endPage = 2; assertFalse(st.getText(d).contains("DRAFT COPY"))
        }
        assertTrue(inked(out, 0) > 200); assertEquals(0, inked(out, 1))
    }

    @Test fun tiledCoversMoreThanASingleStamp() {
        val src = PdfFixtures.create(tmp.newFile("t.pdf"))
        val one = run(src) { PdfWatermarker.apply(it, WatermarkSpec(text = "SAMPLE", size = 30f, rotation = 0f)) }
        val tiled = run(src) { PdfWatermarker.apply(it, WatermarkSpec(text = "SAMPLE", size = 30f, rotation = 0f, position = WatermarkPosition.TILED)) }
        assertTrue(inked(tiled) > inked(one) * 4)
    }

    @Test fun behindStaysUnderOpaqueContentAndOverShowsThrough() {
        val src = run(PdfFixtures.create(tmp.newFile("b.pdf"))) { d ->
            PDPageContentStream(d, d.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true).use { cs -> cs.setNonStrokingColor(1f, 0f, 0f); cs.addRect(0f, 0f, 600f, 850f); cs.fill() }
        }
        val spec = { behind: Boolean -> WatermarkSpec(text = "XX", size = 300f, color = Color.BLUE, opacity = 1f, rotation = 0f, behind = behind) }
        val behind = RenderProbe.render(run(src) { PdfWatermarker.apply(it, spec(true)) })
        val over = RenderProbe.render(run(src) { PdfWatermarker.apply(it, spec(false)) })
        fun blueish(b: Bitmap): Int { var n = 0; for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) { val p = b.getPixel(x, y); if (RenderProbe.b(p) > 150 && RenderProbe.r(p) < 100) n++ }; return n }
        assertEquals(0, blueish(behind)); assertTrue(blueish(over) > 100)
    }

    @Test fun imageWatermarkPaintsThePicture() {
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 160, 0)) }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = run(PdfFixtures.create(tmp.newFile("i.pdf"))) { PdfWatermarker.apply(it, WatermarkSpec(text = null, image = bytes, opacity = 1f, imageScale = 0.3f, rotation = 0f)) }
        val p = RenderProbe.pixel(RenderProbe.render(out), 0.5f, 0.5f)
        assertTrue(RenderProbe.g(p) > 120 && RenderProbe.r(p) < 60)
    }

    @Test fun watermarkOnRotatedPageStillReadsUpright() {
        val src = PdfFixtures.create(tmp.newFile("r.pdf"), rotations = mapOf(0 to 90))
        val out = run(src) { PdfWatermarker.apply(it, WatermarkSpec(text = "ROT", rotation = 0f, position = 0, size = 40f)) }
        val b = RenderProbe.render(out)
        // top-left of what is displayed has ink, bottom-right does not
        fun ink(x0: Int, y0: Int, w: Int, h: Int): Int { var n = 0; for (y in y0 until y0 + h) for (x in x0 until x0 + w) if ((b.getPixel(x, y) and 0xFFFFFF) != 0xFFFFFF) n++; return n }
        assertTrue(ink(0, 0, b.width / 3, b.height / 4) > 50); assertEquals(0, ink(b.width * 2 / 3, b.height * 3 / 4, b.width / 3, b.height / 4))
    }

    // ---- page numbers / stamps
    @Test fun pageNumbersWithFormatsStartSkipAndRoman() {
        val spec = StampSpec(numberFormat = NumberFormat.PAGE_OF, start = 5, skipFirst = true)
        assertEquals(null, PdfStamper.pageNumberText(spec, 0, 4))
        assertEquals("Page 5 of 7", PdfStamper.pageNumberText(spec, 1, 4))
        assertEquals("Page 7 of 7", PdfStamper.pageNumberText(spec, 3, 4))
        assertEquals("iv", PdfStamper.pageNumberText(StampSpec(numberFormat = NumberFormat.ROMAN), 3, 9))
        assertEquals("2/9", PdfStamper.pageNumberText(StampSpec(numberFormat = NumberFormat.SLASH), 1, 9))
        assertEquals("ACME-000042", PdfStamper.bates(StampSpec(batesPrefix = "ACME-", batesDigits = 6, batesStart = 41), 1))
    }

    @Test fun stampsAreExtractableAndPositioned() {
        val src = PdfFixtures.create(tmp.newFile("s.pdf"), pages = 3)
        val spec = StampSpec(numberFormat = NumberFormat.PAGE_OF, header = StampItem(true, "{filename} - {date}", StampPosition.TOP_CENTER), fileName = "plan.pdf", date = "1 Jan 2027",
            batesEnabled = true, batesPrefix = "B-", batesDigits = 4, skipFirst = true)
        val out = run(src) { PdfStamper.apply(it, spec) }
        PdfOps.load(out).use { d ->
            val st = com.tom_roush.pdfbox.text.PDFTextStripper()
            st.startPage = 2; st.endPage = 2
            val t2 = st.getText(d)
            assertTrue(t2, t2.contains("Page 1 of 2") && t2.contains("plan.pdf - 1 Jan 2027") && t2.contains("B-0002"))
            st.startPage = 1; st.endPage = 1
            val t1 = st.getText(d)
            assertFalse(t1.contains("Page ")); assertTrue(t1.contains("B-0001"))
        }
        // page number sits at the bottom centre: ink in the bottom strip, none mid-page
        val b = RenderProbe.render(out, 1)
        fun ink(y0: Int, y1: Int): Int { var n = 0; for (y in y0 until y1) for (x in 0 until b.width) if ((b.getPixel(x, y) and 0xFFFFFF) != 0xFFFFFF) n++; return n }
        assertTrue(ink(b.height - 60, b.height) > 20); assertEquals(0, ink(b.height / 3, b.height * 2 / 3))
    }

    @Test fun nothingStampedWhenAllItemsOff() {
        val src = PdfFixtures.create(tmp.newFile("n.pdf"))
        var n = -1
        run(src) { n = PdfStamper.apply(it, StampSpec(pageNumber = StampItem(false, "", 4))) }
        assertEquals(0, n)
    }
}
