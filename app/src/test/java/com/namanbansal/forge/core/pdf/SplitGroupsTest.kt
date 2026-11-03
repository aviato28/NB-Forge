package com.namanbansal.forge.core.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitGroupsTest {
    @Test fun cutsMakeContiguousFiles() {
        val parts = SplitGroups.partsFromStarts(setOf(3, 5), 8)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5, 6, 7)), parts.map { it.pages })
        assertEquals(listOf("p1-3", "p4-5", "p6-8"), parts.map { it.label })
    }

    @Test fun noCutsIsOneFileAndBadCutsAreIgnored() {
        assertEquals(1, SplitGroups.partsFromStarts(emptySet(), 5).size)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4)), SplitGroups.partsFromStarts(setOf(0, 2, 5, 99, -1), 5).map { it.pages })
        assertEquals(emptyList<SplitPart>(), SplitGroups.partsFromStarts(setOf(1), 0))
        assertEquals(setOf(2), SplitGroups.cleanStarts(listOf(0, 2, 5), 5))
    }

    @Test fun everyGapCutGivesSinglePagesAndOnePageDocStaysWhole() {
        assertEquals(4, SplitGroups.partsFromStarts(setOf(1, 2, 3), 4).size)
        assertEquals(listOf(listOf(0)), SplitGroups.partsFromStarts(setOf(1), 1).map { it.pages })
    }

    @Test fun ownersMapEachPageToItsFirstFileAndMarkUnusedPages() {
        val parts = PdfSplitter.byRanges(listOf(0..1, 4..4, 3..5)) // page 4 (index 3) is in the third file; index 4 in two files
        assertArrayEquals(intArrayOf(0, 0, -1, 2, 1, 2), SplitGroups.owners(parts, 6))
        assertArrayEquals(intArrayOf(-1, -1), SplitGroups.owners(emptyList(), 2))
    }

    @Test fun titlesReadLikeTheMockup() {
        val p = SplitGroups.partsFromStarts(setOf(3), 5)
        assertEquals("File 1 · pages 1–3", SplitGroups.title(0, p[0]))
        assertEquals("File 2 · pages 4–5", SplitGroups.title(1, p[1]))
        assertEquals("File 3 · page 5", SplitGroups.title(2, SplitPart("p5", listOf(4))))
        assertEquals("File 1 · pages 1–3, 5", SplitGroups.title(0, SplitPart("x", listOf(0, 1, 2, 4))))
    }

    @Test fun otherModesFeedTheSameColouredGrid() {
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 2), SplitGroups.owners(PdfSplitter.everyN(2, 5), 5))
        assertArrayEquals(intArrayOf(0, 1, 2), SplitGroups.owners(PdfSplitter.singlePages(3), 3))
    }
}
