package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
class PageEditorTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun PageEditor.order() = state().pages.map { it.source }

    @Test fun startsCleanAndReorders() {
        val e = PageEditor(5)
        assertFalse(e.state().dirty)
        assertEquals(listOf(0, 1, 2, 3, 4), e.order())
        e.move(0, 3)
        assertEquals(listOf(1, 2, 3, 0, 4), e.order())
        assertTrue(e.state().dirty)
        assertTrue(e.state().canUndo)
    }

    @Test fun deleteDuplicateRotateAndUndoRedo() {
        val e = PageEditor(4)
        e.toggle(1); e.toggle(2)
        assertEquals(2, e.deleteSelected())
        assertEquals(listOf(0, 3), e.order())
        assertTrue(e.state().selected.isEmpty())
        e.undo()
        assertEquals(listOf(0, 1, 2, 3), e.order())
        e.redo()
        assertEquals(listOf(0, 3), e.order())
        e.undo()

        e.toggle(2)
        assertEquals(1, e.duplicateSelected())
        assertEquals(listOf(0, 1, 2, 2, 3), e.order())
        val ids = e.state().pages.map { it.id }
        assertEquals(5, ids.toSet().size) // copies get new ids
        assertEquals(setOf(ids[3]), e.state().selected) // the copy is selected
        e.rotateSelected(90); e.rotateSelected(-180)
        assertEquals(270, e.state().pages[3].rotation)
        assertEquals(0, e.state().pages[2].rotation)
    }

    @Test fun cannotDeleteEveryPage() {
        val e = PageEditor(2)
        e.selectAll()
        assertEquals(0, e.deleteSelected())
        assertEquals(2, e.state().pages.size)
        e.toggle(0); e.clearSelection(); e.toggle(1)
        assertEquals(1, e.deleteSelected())
    }

    @Test fun historyKeepsAtLeast20Steps() {
        val e = PageEditor(30)
        repeat(25) { e.move(0, 1) } // alternate swaps, 25 recorded steps
        var undone = 0
        while (e.undo()) undone++
        assertEquals(25, undone)
        assertEquals((0 until 30).toList(), e.order())
        var redone = 0
        while (e.redo()) redone++
        assertEquals(25, redone)
        // beyond the cap the oldest steps fall off, never below 20
        val big = PageEditor(10, maxHistory = 50)
        repeat(80) { big.move(0, 9) }
        var n = 0
        while (big.undo()) n++
        assertEquals(50, n)
    }

    @Test fun newEditClearsRedo() {
        val e = PageEditor(3)
        e.move(0, 2); e.undo()
        assertTrue(e.state().canRedo)
        e.move(1, 2)
        assertFalse(e.state().canRedo)
    }

    @Test fun dragIsOneHistoryStepAndNoOpDragLeavesNone() {
        val e = PageEditor(6)
        e.beginDrag(); e.moveLive(0, 1); e.moveLive(1, 2); e.moveLive(2, 4); e.endDrag()
        assertEquals(listOf(1, 2, 3, 4, 0, 5), e.order())
        e.undo()
        assertEquals((0 until 6).toList(), e.order())
        assertFalse(e.state().canUndo)
        e.beginDrag(); e.moveLive(0, 1); e.moveLive(1, 0); e.endDrag()
        assertFalse(e.state().canUndo)
    }

    @Test fun nudgeMovesSelectionKeepingOrder() {
        val e = PageEditor(5)
        e.toggle(2); e.toggle(3)
        assertTrue(e.nudgeSelected(-1))
        assertEquals(listOf(0, 2, 3, 1, 4), e.order())
        assertTrue(e.nudgeSelected(1)); assertTrue(e.nudgeSelected(1))
        assertEquals(listOf(0, 1, 4, 2, 3), e.order())
        assertFalse(e.nudgeSelected(1))
        e.undo()
    }

    @Test fun encodeRestoreRoundTrip() {
        val e = PageEditor(6)
        e.toggle(1); e.rotateSelected(90); e.duplicateSelected(); e.move(0, 5)
        val state = e.state()
        val f = PageEditor(6)
        assertTrue(f.restore(e.encode()))
        assertEquals(state.pages, f.state().pages)
        assertEquals(state.selected, f.state().selected)
        assertFalse(PageEditor(3).restore(e.encode())) // source index out of range for a shorter document
        assertFalse(PageEditor(6).restore(intArrayOf(1, 2)))
    }

    @Test fun savedResultHasTheEditedPageOrderRotationsAndCopies() {
        val src = PdfFixtures.withText(tmp.newFile("pm.pdf"), 8)
        val e = PageEditor(8)
        e.move(7, 0)                         // 8,1,2,3,4,5,6,7
        e.clearSelection(); e.toggle(e.state().pages[2].id)   // page "2"
        e.duplicateSelected()                // ..., 2, 2', ...
        e.rotateSelected(90)
        e.clearSelection(); e.toggle(e.state().pages[4].id)   // page "3"
        e.deleteSelected()
        val out = tmp.newFile("pm_out.pdf")
        out.outputStream().use { PdfAssembler.build(listOf(src), e.specs(), it) }
        val heights = PdfFixtures.pageHeights(out)
        assertEquals(listOf(308, 301, 302, 302, 304, 305, 306, 307), heights)
        assertEquals(listOf(0, 0, 0, 90, 0, 0, 0, 0), PdfFixtures.pageRotations(out))
        // Extract selection only
        val sel = PageEditor(8); sel.toggle(5); sel.toggle(1)
        val ex = tmp.newFile("ex.pdf")
        ex.outputStream().use { PdfAssembler.build(listOf(src), sel.specs(onlySelected = true), it) }
        assertEquals(listOf(302, 306), PdfFixtures.pageHeights(ex))
    }

    @Test fun threeHundredPagesStayFast() {
        val e = PageEditor(400)
        val t = System.nanoTime()
        repeat(200) { e.move(it, 399 - it) }
        e.selectAll(); e.rotateSelected(90); e.duplicateSelected()
        assertEquals(800, e.state().pages.size)
        assertTrue("model ops took too long", (System.nanoTime() - t) / 1_000_000 < 3000)
    }
}
