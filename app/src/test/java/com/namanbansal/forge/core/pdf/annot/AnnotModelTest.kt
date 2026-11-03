package com.namanbansal.forge.core.pdf.annot

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.DocumentIo
import com.namanbansal.forge.core.DocumentSink
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.WriteResult
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class AnnotModelTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun hl(id: String, page: Int = 0) = Annot(id, page, AnnotType.HIGHLIGHT, AnnotPalette.YELLOW, quads = listOf(NormRect(0.1f, 0.1f, 0.5f, 0.13f)))
    private fun pen(id: String, page: Int = 0) = Annot(id, page, AnnotType.INK, AnnotPalette.BLUE, strokes = listOf(listOf(NormPt(0.1f, 0.5f), NormPt(0.5f, 0.5f))), width = 0.006f)

    // ---- undo / redo ----
    @Test fun undoRedoWalkTheHistoryAndRedoIsClearedByANewEdit() {
        val s = AnnotSession()
        s.add(hl("a")); s.add(hl("b")); s.replace(hl("a").copy(color = AnnotPalette.RED)); s.remove(listOf("b"))
        assertEquals(listOf("a"), s.current.map { it.id })
        s.undo(); assertEquals(listOf("a", "b"), s.current.map { it.id })
        s.undo(); assertEquals(AnnotPalette.YELLOW, s.find("a")!!.color)
        s.redo(); assertEquals(AnnotPalette.RED, s.find("a")!!.color)
        assertTrue(s.canRedo.value)
        s.add(pen("c")); assertFalse(s.canRedo.value)
        repeat(10) { s.undo() }
        assertTrue(s.current.isEmpty()); assertFalse(s.canUndo.value)
    }

    @Test fun historyKeepsAtLeastFiftySteps() {
        val s = AnnotSession()
        repeat(120) { s.add(hl("x$it")) }
        repeat(100) { s.undo() }
        assertEquals(20, s.current.size)
        assertTrue(AnnotSession(maxHistory = 100).let { x -> repeat(60) { x.add(hl("y$it")) }; repeat(60) { x.undo() }; x.current.isEmpty() })
    }

    @Test fun dirtyFollowsTheBaselineSoUndoingEverythingIsClean() {
        val base = listOf(hl("e").copy(ref = ExistingRef(0, 0)))
        val s = AnnotSession(base)
        assertFalse(s.dirty.value)
        s.add(pen("n")); assertTrue(s.dirty.value)
        s.undo(); assertFalse(s.dirty.value)
        s.remove(listOf("e")); assertTrue(s.dirty.value)
        s.reset(emptyList()); assertFalse(s.dirty.value); assertFalse(s.canUndo.value)
    }

    // ---- eraser / hit-testing ----
    @Test fun eraserTouchesOnlyWhatItCrosses() {
        val list = listOf(hl("h"), pen("p"), hl("far").copy(quads = listOf(NormRect(0.1f, 0.8f, 0.5f, 0.83f))))
        val aspect = 0.707f
        assertEquals(listOf("h"), AnnotHit.erased(list, 0, 0.3f, 0.05f, 0.3f, 0.12f, aspect))
        assertEquals(listOf("p"), AnnotHit.erased(list, 0, 0.3f, 0.45f, 0.3f, 0.52f, aspect))
        assertEquals(setOf("h", "p"), AnnotHit.erased(list, 0, 0.3f, 0.05f, 0.3f, 0.52f, aspect).toSet())
        assertTrue(AnnotHit.erased(list, 0, 0.3f, 0.3f, 0.35f, 0.35f, aspect).isEmpty())
        assertTrue(AnnotHit.erased(list, 1, 0.3f, 0.05f, 0.3f, 0.52f, aspect).isEmpty()) // other page
    }

    @Test fun topmostPicksTheLastDrawnAndNotesHaveAnIconBox() {
        val a = hl("a"); val b = hl("b")
        assertEquals("b", AnnotHit.topmost(listOf(a, b), 0, 0.3f, 0.11f, 0.707f)!!.id)
        assertNull(AnnotHit.topmost(listOf(a, b), 0, 0.9f, 0.9f, 0.707f))
        val n = Annot("n", 0, AnnotType.NOTE, AnnotPalette.YELLOW, anchor = NormPt(0.8f, 0.1f))
        assertNotNull(AnnotHit.topmost(listOf(n), 0, 0.82f, 0.11f, 0.707f))
    }

    @Test fun smoothingStaysThroughTheEndpointsAndThinningDropsJitter() {
        val pts = listOf(NormPt(0f, 0f), NormPt(0.1f, 0.1f), NormPt(0.2f, 0f), NormPt(0.3f, 0.1f))
        val sm = StrokeMath.smoothed(pts)
        assertEquals(pts.first(), sm.first()); assertEquals(pts.last(), sm.last()); assertTrue(sm.size > pts.size)
        val dense = (0..100).map { NormPt(it * 0.0001f, 0f) } + NormPt(0.5f, 0f)
        val thin = StrokeMath.thin(dense, 0.707f)
        assertTrue(thin.size < 20); assertEquals(dense.last(), thin.last())
    }

    // ---- text snapping ----
    private fun line(text: String, top: Float, left: Float = 0.1f, cw: Float = 0.03f): List<NormRect?> =
        text.mapIndexed { i, ch -> if (ch == ' ') null else NormRect(left + i * cw, top, left + (i + 1) * cw, top + 0.02f) }

    @Test fun dragAcrossTwoLinesSnapsToOneQuadPerLine() {
        val l1 = "hello big world"; val l2 = "second line here"
        val pt = PageText(l1 + "\n" + l2, line(l1, 0.10f) + listOf(null) + line(l2, 0.14f))
        // drag from inside "big" (line 1) to inside "line" (line 2)
        val x0 = 0.1f + 7.5f * 0.03f; val x1 = 0.1f + 8.5f * 0.03f
        val q = TextSnap.quadsBetween(pt, x0, 0.11f, x1, 0.15f)!!
        assertEquals(2, q.size)
        assertEquals(0.1f + 7 * 0.03f, q[0].left, 1e-4f)            // starts at the character under the first point
        assertEquals(0.1f + l1.length * 0.03f, q[0].right, 1e-4f)   // runs to the end of line one
        assertEquals(0.1f, q[1].left, 1e-4f); assertEquals(0.1f + 9 * 0.03f, q[1].right, 1e-4f)
        assertTrue(q[0].bottom <= q[1].top + 0.001f)
        // dragging backwards gives the same boxes
        assertEquals(q, TextSnap.quadsBetween(pt, x1, 0.15f, x0, 0.11f))
        // a drag that never touches text selects nothing
        assertNull(TextSnap.quadsBetween(pt, 0.9f, 0.9f, 0.95f, 0.95f))
    }

    // ---- persistence ----
    @Test fun pendingChangesSurviveJsonAndTheStore() {
        val list = listOf(
            hl("a"), pen("b"),
            Annot("n", 1, AnnotType.NOTE, AnnotPalette.EMBER, anchor = NormPt(0.3f, 0.4f), text = "line1\nline \"two\""),
            hl("e").copy(ref = ExistingRef(2, 5), opacity = 0.5f),
        )
        assertEquals(list, AnnotJson.decode(AnnotJson.encode(list)))
        val store = PendingStore(tmp.newFolder())
        assertNull(store.load("doc1"))
        store.save("doc1", list)
        assertEquals(list, store.load("doc1"))
        store.clear("doc1"); assertNull(store.load("doc1"))
        assertTrue(AnnotJson.decode("{not json").isEmpty())
    }

    // ---- save path through DocumentWriter ----
    private class FakeIo(var writable: Boolean, val original: ByteArray) : DocumentIo {
        var current = original
        override fun takeWritePermission(uri: Uri) = writable
        override fun probeWrite(uri: Uri) = writable
        override fun openInput(uri: Uri): InputStream? = ByteArrayInputStream(if (uri.scheme == "backup") backups[uri.toString()] ?: return null else current)
        override fun openOverwrite(uri: Uri): OutputStream = object : ByteArrayOutputStream() { override fun close() { super.close(); current = toByteArray() } }
        val backups = HashMap<String, ByteArray>()
    }

    private class FakeSink(val io: FakeIo) : DocumentSink {
        val saved = ArrayList<String>()
        override suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream): SavedOutput {
            val u = Uri.parse("backup://b/${saved.size}"); io.backups[u.toString()] = content().readBytes(); saved += "backup"; return SavedOutput(u, "b.pdf", 1, "application/pdf")
        }
        override suspend fun saveCopy(originalName: String, content: () -> InputStream): SavedOutput {
            content().readBytes(); saved += "copy"; return SavedOutput(Uri.parse("copy://c"), "c.pdf", 1, "application/pdf")
        }
    }

    private fun saveWith(writable: Boolean): Triple<SaveOutcome, FakeIo, FakeSink> {
        val src = PdfFixtures.create(tmp.newFile("o${System.nanoTime()}.pdf"), pages = 1)
        val io = FakeIo(writable, src.readBytes()); val sink = FakeSink(io)
        val saver = AnnotSaver(DocumentWriter(io, sink), tmp.newFolder())
        val out = runBlocking { saver.save(Uri.parse("content://x/1"), "doc.pdf", src, null, emptyList(), listOf(hl("a"), pen("b"))) }
        return Triple(out, io, sink)
    }

    @Test fun writableOriginalIsBackedUpThenOverwritten() {
        val (out, io, sink) = saveWith(true)
        assertTrue(out.result is WriteResult.Overwritten)
        assertEquals(listOf("backup"), sink.saved)
        assertEquals("Saved · backup in Downloads/Forge/Backups", out.message)
        assertFalse(io.current.contentEquals(io.original)) // original replaced
        assertTrue(io.backups.values.single().contentEquals(io.original)) // backup is the untouched original
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(io.current).use { assertEquals(2, PdfAnnotIo.read(it).size) }
        assertEquals(2, com.tom_roush.pdfbox.pdmodel.PDDocument.load(out.reloadFile).use { PdfAnnotIo.read(it).size })
    }

    @Test fun readOnlyOriginalIsLeftAloneAndSavedAsACopy() {
        val (out, io, sink) = saveWith(false)
        assertTrue(out.result is WriteResult.SavedCopy)
        assertEquals(listOf("copy"), sink.saved)
        assertEquals("Saved as a copy in Downloads/Forge", out.message)
        assertTrue(io.current.contentEquals(io.original))
        assertTrue(out.reloadFile.exists())
    }
}
