package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfRotatorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun rotate(src: File, rotations: Map<Int, Int>, progress: MutableList<Float> = mutableListOf()): File {
        val out = File(tmp.root, "out-${System.nanoTime()}.pdf")
        out.outputStream().use { PdfRotator.rotate(src, it, rotations, onProgress = { p -> progress += p }) }
        return out
    }

    @Test fun singlePageRotatesRightLeftAnd180() {
        val src = PdfFixtures.create(tmp.newFile("one.pdf"))
        assertEquals(listOf(90), PdfFixtures.pageRotations(rotate(src, mapOf(0 to 90))))
        assertEquals(listOf(270), PdfFixtures.pageRotations(rotate(src, mapOf(0 to -90))))
        assertEquals(listOf(180), PdfFixtures.pageRotations(rotate(src, mapOf(0 to 180))))
        assertEquals(listOf(0), PdfFixtures.pageRotations(rotate(src, mapOf(0 to 360))))
    }

    @Test fun rotationIsLosslessMediaBoxAndPageCountUnchanged() {
        val src = PdfFixtures.create(tmp.newFile("a.pdf"), pages = 3, sizes = listOf(PDRectangle.A4, PDRectangle.LETTER, PDRectangle.A3))
        val out = rotate(src, mapOf(1 to 90))
        PDDocument.load(out).use { d ->
            assertEquals(3, d.numberOfPages)
            assertEquals(PDRectangle.LETTER.width, d.getPage(1).mediaBox.width, 0.01f)
            assertEquals(PDRectangle.A3.height, d.getPage(2).mediaBox.height, 0.01f)
        }
    }

    @Test fun mixedSizesAndExistingRotationAddUp() {
        val src = PdfFixtures.create(
            tmp.newFile("mixed.pdf"), pages = 4,
            sizes = listOf(PDRectangle.A4, PDRectangle.LETTER, PDRectangle(900f, 300f), PDRectangle.A5),
            rotations = mapOf(1 to 90, 2 to 270),
        )
        val out = rotate(src, mapOf(0 to 90, 1 to 180, 2 to 90))
        assertEquals(listOf(90, 270, 0, 0), PdfFixtures.pageRotations(out))
    }

    @Test fun threeHundredPagesWithMonotonicProgress() {
        val src = PdfFixtures.create(tmp.newFile("big.pdf"), pages = 300)
        val progress = mutableListOf<Float>()
        val out = rotate(src, (0 until 300).associateWith { 90 }, progress)
        assertEquals(300, PdfFixtures.pageRotations(out).size)
        assertTrue(PdfFixtures.pageRotations(out).all { it == 90 })
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)
    }

    @Test fun cancelMidwayStopsAndWritesNothing() {
        val src = PdfFixtures.create(tmp.newFile("c.pdf"), pages = 50)
        val sink = ByteArrayOutputStream()
        var pagesSeen = 0
        try {
            PdfRotator.rotate(src, sink, (0 until 50).associateWith { 90 }, checkCancelled = { if (++pagesSeen == 10) throw CancellationException("user") })
            fail("should have been cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals("cancelled before the 11th page", 10, pagesSeen)
        assertEquals("nothing may be written when cancelled", 0, sink.size())
    }

    @Test fun rejectsBadInput() {
        val src = PdfFixtures.create(tmp.newFile("b.pdf"), pages = 2)
        val sink = ByteArrayOutputStream()
        try { PdfRotator.rotate(src, sink, mapOf(5 to 90)); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("doesn't exist")) }
        try { PdfRotator.rotate(src, sink, mapOf(0 to 45)); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("90")) }
    }

    @Test fun corruptFileGivesFriendlyError() {
        val bad = PdfFixtures.corrupt(tmp.newFile("bad.pdf"))
        try { PdfRotator.rotate(bad, ByteArrayOutputStream(), mapOf(0 to 90)); fail() } catch (e: ToolException) { assertTrue(e.message!!.startsWith("Could not rotate")) }
    }

    @Test fun normalize() {
        assertEquals(270, PdfRotator.normalize(-90))
        assertEquals(0, PdfRotator.normalize(720))
        assertEquals(90, PdfRotator.normalize(450))
    }

    @Test fun encryptedSourceIsRotatableAfterDecryptingViaLoader() = runTest {
        val enc = PdfFixtures.create(tmp.newFile("enc.pdf"), pages = 3, userPassword = "s3cret")
        val ready = PdfLoader.open(enc, tmp.newFolder("work"), "s3cret") as PdfOpenResult.Ready
        assertTrue(ready.source.wasEncrypted)
        val out = rotate(ready.source.file, mapOf(0 to 90, 2 to 90))
        assertEquals(listOf(90, 0, 90), PdfFixtures.pageRotations(out))
    }
}
