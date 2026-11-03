package com.namanbansal.forge.viewer

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotTool
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.core.pdf.annot.NormPt
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.namanbansal.forge.tools.pdf.AnnotPrefs
import com.namanbansal.forge.tools.pdf.AnnotateController
import com.namanbansal.forge.tools.pdf.PageHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotateControllerTest {
    private val line = "hello big world"
    private val text = PageText(line, line.mapIndexed { i, ch -> if (ch == ' ') null else NormRect(0.1f + i * 0.03f, 0.1f, 0.1f + (i + 1) * 0.03f, 0.12f) })
    private fun controller() = AnnotateController(AnnotPrefs(null), CoroutineScope(Dispatchers.Unconfined)) { text }
    private fun hit(x: Float, y: Float) = PageHit(0, x, y, 0.707f)

    @Test fun penStrokeBecomesOneInkAnnotationWithThePenColourAndWidth() {
        val c = controller()
        c.selectTool(AnnotTool.PEN); c.setColor(AnnotTool.PEN, AnnotPalette.RED); c.setThickness(AnnotTool.PEN, 2)
        c.onStart(hit(0.2f, 0.5f)); (1..20).forEach { c.onMove(0.2f + it * 0.02f, 0.5f + it * 0.003f) }; c.onEnd()
        val a = c.session.current.single()
        assertEquals(AnnotType.INK, a.type); assertEquals(AnnotPalette.RED, a.color); assertEquals(AnnotPalette.thicknesses[2], a.width, 1e-6f)
        assertEquals(1f, a.opacity, 0f); assertTrue(a.strokes[0].size > 5)
        assertTrue(c.session.dirty.value)
    }

    @Test fun highlighterIsSemiTransparentAndWider() {
        val c = controller()
        c.selectTool(AnnotTool.HIGHLIGHTER)
        c.onStart(hit(0.2f, 0.5f)); c.onMove(0.6f, 0.5f); c.onEnd()
        val a = c.session.current.single()
        assertEquals(AnnotPalette.HIGHLIGHTER_OPACITY, a.opacity, 0f); assertTrue(a.width > AnnotPalette.thicknesses[1])
        assertEquals(AnnotPalette.YELLOW, a.color) // default colour of the marker
    }

    @Test fun aTapWithThePenLeavesADot() {
        val c = controller(); c.selectTool(AnnotTool.PEN)
        c.onStart(hit(0.3f, 0.3f)); c.onEnd()
        assertEquals(1, c.session.current.size)
    }

    @Test fun textToolSnapsADragToWordsAndKeepsPerToolColours() {
        val c = controller(); c.selectTool(AnnotTool.HIGHLIGHT)
        c.onStart(hit(0.1f + 6.5f * 0.03f, 0.11f)); c.onMove(0.1f + 8.5f * 0.03f, 0.11f); c.onEnd()
        val a = c.session.current.single()
        assertEquals(AnnotType.HIGHLIGHT, a.type); assertEquals(AnnotPalette.YELLOW, a.color); assertEquals(AnnotPalette.HIGHLIGHT_OPACITY, a.opacity, 0f)
        assertEquals(0.1f + 6 * 0.03f, a.quads.single().left, 1e-4f); assertEquals(0.1f + 9 * 0.03f, a.quads.single().right, 1e-4f)
        c.selectTool(AnnotTool.UNDERLINE)
        assertEquals(AnnotPalette.RED, c.colorOf(AnnotTool.UNDERLINE))
        c.setColor(AnnotTool.UNDERLINE, AnnotPalette.BLUE)
        assertEquals(AnnotPalette.YELLOW, c.colorOf(AnnotTool.HIGHLIGHT)) // not shared between tools
    }

    @Test fun eraserRemovesTheWholeAnnotationInOneUndoStep() {
        val c = controller()
        c.session.addAll(listOf(
            Annot("a", 0, AnnotType.INK, AnnotPalette.BLUE, strokes = listOf(listOf(NormPt(0.2f, 0.5f), NormPt(0.6f, 0.5f))), width = 0.006f),
            Annot("b", 0, AnnotType.INK, AnnotPalette.BLUE, strokes = listOf(listOf(NormPt(0.2f, 0.7f), NormPt(0.6f, 0.7f))), width = 0.006f),
            Annot("c", 0, AnnotType.INK, AnnotPalette.BLUE, strokes = listOf(listOf(NormPt(0.2f, 0.9f), NormPt(0.6f, 0.9f))), width = 0.006f),
        ))
        c.selectTool(AnnotTool.ERASER)
        c.onStart(hit(0.4f, 0.45f)); (1..30).forEach { c.onMove(0.4f, 0.45f + it * 0.01f) }; c.onEnd() // down to 0.75: crosses a and b, not c
        assertEquals(listOf("c"), c.session.current.map { it.id })
        c.session.undo()
        assertEquals(3, c.session.current.size)
    }

    @Test fun noteToolPlacesANoteAndOpensTheEditor_emptyNoteIsTakenBack() {
        val c = controller(); c.selectTool(AnnotTool.NOTE)
        assertTrue(c.onTap(hit(0.5f, 0.5f)))
        val n = c.session.current.single()
        assertEquals(AnnotType.NOTE, n.type); assertEquals(n.id, c.editingNote.value)
        c.cancelNote()
        assertTrue(c.session.current.isEmpty()); assertEquals(null, c.editingNote.value)
        c.onTap(hit(0.5f, 0.5f)); val id = c.session.current.single().id
        c.saveNote(id, "  Ask finance  ")
        assertEquals("Ask finance", c.session.current.single().text)
        // tapping the existing note selects it instead of stacking another
        assertTrue(c.onTap(hit(0.5f, 0.5f))); assertEquals(1, c.session.current.size); assertEquals(id, c.selectedId.value)
    }

    @Test fun selectedAnnotationCanBeRecolouredAndDeleted() {
        val c = controller()
        c.session.add(Annot("h", 0, AnnotType.HIGHLIGHT, AnnotPalette.YELLOW, quads = listOf(NormRect(0.1f, 0.1f, 0.5f, 0.13f))))
        assertTrue(c.onTap(hit(0.3f, 0.11f))); assertEquals("h", c.selectedId.value)
        c.recolorSelected(AnnotPalette.GREEN); assertEquals(AnnotPalette.GREEN, c.session.current.single().color)
        c.deleteSelected(); assertTrue(c.session.current.isEmpty()); assertEquals(null, c.selectedId.value)
        repeat(3) { c.session.undo() }; assertFalse(c.session.dirty.value)
    }

    @Test fun onceAStylusIsSeenFingersNoLongerDraw() {
        val c = controller(); c.selectTool(AnnotTool.PEN)
        assertTrue(c.accepts(stylus = false))
        assertTrue(c.accepts(stylus = true))
        assertFalse(c.accepts(stylus = false))
        assertNotNull(c.claimsDrag)
    }
}
