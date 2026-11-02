package com.namanbansal.forge.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {
    private fun ok(text: String, n: Int = 10) = (PageRanges.parse(text, n) as PageRanges.Result.Ok)
    private fun err(text: String, n: Int = 10) = (PageRanges.parse(text, n) as PageRanges.Result.Error).message

    @Test fun parsesRangesAndSinglesInTypedOrder() {
        val r = ok("1-3, 5, 8-10")
        assertEquals(listOf(0..2, 4..4, 7..9), r.ranges)
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), r.pages)
        assertEquals(listOf(4, 0), ok("5, 1").pages)
        assertEquals(listOf(0, 4), ok("5, 1, 5").sortedUnique)
    }

    @Test fun toleratesSpacesDashesAndOpenEnds() {
        assertEquals(listOf(0..2), ok(" 1 - 3 ").ranges)
        assertEquals(listOf(0..1), ok("1–2").ranges)
        assertEquals(listOf(6..9), ok("7-").ranges)
        assertEquals(listOf(0..2), ok("-3").ranges)
        assertEquals(listOf(0..0, 2..2), ok("1 3").ranges)
        assertEquals(listOf(0..0, 2..2), ok("1;3").ranges)
    }

    @Test fun rejectsBadInputWithAMessage() {
        assertTrue(err("").contains("Enter page numbers"))
        assertTrue(err("abc").contains("isn't a page number"))
        assertTrue(err("0").contains("start at 1"))
        assertTrue(err("11").contains("doesn't exist"))
        assertTrue(err("11").contains("10 pages"))
        assertTrue(err("5-3").contains("backwards"))
        assertTrue(err("1-2-3").contains("isn't a valid range"))
        assertTrue(err("1-x").contains("isn't a valid range"))
        assertTrue(err("3-20").contains("doesn't exist"))
        assertTrue(err("-").isNotBlank())
        assertTrue(err("2", 1).contains("1 page"))
    }

    @Test fun formatCollapsesRuns() {
        assertEquals("1-3, 5", PageRanges.format(listOf(0, 1, 2, 4)))
        assertEquals("2", PageRanges.format(listOf(1)))
        assertEquals("", PageRanges.format(emptyList()))
        assertEquals("1-2, 4-5", PageRanges.format(setOf(4, 3, 1, 0)))
    }

    @Test fun labels() {
        assertEquals("p5", PageRanges.label(4..4))
        assertEquals("p1-3", PageRanges.label(0..2))
    }
}
