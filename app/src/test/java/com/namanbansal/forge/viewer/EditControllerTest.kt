package com.namanbansal.forge.viewer

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditStyle
import com.namanbansal.forge.core.pdf.edit.RedactMark
import com.namanbansal.forge.core.pdf.edit.RedactPreset
import com.namanbansal.forge.core.pdf.edit.StoredImage
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.namanbansal.forge.core.pdf.viewer.TextSelection
import com.namanbansal.forge.tools.pdf.EditController
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.PageHit
import com.namanbansal.forge.tools.pdf.RedactHow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditControllerTest {
    private val messages = ArrayList<String>()
    private val text = "Mail jane@example.org now and call 555-123-4567 today"
    private val pt = PageText(text, text.indices.map { i -> NormRect(0.01f + 0.015f * i, 0.1f, 0.01f + 0.015f * i + 0.014f, 0.12f) })
    private var sampled: Int? = 0xFFF0E6CC.toInt()

    private fun controller(page: Int = 0) = EditController(
        pageAspect = { 0.7f }, currentPage = { page }, pageCount = { 3 }, scope = CoroutineScope(Dispatchers.Unconfined),
        pageText = { p -> if (p == 1) pt else null }, sampleColor = { _, _, _ -> sampled }, message = { messages += it },
    )

    private fun hit(x: Float, y: Float, page: Int = 0) = PageHit(page, x, y, 0.7f)
    private fun EditController.drag(page: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        assertTrue(claimsDrag); assertTrue(claimsAt(hit(x0, y0, page)))
        onStart(hit(x0, y0, page)); onMove((x0 + x1) / 2, (y0 + y1) / 2); onMove(x1, y1); onEnd()
    }

    @Test fun dragWithAShapeToolDrawsThenSelectsIt() {
        val c = controller()
        c.setTool(EditTool.SHAPE); c.setShapeKind(EditKind.ELLIPSE)
        c.drag(0, 0.2f, 0.2f, 0.5f, 0.35f)
        val o = c.session.current.items.single()
        assertEquals(EditKind.ELLIPSE, o.kind)
        assertEquals(NormRect(0.2f, 0.2f, 0.5f, 0.35f), o.rect)
        assertEquals(o.id, c.selectedId.value)
        assertEquals(EditTool.SELECT, c.tool.value)
    }

    @Test fun lineDirectionFollowsTheDrag() {
        val c = controller()
        c.setShapeKind(EditKind.ARROW)
        c.drag(0, 0.2f, 0.4f, 0.6f, 0.2f)   // up and to the right: bottom-left to top-right
        val up = c.session.current.items.last()
        assertTrue(up.flip); assertFalse(up.reverse)
        c.setShapeKind(EditKind.ARROW)
        c.drag(0, 0.6f, 0.5f, 0.2f, 0.7f)   // down and to the left: the head is at the left end
        val back = c.session.current.items.last()
        assertTrue(back.reverse)
    }

    @Test fun aTapWithAShapeToolDropsADefaultOne() {
        val c = controller()
        c.setShapeKind(EditKind.RECT)
        assertTrue(c.onTap(hit(0.5f, 0.5f)))
        val o = c.session.current.items.single()
        assertTrue(o.rect.right - o.rect.left > 0.2f)
        assertTrue(o.rect.left >= 0f && o.rect.right <= 1f && o.rect.bottom <= 1f)
    }

    @Test fun selectMoveResizeDuplicateFrontDelete() {
        val c = controller()
        c.setShapeKind(EditKind.RECT); c.drag(0, 0.1f, 0.1f, 0.3f, 0.2f)
        val a = c.session.current.items.single()
        c.setShapeKind(EditKind.RECT); c.drag(0, 0.5f, 0.5f, 0.7f, 0.6f)
        val b = c.session.current.items.last()
        // tap selects the one under the finger
        c.select(null); assertTrue(c.onTap(hit(0.2f, 0.15f))); assertEquals(a.id, c.selectedId.value)
        // drag inside moves it by the finger delta (one undo step)
        c.drag(0, 0.2f, 0.15f, 0.3f, 0.25f)
        val moved = c.session.current.items.first { it.id == a.id }.rect
        assertEquals(0.2f, moved.left, 1e-4f); assertEquals(0.4f, moved.right, 1e-4f); assertEquals(0.2f, moved.top, 1e-4f)
        // drag the corner handle to resize (free for shapes)
        c.drag(0, 0.4f, 0.3f, 0.6f, 0.4f)
        val big = c.session.current.items.first { it.id == a.id }.rect
        assertEquals(0.2f, big.left, 1e-4f); assertEquals(0.6f, big.right, 1e-4f); assertEquals(0.4f, big.bottom, 1e-4f)
        c.bringToFront(); assertEquals(a.id, c.session.current.items.last().id)
        c.duplicateSelected(); assertEquals(3, c.session.current.items.size); assertTrue(c.selectedId.value != a.id)
        c.deleteSelected(); assertEquals(2, c.session.current.items.size); assertNull(c.selectedId.value)
        c.session.undo(); assertEquals(3, c.session.current.items.size)
        assertNotNull(b)
    }

    @Test fun imagesKeepTheirShapeWhileResizingAndTurnByNinetyDegrees() {
        val c = controller()
        c.placeImage(StoredImage("img1", 2f, false))
        val o = c.session.current.items.single()
        assertEquals(EditKind.IMAGE, o.kind)
        val w0 = o.rect.right - o.rect.left; val h0 = o.rect.bottom - o.rect.top
        c.drag(0, o.rect.right, o.rect.bottom, o.rect.right + 0.1f, o.rect.bottom + 0.01f)
        val r = c.session.current.items.single().rect
        assertEquals("aspect locked", (w0 / h0), (r.right - r.left) / (r.bottom - r.top), 0.02f)
        c.setLockAspect(false)
        val r1 = c.session.current.items.single().rect
        c.drag(0, r1.right, r1.bottom, r1.right - 0.1f, r1.bottom + 0.05f)
        val r2 = c.session.current.items.single().rect
        assertTrue("free resize changes the ratio", kotlin.math.abs((r2.right - r2.left) / (r2.bottom - r2.top) - (r1.right - r1.left) / (r1.bottom - r1.top)) > 0.05f)
        c.rotateSelected(); c.rotateSelected(); c.rotateSelected(); c.rotateSelected()
        assertEquals(0, c.session.current.items.single().turns)
        c.rotateSelected(); assertEquals(1, c.session.current.items.single().turns)
    }

    @Test fun textBoxesAreCreatedByTapAndEmptyOnesDisappear() {
        val c = controller()
        c.setTool(EditTool.TEXT)
        assertTrue(c.onTap(hit(0.3f, 0.3f)))
        val t = c.session.current.items.single()
        assertEquals(EditKind.TEXT, t.kind); assertEquals(t.id, c.editingId.value)
        c.closeTextEdit("   ")
        assertTrue(c.session.current.items.isEmpty())
        c.setTool(EditTool.TEXT); c.onTap(hit(0.3f, 0.3f)); c.closeTextEdit("Hello there")
        assertEquals("Hello there", c.session.current.items.single().text)
        // styling applies to the selected box and is remembered for the next one
        c.style { it.copy(font = com.namanbansal.forge.core.pdf.edit.EditFont.MONO, bold = true, color = EditStyle.RED, align = 1) }
        val s = c.session.current.items.single()
        assertEquals(com.namanbansal.forge.core.pdf.edit.EditFont.MONO, s.font); assertTrue(s.bold); assertEquals(1, s.align); assertEquals(EditStyle.RED, s.color)
        c.select(null); c.setTool(EditTool.TEXT); c.onTap(hit(0.5f, 0.6f)); c.closeTextEdit("Second")
        assertEquals(EditStyle.RED, c.session.current.items.last().color)
    }

    @Test fun tallTextGrowsTheBox() {
        val c = controller()
        c.setTool(EditTool.TEXT); c.onTap(hit(0.1f, 0.1f))
        val h0 = c.session.current.items.single().rect.let { it.bottom - it.top }
        c.closeTextEdit((1..25).joinToString(" ") { "word$it" })
        val h1 = c.session.current.items.single().rect.let { it.bottom - it.top }
        assertTrue("$h1 > $h0", h1 > h0 * 2)
    }

    @Test fun whiteoutTakesTheEyedropperColourAndReplacementTextFitsTheBox() {
        val c = controller()
        c.setTool(EditTool.WHITEOUT)
        c.drag(0, 0.1f, 0.4f, 0.6f, 0.45f)
        val w = c.session.current.items.single()
        assertEquals(EditKind.WHITEOUT, w.kind); assertEquals(EditStyle.WHITE, w.fillColor)
        c.toggleEyedropper(); assertTrue(c.eyedropper.value)
        c.onTap(hit(0.3f, 0.3f))
        assertEquals(0xFFF0E6CC.toInt(), c.session.current.items.single().fillColor)
        assertFalse(c.eyedropper.value)
        c.editSelectedText(); assertEquals(w.id, c.editingId.value)
        c.closeTextEdit("New value")
        val t = c.session.current.items.single()
        assertEquals("New value", t.text)
        assertTrue("sized to the 5% tall box: ${t.sizeFrac}", t.sizeFrac <= 0.05f / 0.7f / 1.7f + 0.002f)
        // a size the user picked is kept when the text changes
        c.style(pinSize = true) { it.copy(sizeFrac = 0.012f) }
        c.editSelectedText(); c.closeTextEdit("Another longer value here")
        assertEquals(0.012f, c.session.current.items.single().sizeFrac, 1e-5f)
        c.fitSelectedText(); assertTrue(c.session.current.items.single().sizeFrac != 0.012f)
    }

    @Test fun eyedropperFailureIsExplained() {
        val c = controller(); sampled = null
        c.toggleEyedropper(); c.onTap(hit(0.2f, 0.2f))
        assertTrue(messages.any { it.contains("colour") })
    }

    @Test fun dragMarksAndSelectingAndRemovingThem() {
        val c = controller()
        c.setTool(EditTool.REDACT)
        c.drag(0, 0.1f, 0.1f, 0.4f, 0.2f)
        assertEquals(1, c.session.current.marks.size)
        c.onStart(hit(0.5f, 0.5f)); c.onMove(0.5004f, 0.5004f); c.onEnd() // a speck is ignored
        assertEquals(1, c.session.current.marks.size)
        assertTrue(c.onTap(hit(0.2f, 0.15f))); assertNotNull(c.selectedMark.value)
        c.deleteSelected(); assertTrue(c.session.current.marks.isEmpty())
        c.session.undo(); assertEquals(1, c.session.current.marks.size)
        // text mode leaves drags to the viewer's own selection
        c.setRedactHow(RedactHow.TEXT); assertFalse(c.claimsDrag)
        c.askApply(); assertTrue(c.applyConfirm.value); c.cancelApply(); assertFalse(c.applyConfirm.value)
    }

    @Test fun nothingToApplyMeansNoDialog() {
        val c = controller(); c.askApply(); assertFalse(c.applyConfirm.value)
    }

    @Test fun textSelectionBecomesLineMarks() {
        val c = controller(page = 1)
        c.markSelection(TextSelection(1, 5, 21))
        assertEquals(1, c.session.current.marks.size)
        assertEquals(RedactMark.ORIGIN_TEXT, c.session.current.marks[0].origin)
        assertEquals(1, c.session.current.marks[0].page)
    }

    @Test fun searchReviewTogglesAndMarksOnlyTheChosenHits() {
        val c = controller()
        c.runSearch(RedactPreset.EMAIL, null)
        var ui = c.search.value
        assertTrue(ui.done); assertFalse(ui.running)
        assertEquals(1, ui.hits.size); assertEquals(1, ui.hits[0].page)
        assertEquals(setOf(ui.hits[0].id), ui.on)
        c.runSearch(null, "o")
        ui = c.search.value
        assertTrue("several hits", ui.hits.size >= 3)
        c.setAllHits(false); assertTrue(c.search.value.on.isEmpty())
        c.toggleHit(ui.hits[0].id); c.toggleHit(ui.hits[2].id)
        c.markHits()
        assertEquals(2, c.session.current.marks.size)
        assertEquals(RedactMark.ORIGIN_SEARCH, c.session.current.marks[0].origin)
        assertFalse(c.search.value.open)
        // phone numbers
        c.runSearch(RedactPreset.PHONE, null)
        assertEquals("555-123-4567", c.search.value.hits.single().text)
    }

    @Test fun resetClearsEverything() {
        val c = controller()
        c.setShapeKind(EditKind.RECT); c.drag(0, 0.1f, 0.1f, 0.3f, 0.2f)
        c.session.addMark(RedactMark("m", 0, NormRect(0f, 0f, 0.1f, 0.1f)))
        c.reset()
        assertTrue(c.session.current.isEmpty); assertFalse(c.session.dirty.value); assertNull(c.selectedId.value)
    }
}
