package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfFixtures
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
class ScaleAndCancelTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun cancelAfter(n: Int): TransformCtx { var calls = 0; return TransformCtx({ if (++calls > n) throw CancellationException("cancelled") }, {}) }

    private fun expectCancel(block: () -> Unit) { try { block(); fail("should have been cancelled") } catch (_: CancellationException) {} }

    @Test fun everyLongRunningToolStopsWhenCancelled() {
        val src = ImageFixtures.imagePdf(tmp.newFile("c.pdf"), pages = 4, px = 200)
        fun doc() = PdfOps.load(src)
        expectCancel { doc().use { PdfCompressor.compress(it, CompressOptions(72, 50), cancelAfter(1)) } }
        expectCancel { doc().use { PdfGrayscale.convert(it, ctx = cancelAfter(1)) } }
        expectCancel { doc().use { PdfWatermarker.apply(it, WatermarkSpec(), null, cancelAfter(1)) } }
        expectCancel { doc().use { PdfStamper.apply(it, StampSpec(), null, cancelAfter(1)) } }
        expectCancel { doc().use { PdfCropper.apply(it, (0..3).toSet(), false, { PdfCropper.Box(0.1f, 0.1f, 0.9f, 0.9f) }, cancelAfter(1)) } }
        expectCancel { doc().use { PdfFlattener.flatten(it, true, true, cancelAfter(1)) } }
        expectCancel { doc().use { PdfSanitizer.clean(it, SanitizeKind.entries.toSet(), cancelAfter(1)) } }
        expectCancel { doc().use { PdfLinks.autoLink(it, cancelAfter(1)) } }
        expectCancel { doc().use { HeadingDetector.detect(it, cancelAfter(1)) } }
        expectCancel { doc().use { PdfImposer.build(it, ImposeSpec(ImposeMode.NUP2), null, cancelAfter(0)).close() } }
        expectCancel { File(tmp.root, "o.pdf").outputStream().use { o -> ImagesToPdf.build(4, ImagesPdfSpec(), o, null, cancelAfter(1)) { ImageFixtures.photo(50, 50) } } }
    }

    @Test fun threeHundredImagePagesCompressWithoutRunningOutOfMemory() {
        // 300 pages, each with its own picture (about 17 MB of lossless data in total): processed page by page through temp-file memory.
        val f = File(tmp.root, "300.pdf")
        PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (i in 0 until 300) {
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                val img = LosslessFactory.createFromImage(doc, ImageFixtures.photo(220, 220, i.toLong()))
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 50f, 400f, 150f, 150f) }
            }
            doc.save(f)
        }
        val out = File(tmp.root, "300-out.pdf")
        var progress = 0f
        PdfOps.load(f).use { d ->
            val stats = PdfCompressor.compress(d, CompressOptions(72, 55), TransformCtx({}, { progress = it }))
            assertEquals(300, stats.images)
            d.save(out)
        }
        assertTrue("${out.length()} vs ${f.length()}", out.length() < f.length() / 3)
        assertTrue(progress > 0.5f)
        PdfOps.load(out).use { assertEquals(300, it.numberOfPages) }
    }
}
