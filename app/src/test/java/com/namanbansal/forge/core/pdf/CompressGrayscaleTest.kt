package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.RenderProbe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSStream
import org.junit.Assert.assertEquals
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
class CompressGrayscaleTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun run(src: File, edit: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit): File {
        val out = File(tmp.root, "o-${System.nanoTime()}.pdf")
        PdfOps.load(src).use { edit(it); it.save(out) }
        return out
    }

    private fun distinctImages(f: File): Int = PdfOps.load(f).use { PdfImagePass.allImages(it).size }

    @Test fun compressShrinksImageHeavyFileAndKeepsText() {
        val src = ImageFixtures.imagePdf(tmp.newFile("img.pdf"), pages = 3)
        val out = run(src) { PdfCompressor.compress(it, CompressOptions(CompressPreset.SCREEN.dpi, CompressPreset.SCREEN.quality)) }
        assertTrue("${out.length()} vs ${src.length()}", out.length() < src.length() / 5)
        assertTrue(RenderProbe.text(out).contains("Page 2"))
        // still renders: the red rectangle survived
        val px = RenderProbe.pixel(RenderProbe.render(out), 420f / 595f, (842f - 675f) / 842f)
        assertTrue(RenderProbe.r(px) > 200 && RenderProbe.g(px) < 60)
    }

    @Test fun presetsOrderBySize() {
        val src = ImageFixtures.imagePdf(tmp.newFile("img2.pdf"), pages = 1, px = 1200, sizePt = 216f)
        val sizes = listOf(CompressPreset.SCREEN, CompressPreset.EBOOK, CompressPreset.PRINT).map { p ->
            run(src) { PdfCompressor.compress(it, CompressOptions(p.dpi, p.quality)) }.length()
        }
        assertTrue(sizes.toString(), sizes[0] < sizes[1] && sizes[1] < sizes[2])
    }

    @Test fun identicalImagesAreMerged() {
        val src = ImageFixtures.imagePdf(tmp.newFile("img3.pdf"), pages = 4, px = 600)
        assertEquals(4, distinctImages(src))
        val out = run(src) { PdfCompressor.compress(it, CompressOptions(150, 70)) }
        assertEquals(1, distinctImages(out))
    }

    @Test fun picturesAlreadyBelowTheTargetAreLeftAlone() {
        val src = ImageFixtures.imagePdf(tmp.newFile("small.pdf"), pages = 1, px = 80, sizePt = 288f) // 20 dpi
        var stats: ImageStats? = null
        run(src) { stats = PdfImagePass.run(it, ImageOptions(targetDpi = 150, quality = 60, reencodeLossless = false)) }
        assertEquals(0, stats!!.recompressed)
    }

    @Test fun estimateIsCloseToTheRealOutput() {
        val src = ImageFixtures.imagePdf(tmp.newFile("est.pdf"), pages = 3)
        val o = CompressOptions(150, 72)
        val est = PdfCompressor.estimate(src, src.length(), o)
        val real = run(src) { PdfCompressor.compress(it, o) }.length()
        assertTrue("est ${est.estimatedBytes} real $real src ${src.length()} img ${est.imageBytes} sampled ${est.sampled} n ${est.images}", est.estimatedBytes > real * 0.5 && est.estimatedBytes < real * 2.0)
        assertTrue(est.estimatedBytes < src.length())
    }

    @Test fun textOnlyFileEstimatesNearOriginalAndStreamsGetCompressed() {
        val src = PdfFixtures.withText(tmp.newFile("t.pdf"), 5)
        val est = PdfCompressor.estimate(src, src.length(), CompressOptions(150, 72))
        assertTrue(est.estimatedBytes >= src.length() / 2)
        val out = run(src) { PdfCompressor.compress(it, CompressOptions(150, 72, stripMetadata = true)) }
        assertTrue(RenderProbe.text(out).contains("Quarterly report"))
        assertTrue(out.length() <= src.length() + 200)
    }

    @Test fun grayscaleMakesPixelsNeutralForVectorsAndPictures() {
        val src = ImageFixtures.imagePdf(tmp.newFile("g.pdf"), pages = 1, px = 400)
        var ops = 0; var stats: ImageStats? = null
        val out = run(src) { val (s, n) = PdfGrayscale.convert(it); stats = s; ops = n }
        assertTrue(ops > 0); assertEquals(1, stats!!.recompressed)
        val bmp = RenderProbe.render(out)
        for ((fx, fy) in listOf(420f / 595f to (842f - 675f) / 842f, 150f / 595f to (842f - 560f) / 842f, 100f / 595f to (842f - 500f) / 842f)) {
            val p = RenderProbe.pixel(bmp, fx, fy)
            assertTrue("rgb=${RenderProbe.r(p)},${RenderProbe.g(p)},${RenderProbe.b(p)}", Math.abs(RenderProbe.r(p) - RenderProbe.g(p)) <= 2 && Math.abs(RenderProbe.g(p) - RenderProbe.b(p)) <= 2)
        }
        assertTrue(RenderProbe.text(out).contains("Page 1"))
    }

    @Test fun operatorRewriteHandlesRgbCmykAndColourSpaces() {
        val src = "q 1 0 0 rg 0 0 1 RG /DeviceCMYK cs 0.2 0.4 0.6 0.1 sc 0 0 10 10 re f Q 0.5 g".toByteArray()
        val (out, n) = PdfGrayscale.rewrite(src, null)
        val s = String(out)
        assertEquals(4, n)
        assertTrue(s, !s.contains(" rg") && !s.contains(" RG") && s.contains("DeviceGray cs") && s.contains("0.5 g"))
        assertEquals(1f, PdfGrayscale.lum(1f, 1f, 1f), 0.001f)
        assertEquals(0f, PdfGrayscale.lumCmyk(0f, 0f, 0f, 1f), 0.001f)
    }
}
