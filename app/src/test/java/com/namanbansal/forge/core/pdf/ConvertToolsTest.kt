package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.core.image.ImgFormat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
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
class ConvertToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    // ---- PDF -> images
    @Test fun pageCountAndDimensionsPerDpi() {
        val src = PdfFixtures.withText(tmp.newFile("p.pdf"), 3, uniqueHeights = false)
        val r = PdfBoxRasterizer(src)
        try {
            assertEquals(3, r.pageCount)
            val (wp, hp) = r.pageSize(0)!!
            for (dpi in listOf(72, 150, 300)) {
                val bmp = PdfPageExporter.render(r, 1, dpi)
                val (w, h) = PdfPageExporter.pixelSize(wp, hp, dpi)
                assertEquals("w@$dpi", w, bmp.width); assertEquals("h@$dpi", h, bmp.height)
            }
            assertEquals(595 to 841, PdfPageExporter.pixelSize(595, 841, 72))
            assertEquals(1240 to 1754, PdfPageExporter.pixelSize(595, 842, 150))
        } finally { r.close() }
    }

    @Test fun encodesEveryFormatAndNamesFilesInOrder() {
        val bmp = ImageFixtures.photo(120, 80)
        for (f in PdfPageExporter.formats) {
            val bos = ByteArrayOutputStream(); PdfPageExporter.encode(bmp, f, 80, bos)
            assertTrue("${f.label} empty", bos.size() > 100)
        }
        assertEquals("report_page-07.png", PdfPageExporter.fileName("report", 6, 12, ImgFormat.PNG))
        assertEquals("report_page-007.jpg", PdfPageExporter.fileName("report", 6, 120, ImgFormat.JPEG))
        assertTrue(PdfPageExporter.fits(595, 842, 300)); assertTrue(!PdfPageExporter.fits(5000, 5000, 600))
        val est = PdfPageExporter.estimateBytes(ImageFixtures.photo(100, 100), 400, 400, ImgFormat.JPEG, 80)
        assertTrue(est != null && est > 0)
    }

    // ---- images -> PDF
    private fun build(spec: ImagesPdfSpec, bitmaps: List<Bitmap>): File {
        val out = File(tmp.root, "o-${System.nanoTime()}.pdf")
        out.outputStream().use { ImagesToPdf.build(bitmaps.size, spec, it) { i -> bitmaps[i].copy(Bitmap.Config.ARGB_8888, false) } }
        return out
    }

    @Test fun pageSizesFollowModeAndOrientation() {
        val portrait = ImageFixtures.photo(300, 400); val landscape = ImageFixtures.photo(400, 300)
        val a4 = build(ImagesPdfSpec(ImagePageSize.A4), listOf(portrait, landscape))
        PdfOps.load(a4).use { d ->
            assertEquals(2, d.numberOfPages)
            assertEquals(595.3f, d.getPage(0).mediaBox.width, 0.5f); assertEquals(841.9f, d.getPage(0).mediaBox.height, 0.5f)
            assertEquals(841.9f, d.getPage(1).mediaBox.width, 0.5f); assertEquals(595.3f, d.getPage(1).mediaBox.height, 0.5f)
        }
        val letter = build(ImagesPdfSpec(ImagePageSize.LETTER, ImageOrientation.LANDSCAPE), listOf(portrait))
        PdfOps.load(letter).use { assertEquals(792f, it.getPage(0).mediaBox.width, 0.5f); assertEquals(612f, it.getPage(0).mediaBox.height, 0.5f) }
        val fit = build(ImagesPdfSpec(ImagePageSize.FIT), listOf(ImageFixtures.photo(2480, 3508).let { Bitmap.createScaledBitmap(it, 620, 877, true) }))
        PdfOps.load(fit).use { assertEquals(148.8f, it.getPage(0).mediaBox.width, 1f) }
        val custom = build(ImagesPdfSpec(ImagePageSize.CUSTOM, ImageOrientation.PORTRAIT, customWidthMm = 100f, customHeightMm = 150f), listOf(landscape))
        PdfOps.load(custom).use { assertEquals(283.5f, it.getPage(0).mediaBox.width, 1f); assertEquals(425.2f, it.getPage(0).mediaBox.height, 1f) }
    }

    @Test fun placementStaysInsideTheMargins() {
        val p = ImagesToPdf.placement(595f, 842f, 400, 300, 36f)
        assertTrue(p[0] >= 36f - 0.01f && p[0] + p[2] <= 595f - 36f + 0.01f && p[1] >= 36f && p[1] + p[3] <= 842f - 36f + 0.01f)
        assertEquals(400f / 300f, p[2] / p[3], 0.01f)
    }

    @Test fun exifOrientationIsRespectedWhenDecoding() {
        // A 40x20 JPEG tagged "rotate 90": upright it is 20 wide and 40 tall, so it must land on a portrait page.
        val f = tmp.newFile("rot.jpg")
        f.outputStream().use { ImageFixtures.photo(40, 20).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val upright = ImageIO.decode("image/jpeg", { f.inputStream() })
        assertEquals(20, upright.width); assertEquals(40, upright.height)
        val pdf = build(ImagesPdfSpec(ImagePageSize.A4), listOf(upright))
        PdfOps.load(pdf).use { assertTrue(it.getPage(0).mediaBox.height > it.getPage(0).mediaBox.width) }
    }

    @Test fun transparentPicturesGetAWhiteBackground() {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val pdf = build(ImagesPdfSpec(ImagePageSize.FIT, marginPt = 0f), listOf(bmp))
        val px = com.namanbansal.forge.core.RenderProbe.pixel(com.namanbansal.forge.core.RenderProbe.render(pdf), 0.5f, 0.5f)
        assertTrue(com.namanbansal.forge.core.RenderProbe.r(px) > 240)
    }

    @Test fun previewPageMatchesPageProportions() {
        val pv = ImagesToPdf.previewPage(ImagesPdfSpec(ImagePageSize.A4), ImageFixtures.photo(300, 400), 300)
        assertEquals(300, pv.width); assertEquals(424, pv.height, )
    }
}
