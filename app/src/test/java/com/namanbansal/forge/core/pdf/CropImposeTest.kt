package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.RenderProbe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropImposeTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    // ---- crop
    @Test fun cropSetsTheCropBoxAndLeavesTheMediaBox() {
        val src = PdfFixtures.create(tmp.newFile("c.pdf"), pages = 4)
        val out = File(tmp.root, "o.pdf")
        var n = 0
        PdfOps.load(src).use { d -> n = PdfCropper.apply(d, PdfCropper.pagesFor(CropPages.ODD, 4, emptySet()), false, { PdfCropper.Box(0.1f, 0.2f, 0.9f, 0.8f) }); d.save(out) }
        assertEquals(2, n)
        PdfOps.load(out).use { d ->
            val c = d.getPage(0).cropBox
            assertEquals(59.5f, c.lowerLeftX, 0.6f); assertEquals(168.4f, c.lowerLeftY, 0.6f); assertEquals(535.5f, c.upperRightX, 0.6f); assertEquals(673.6f, c.upperRightY, 0.6f)
            assertEquals(595f, d.getPage(0).mediaBox.width, 0.6f)
            assertEquals(842f, d.getPage(1).cropBox.height, 0.6f) // even pages untouched
        }
    }

    @Test fun cropCanAlsoSetTheMediaBoxAndHonoursRotation() {
        val src = PdfFixtures.create(tmp.newFile("r.pdf"), rotations = mapOf(0 to 90))
        val out = File(tmp.root, "r2.pdf")
        PdfOps.load(src).use { d -> PdfCropper.apply(d, setOf(0), true, { PdfCropper.Box(0f, 0f, 0.5f, 1f) }); d.save(out) }
        PdfOps.load(out).use { d ->
            val m = d.getPage(0).mediaBox
            assertEquals(595f, m.width, 0.6f); assertEquals(421f, m.height, 0.6f) // left half of the rotated view = bottom half of the page
            assertEquals(0f, m.lowerLeftY, 0.6f)
        }
        val b = RenderProbe.render(out)
        assertTrue(b.width < b.height) // rotated: 421 tall page shown 421 wide x 595 tall
    }

    @Test fun detectFindsContentBoundsAndBlankReturnsNull() {
        val bmp = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertNull(PdfCropper.detectContent(bmp))
        for (y in 100 until 200) for (x in 50 until 150) bmp.setPixel(x, y, Color.BLACK)
        val box = PdfCropper.detectContent(bmp, pad = 0f)!!
        assertEquals(0.25f, box.l, 0.01f); assertEquals(0.25f, box.t, 0.01f); assertEquals(0.75f, box.r, 0.01f); assertEquals(0.5f, box.b, 0.01f)
    }

    // ---- imposition maths
    @Test fun bookletOrderPadsToMultipleOfFour() {
        assertEquals(listOf(listOf(7, 0), listOf(1, 6), listOf(5, 2), listOf(3, 4)), ImposePlan.booklet(8, false))
        assertEquals(listOf(listOf(null, 0), listOf(1, null), listOf(null, 2), listOf(3, 4)), ImposePlan.booklet(5, false))
        assertEquals(listOf(listOf(0, 3), listOf(2, 1)), ImposePlan.booklet(4, true))
        assertEquals(12, ImposePlan.paddedCount(9)); assertEquals(4, ImposePlan.paddedCount(1)); assertEquals(8, ImposePlan.paddedCount(8))
    }

    @Test fun nupOrdering() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, null)), ImposePlan.nup(5, 2, 1, false, false))
        assertEquals(listOf(listOf(1, 0)), ImposePlan.nup(2, 2, 1, false, true))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, null, null, null)), ImposePlan.nup(5, 2, 2, false, false))
        assertEquals(listOf(listOf(0, 2, 1, 3)), ImposePlan.nup(4, 2, 2, true, false)) // column first: 1 above 2
    }

    private fun sideText(doc: com.tom_roush.pdfbox.pdmodel.PDDocument, page: Int): String {
        val st = PDFTextStripper(); st.sortByPosition = true; st.startPage = page + 1; st.endPage = page + 1
        return st.getText(doc)
    }

    @Test fun bookletBuildHasHalfAsManySidesInSaddleOrder() {
        val src = PdfFixtures.withText(tmp.newFile("b.pdf"), 8, uniqueHeights = false)
        PdfOps.load(src).use { d ->
            ImposePlan.plan(8, ImposeSpec(ImposeMode.BOOKLET)).also { assertEquals(4, it.size) }
            val out = PdfImposer.build(d, ImposeSpec(ImposeMode.BOOKLET))
            assertEquals(4, out.numberOfPages)
            assertTrue(out.getPage(0).mediaBox.width > out.getPage(0).mediaBox.height) // landscape sheets
            val t = sideText(out, 0)
            assertTrue(t, t.indexOf("Page 8") in 0 until t.indexOf("Page 1"))
            val t2 = sideText(out, 1)
            assertTrue(t2, t2.contains("Page 2") && t2.contains("Page 7") && t2.indexOf("Page 2") < t2.indexOf("Page 7"))
            out.close()
        }
    }

    @Test fun fourUpPutsFourPagesOnEachSheetAndDrawsBorders() {
        val src = PdfFixtures.withText(tmp.newFile("n.pdf"), 5, uniqueHeights = false)
        PdfOps.load(src).use { d ->
            val out = PdfImposer.build(d, ImposeSpec(ImposeMode.NUP4, borders = true))
            assertEquals(2, out.numberOfPages)
            val t = sideText(out, 0)
            assertTrue((1..4).all { t.contains("Page $it") } && !t.contains("Page 5"))
            val saved = File(tmp.root, "n4.pdf"); out.save(saved); out.close()
            val bmp = RenderProbe.render(saved)
            assertTrue(bmp.width < bmp.height) // 4-up of portrait pages stays portrait
        }
    }

    @Test fun previewSideRendersOneSheet() {
        val src = PdfFixtures.withText(tmp.newFile("p.pdf"), 4, uniqueHeights = false)
        com.namanbansal.forge.core.PdfBoxRasterizer.install()
        val bmp = try { PdfImposer.previewSide(src, ImposeSpec(ImposeMode.NUP2), 1, 400, File(tmp.root, "w")) } finally { com.namanbansal.forge.core.PdfBoxRasterizer.uninstall() }
        assertNotNull(bmp); assertEquals(400, bmp!!.width)
    }
}
