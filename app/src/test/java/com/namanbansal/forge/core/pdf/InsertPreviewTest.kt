package com.namanbansal.forge.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertPreviewTest {
    private fun kinds(e: List<PreviewEntry>) = e.map { if (it.kind == PreviewKind.Base) "b${it.page}" else "n" }
    private val two = InsertPreview.otherEntries(listOf(3, 0))

    @Test fun insertsInTheMiddleAndMarksTheGapInFrontOfTheNewPages() {
        val e = InsertPreview.entries(4, 2, two)
        assertEquals(listOf("b0", "b1", "n", "n", "b2", "b3"), kinds(e))
        assertEquals(2, e[2].gapBefore)
        assertEquals(null, e[3].gapBefore)
        assertEquals(null, e[4].gapBefore) // the block's marker stands in for gap 2
        assertEquals(0, e[0].gapBefore)
        assertEquals(1, e[1].gapBefore)
        assertEquals(3, e[5].gapBefore)
        assertEquals(4, e[5].gapAfter)
    }

    @Test fun insertsAtStartAndEnd() {
        val start = InsertPreview.entries(3, 0, two)
        assertEquals(listOf("n", "n", "b0", "b1", "b2"), kinds(start))
        assertEquals(0, start[0].gapBefore)
        val end = InsertPreview.entries(3, 3, two)
        assertEquals(listOf("b0", "b1", "b2", "n", "n"), kinds(end))
        assertEquals(3, end[2].gapAfter)
        assertEquals(null, end[3].gapBefore)
    }

    @Test fun everyGapIsReachableExactlyOnce() {
        for (at in 0..5) for (block in listOf(emptyList(), two)) {
            val ids = InsertPreview.entries(5, at, block).flatMap { listOfNotNull(it.gapBefore, it.gapAfter) }
            assertEquals("at=$at block=${block.size}", (0..5).toList(), ids.sorted())
        }
    }

    @Test fun outOfRangeGapIsClamped() {
        assertEquals(listOf("b0", "b1", "n"), kinds(InsertPreview.entries(2, 99, InsertPreview.blankEntries(1))))
        assertEquals(listOf("n", "b0"), kinds(InsertPreview.entries(1, -4, InsertPreview.blankEntries(1))))
    }

    @Test fun blanksAndOtherPagesKeepTheirKind() {
        assertEquals(List(3) { PreviewKind.Blank }, InsertPreview.blankEntries(3).map { it.kind })
        assertEquals(listOf(3, 0), two.map { it.page })
        assertEquals(PreviewKind.Other, two[0].kind)
    }

    @Test fun tappedGapMapsToThePersistedPosition() {
        assertEquals(InsertPosition.Start to 1, InsertPreview.positionFor(0, 5))
        assertEquals(InsertPosition.BeforePage to 3, InsertPreview.positionFor(2, 5))
        assertEquals(InsertPosition.End to 5, InsertPreview.positionFor(5, 5))
        for (g in 0..5) assertEquals(g, PdfInsert.insertIndex(InsertPreview.positionFor(g, 5).first, InsertPreview.positionFor(g, 5).second, 5))
    }
}
