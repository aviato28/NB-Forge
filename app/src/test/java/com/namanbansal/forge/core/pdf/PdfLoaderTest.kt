package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    @Test fun plainPdfOpensAsIs() = runTest {
        val f = PdfFixtures.create(tmp.newFile("p.pdf"), pages = 7)
        val r = PdfLoader.open(f, tmp.newFolder(), null) as PdfOpenResult.Ready
        assertEquals(7, r.source.pageCount)
        assertFalse(r.source.wasEncrypted)
        assertEquals(f, r.source.file)
    }

    @Test fun encryptedNeedsPasswordThenWrongThenRight() = runTest {
        val f = PdfFixtures.create(tmp.newFile("e.pdf"), pages = 2, userPassword = "open-sesame")
        val work = tmp.newFolder()
        assertTrue(PdfLoader.open(f, work, null) is PdfOpenResult.NeedsPassword)
        assertTrue(PdfLoader.open(f, work, "") is PdfOpenResult.NeedsPassword)
        assertTrue(PdfLoader.open(f, work, "nope") is PdfOpenResult.WrongPassword)
        val ok = PdfLoader.open(f, work, "open-sesame") as PdfOpenResult.Ready
        assertTrue(ok.source.wasEncrypted)
        assertEquals(2, ok.source.pageCount)
        // The working copy is genuinely unencrypted: loads with no password.
        PDDocument.load(ok.source.file).use { assertFalse(it.isEncrypted) }
    }

    @Test fun corruptBytesAreReportedNotThrown() = runTest {
        val r = PdfLoader.open(PdfFixtures.corrupt(tmp.newFile("c.pdf")), tmp.newFolder(), null)
        assertTrue(r is PdfOpenResult.Unreadable)
    }

    @Test fun emptyFileIsUnreadable() = runTest {
        assertTrue(PdfLoader.open(tmp.newFile("empty.pdf"), tmp.newFolder(), null) is PdfOpenResult.Unreadable)
    }

    @Test fun zeroPagePdfIsUnreadable() = runTest {
        val f = tmp.newFile("zero.pdf")
        PDDocument().use { it.save(f) }
        val r = PdfLoader.open(f, tmp.newFolder(), null)
        assertTrue(r is PdfOpenResult.Unreadable)
    }

    @Test fun copyStreamWritesAPrivateCopyAndCleansUpOnFailure() {
        val dir = tmp.newFolder()
        val copy = PdfLoader.copyStream("hello".byteInputStream(), dir)
        assertEquals("hello", copy.readText())
        val failing = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("boom") }
        try { PdfLoader.copyStream(failing, dir) } catch (_: java.io.IOException) {}
        assertEquals("failed copy must not leave a partial file", 1, dir.listFiles()!!.size)
    }
}
