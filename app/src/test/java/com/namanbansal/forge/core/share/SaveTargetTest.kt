package com.namanbansal.forge.core.share

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.DocumentIo
import com.namanbansal.forge.core.DocumentSink
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.WriteResult
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotSaver
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.namanbansal.forge.core.pdf.annot.SaveTarget
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Edits on a file that can't be overwritten: the save asks where to go, and each answer does what it says. */
@RunWith(RobolectricTestRunner::class)
class SaveTargetTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Io(val writable: Boolean, var current: ByteArray) : DocumentIo {
        var overwrites = 0
        override fun takeWritePermission(uri: Uri) = writable
        override fun probeWrite(uri: Uri) = writable
        override fun openInput(uri: Uri): InputStream? = ByteArrayInputStream(current)
        override fun openOverwrite(uri: Uri): OutputStream { overwrites++; return object : ByteArrayOutputStream() { override fun close() { super.close(); current = toByteArray() } } }
    }

    private class Sink : DocumentSink {
        val copies = ArrayList<ByteArray>()
        override suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream) = SavedOutput(Uri.parse("backup://b"), "b.pdf", 1, "application/pdf")
        override suspend fun saveCopy(originalName: String, content: () -> InputStream): SavedOutput {
            copies += content().readBytes(); return SavedOutput(Uri.parse("content://media/external/downloads/9"), "${originalName.removeSuffix(".pdf")}_edited.pdf", 1, "application/pdf")
        }
    }

    private val hl = Annot("a", 0, AnnotType.HIGHLIGHT, AnnotPalette.YELLOW, quads = listOf(NormRect(0.1f, 0.1f, 0.5f, 0.13f)))
    private fun annotCount(bytes: ByteArray) = PDDocument.load(bytes).use { PdfAnnotIo.read(it).size }

    @Test fun downloadsTargetLeavesTheOriginalUntouchedEvenWhenItIsWritable() = runBlocking {
        val src = PdfFixtures.create(tmp.newFile("o.pdf"), 1)
        val io = Io(writable = true, current = src.readBytes()); val sink = Sink()
        val out = AnnotSaver(DocumentWriter(io, sink), tmp.newFolder())
            .save(Uri.parse("content://x/1"), "doc.pdf", src, null, emptyList(), listOf(hl), target = SaveTarget.Downloads("Saved to Downloads/Forge."))
        assertEquals(0, io.overwrites)
        assertTrue(out.result is WriteResult.SavedCopy)
        assertEquals(1, annotCount(sink.copies.single()))
        assertTrue(io.current.contentEquals(src.readBytes()))
    }

    @Test fun chosenTargetWritesToThePickedUriAndNothingElse() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val src = PdfFixtures.create(tmp.newFile("o.pdf"), 1)
        val io = Io(writable = false, current = src.readBytes()); val sink = Sink()
        val dest = File(tmp.root, "chosen.pdf")
        val out = AnnotSaver(DocumentWriter(io, sink), tmp.newFolder(), OutputSaver(app))
            .save(Uri.parse("content://x/1"), "doc.pdf", src, null, emptyList(), listOf(hl), target = SaveTarget.Chosen(Uri.fromFile(dest), "chosen.pdf"))
        assertEquals(0, io.overwrites); assertTrue(sink.copies.isEmpty())
        assertEquals("Saved to chosen.pdf", out.message)
        assertEquals(1, annotCount(dest.readBytes()))
        assertEquals(Uri.fromFile(dest), out.copy!!.uri)
    }

    @Test fun renderBuildsTheEditedFileWithoutStoringAnything() = runBlocking {
        val src = PdfFixtures.create(tmp.newFile("o.pdf"), 1)
        val io = Io(true, src.readBytes()); val sink = Sink()
        val work = tmp.newFolder()
        val r = AnnotSaver(DocumentWriter(io, sink), work).render(src, null, emptyList(), listOf(hl))
        assertEquals(1, annotCount(r.toStore.readBytes()))
        assertTrue(sink.copies.isEmpty()); assertEquals(0, io.overwrites)
        r.discard()
        assertFalse(r.reload.exists())
    }

    @Test fun canOverwriteAsksTheProviderWithoutWriting() {
        val w = { ok: Boolean -> DocumentWriter(Io(ok, ByteArray(0)), Sink()) }
        assertTrue(w(true).canOverwrite(Uri.parse("content://x/1")))
        assertFalse(w(false).canOverwrite(Uri.parse("content://x/1")))
    }
}
