package com.namanbansal.forge.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSelectionSyncTest {
    @Test fun typedTextSelectsPages() {
        val a = PageSelectionSync.fromText("1-3, 5", 10, emptySet())
        assertEquals(setOf(0, 1, 2, 4), a.selection)
        assertNull(a.error)
    }

    @Test fun blankTextClearsTheSelection() {
        assertEquals(emptySet<Int>(), PageSelectionSync.fromText("  ", 10, setOf(1, 2)).selection)
        assertNull(PageSelectionSync.fromText("", 10, setOf(1)).error)
    }

    @Test fun invalidTextKeepsTheCurrentSelectionAndReportsWhy() {
        val a = PageSelectionSync.fromText("1-3, 99", 10, setOf(4, 5))
        assertEquals(setOf(4, 5), a.selection)
        assertTrue(a.error!!.contains("doesn't exist"))
        assertNotNull(PageSelectionSync.fromText("3-1", 10, emptySet()).error)
        assertNotNull(PageSelectionSync.fromText("abc", 10, emptySet()).error)
        assertNull(PageSelectionSync.error("", 10))
        assertNull(PageSelectionSync.error("2-4", 10))
    }

    @Test fun textAndSelectionRoundTrip() {
        for (sel in listOf(setOf(0), setOf(0, 1, 2, 4), setOf(2, 4, 6, 8), (0 until 10).toSet(), emptySet())) {
            val text = PageSelectionSync.toText(sel)
            assertEquals("round trip of $sel via '$text'", sel, PageSelectionSync.fromText(text, 10, setOf(7)).selection)
        }
        assertEquals("1-3, 5", PageSelectionSync.toText(setOf(4, 2, 0, 1)))
    }

    @Test fun duplicatesOverlapsAndOpenEndedRangesCollapseToASet() {
        assertEquals(setOf(0, 1, 2, 3), PageSelectionSync.fromText("1-3, 2-4, 2", 10, emptySet()).selection)
        assertEquals(setOf(7, 8, 9), PageSelectionSync.fromText("8-", 10, emptySet()).selection)
        assertEquals(setOf(0), PageSelectionSync.fromText("1", 1, emptySet()).selection)
    }

    @Test fun allNoneAndInvert() {
        assertEquals(setOf(0, 1, 2), PageSelectionSync.all(3))
        assertEquals(setOf(1), PageSelectionSync.invert(setOf(0, 2), 3))
        assertEquals(setOf(0, 1, 2), PageSelectionSync.invert(emptySet(), 3))
        assertEquals(emptySet<Int>(), PageSelectionSync.invert(setOf(0, 1, 2), 3))
        assertEquals(emptySet<Int>(), PageSelectionSync.all(0))
    }

    @Test fun dragRunWorksBothWaysAndKeepsWhatWasThereBefore() {
        val base = setOf(0, 9)
        assertEquals(setOf(0, 2, 3, 4, 9), PageSelectionSync.dragRun(base, 2, 4, true))
        assertEquals(setOf(0, 2, 3, 4, 9), PageSelectionSync.dragRun(base, 4, 2, true)) // backwards
        assertEquals(setOf(0, 9), PageSelectionSync.dragRun(base, 5, 5, false)) // nothing to remove
        assertEquals(setOf(9), PageSelectionSync.dragRun(base, 0, 3, false)) // deselect run
        assertEquals(setOf(0, 1, 9), PageSelectionSync.dragRun(base, 1, 1, true)) // single page
    }
}
