package com.namanbansal.forge.viewer

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.viewer.LinkGeometry
import com.namanbansal.forge.core.pdf.viewer.OutlineItem
import com.namanbansal.forge.core.pdf.viewer.PageLink
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.namanbansal.forge.core.pdf.viewer.PdfOutline
import com.namanbansal.forge.core.pdf.viewer.SlotLayout
import com.namanbansal.forge.core.pdf.viewer.TILE_PX
import com.namanbansal.forge.core.pdf.viewer.TileCache
import com.namanbansal.forge.core.pdf.viewer.TileKey
import com.namanbansal.forge.core.pdf.viewer.TileMath
import com.namanbansal.forge.core.pdf.viewer.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {
    @Test fun fitWidthPageIsOneColumnOfTiles() {
        // 1080 x 1527 px page, whole page visible (no margin): 3 columns x 3 rows.
        val t = TileMath.visibleTiles(0, 1080, 1527, 0f, 0f, 1080f, 1527f, margin = 0)
        assertEquals(9, t.size)
        assertEquals(setOf(0, 1, 2), t.map { it.col }.toSet()); assertEquals(setOf(0, 1, 2), t.map { it.row }.toSet())
    }

    @Test fun zoomedViewShowsOnlyTheRegionOnScreen() {
        // 8x zoom: the page is 8640 x 12220 px; the 1080x2200 viewport looks at x 3000..4080, y 5000..7200.
        val t = TileMath.visibleTiles(3, 8640, 12220, 3000f, 5000f, 4080f, 7200f, margin = 0)
        assertEquals((5..7).toList(), t.map { it.col }.distinct().sorted())
        assertEquals((9..14).toList(), t.map { it.row }.distinct().sorted())
        assertEquals(3 * 6, t.size)
        assertTrue(t.all { it.page == 3 && it.pageWidthPx == 8640 })
        assertTrue("far fewer than the ${TileMath.cols(8640) * TileMath.rows(12220)} tiles of the page", t.size < 30)
    }

    @Test fun marginPrefetchesNeighboursButStaysInsideThePage() {
        val none = TileMath.visibleTiles(0, 2000, 3000, 0f, 0f, 400f, 400f, margin = 0)
        val withMargin = TileMath.visibleTiles(0, 2000, 3000, 0f, 0f, 400f, 400f)
        assertEquals(1, none.size)
        assertEquals(4, withMargin.size)
        assertTrue(withMargin.all { it.col >= 0 && it.row >= 0 })
    }

    @Test fun nearestTileFirst() {
        val t = TileMath.visibleTiles(0, 3000, 3000, 1000f, 1000f, 1500f, 1500f, margin = 0)
        val first = t.first()
        assertEquals(2, first.col); assertEquals(2, first.row) // the tile containing the centre (1250,1250)
    }

    @Test fun landscapeAndPortraitPagesAndOffscreen() {
        assertEquals(TileMath.cols(1080) * TileMath.rows(764), TileMath.visibleTiles(0, 1080, 764, 0f, 0f, 1080f, 764f, margin = 0).size)
        assertTrue(TileMath.visibleTiles(0, 1080, 1527, 0f, 2000f, 1080f, 3000f).isEmpty()) // below the page
        assertTrue(TileMath.visibleTiles(0, 1080, 1527, -900f, 0f, -10f, 800f).isEmpty()) // left of the page
        assertTrue(TileMath.visibleTiles(0, 0, 0, 0f, 0f, 10f, 10f).isEmpty())
    }

    @Test fun edgeTilesAreClamped() {
        val r = TileMath.tileRect(TileKey(0, 1100, 2, 2), 1100, 1300)
        assertEquals(1024, r.left); assertEquals(1024, r.top); assertEquals(1100, r.right); assertEquals(1300, r.bottom)
        assertEquals(76, r.width); assertEquals(TILE_PX, TileMath.tileRect(TileKey(0, 1100, 0, 0), 1100, 1300).width)
    }
}

class TileCacheTest {
    private fun k(i: Int) = TileKey(0, 100, i, 0)

    @Test fun staysWithinBudgetAndEvictsLeastRecentlyUsed() {
        val evicted = ArrayList<Int>()
        val c = TileCache<Int>(budgetBytes = 100, sizeOf = { it.toLong() }, onEvict = { evicted += it })
        for (i in 1..10) c.put(k(i), 30)
        assertTrue(c.totalBytes() <= 100)
        assertEquals(3, c.size())
        assertTrue(c.contains(k(10)) && c.contains(k(9)) && !c.contains(k(1)))
        assertEquals(7, evicted.size)
    }

    @Test fun readingKeepsATileAlive() {
        val c = TileCache<Int>(60, { it.toLong() })
        c.put(k(1), 30); c.put(k(2), 30)
        c.get(k(1))
        c.put(k(3), 30)
        assertTrue(c.contains(k(1)) && !c.contains(k(2)))
    }

    @Test fun pinnedTilesSurviveEvenOverBudget() {
        val c = TileCache<Int>(60, { it.toLong() })
        val pinned = setOf(k(1), k(2), k(3))
        for (i in 1..3) c.put(k(i), 30, pinned)
        assertEquals(3, c.size()) // visible tiles are never thrown away
        c.put(k(4), 30, setOf(k(4)))
        assertTrue(!c.contains(k(1)) || c.totalBytes() <= 90)
    }
}

class SlotLayoutTest {
    @Test fun slotsSinglesAndSpreads() {
        assertEquals(listOf(0..0, 1..1, 2..2), SlotLayout.slots(3, false))
        assertEquals(listOf(0..1, 2..3, 4..4), SlotLayout.slots(5, true))
        assertEquals(2, SlotLayout.slotOf(5, true)); assertEquals(5, SlotLayout.slotOf(5, false))
        assertTrue(SlotLayout.slots(0, true).isEmpty())
    }

    @Test fun fitWidthFillsViewportAndZoomScales() {
        val a = floatArrayOf(0.5f)
        val g1 = SlotLayout.compute(0..0, a, 1000, 2000, 1f, fitPage = false)
        assertEquals(1000, g1.boxes[0].w); assertEquals(2000, g1.boxes[0].h); assertEquals(0, g1.boxes[0].x)
        val g3 = SlotLayout.compute(0..0, a, 1000, 2000, 3f, fitPage = false)
        assertEquals(3000, g3.boxes[0].w); assertEquals(6000, g3.height)
    }

    @Test fun fitPageKeepsPageInsideViewportAndCentres() {
        val g = SlotLayout.compute(0..0, floatArrayOf(0.7f), 1000, 1000, 1f, fitPage = true, minHeight = 1000)
        assertEquals(700, g.boxes[0].w); assertEquals(150, g.boxes[0].x); assertEquals(1000, g.height)
    }

    @Test fun spreadSplitsTheWidthAndHitTestMapsToPageFractions() {
        val g = SlotLayout.compute(0..1, floatArrayOf(0.5f, 0.5f), 1000, 600, 1f, fitPage = false)
        assertEquals(500, g.boxes[0].w); assertEquals(500, g.boxes[1].x)
        val (box, frac) = SlotLayout.hit(g, 750f, 500f)!!
        assertEquals(1, box.page); assertEquals(0.5f, frac.first, 0.001f); assertEquals(0.5f, frac.second, 0.001f)
        assertNull(SlotLayout.hit(g, 750f, 5000f))
    }
}

class LinkGeometryTest {
    // US Letter-ish box 600 x 800 at the origin; link at x 100..200, y 700..750 (near the top).
    @Test fun unrotated() {
        val r = LinkGeometry.normalize(100f, 700f, 200f, 750f, 0f, 0f, 600f, 800f, 0)
        assertEquals(100f / 600, r.left, 1e-4f); assertEquals(200f / 600, r.right, 1e-4f)
        assertEquals(50f / 800, r.top, 1e-4f); assertEquals(100f / 800, r.bottom, 1e-4f)
    }

    @Test fun rotated90MovesTheTopLeftToTheTopRight() {
        val r = LinkGeometry.normalize(0f, 750f, 50f, 800f, 0f, 0f, 600f, 800f, 90) // the unrotated top-left corner
        assertTrue("now in the top-right corner: $r", r.left > 0.9f && r.top < 0.1f)
    }

    @Test fun rotated180And270() {
        val a = LinkGeometry.normalize(0f, 750f, 50f, 800f, 0f, 0f, 600f, 800f, 180)
        assertTrue(a.left > 0.9f && a.top > 0.9f)
        val b = LinkGeometry.normalize(0f, 750f, 50f, 800f, 0f, 0f, 600f, 800f, 270)
        assertTrue(b.left < 0.1f && b.top > 0.9f)
    }

    @Test fun cropBoxOffsetIsRespected() {
        val r = LinkGeometry.normalize(150f, 50f, 250f, 100f, 100f, 0f, 400f, 200f, 0)
        assertEquals(0.125f, r.left, 1e-4f); assertEquals(0.375f, r.right, 1e-4f)
    }

    @Test fun hitTestPicksTheSmallestOverlappingLinkAndIgnoresMisses() {
        val big = PageLink(NormRect(0.1f, 0.1f, 0.9f, 0.9f), "https://big", null)
        val small = PageLink(NormRect(0.4f, 0.4f, 0.5f, 0.5f), null, 7)
        assertEquals(7, LinkGeometry.hit(listOf(big, small), 0.45f, 0.45f)!!.page)
        assertEquals("https://big", LinkGeometry.hit(listOf(big, small), 0.2f, 0.2f)!!.uri)
        assertNull(LinkGeometry.hit(listOf(big, small), 0.95f, 0.95f))
        assertEquals("https://big", LinkGeometry.hit(listOf(big), 0.0999f, 0.5f)!!.uri) // within the slop
    }
}

class PageTextSelectionTest {
    // "ab cd" with each letter 0.1 wide on one line, a gap at index 2.
    private fun line(y: Float) = PageText("ab cd", listOf(
        NormRect(0.1f, y, 0.2f, y + 0.05f), NormRect(0.2f, y, 0.3f, y + 0.05f), null,
        NormRect(0.4f, y, 0.5f, y + 0.05f), NormRect(0.5f, y, 0.6f, y + 0.05f),
    ))

    @Test fun charAtFindsTheLetterUnderTheFinger() {
        val t = line(0.1f)
        assertEquals(0, t.charAt(0.12f, 0.12f)); assertEquals(4, t.charAt(0.55f, 0.13f))
        assertEquals(-1, t.charAt(0.9f, 0.9f))
        assertEquals(1, t.charAt(0.305f, 0.12f)) // just right of the letter: within the slop
    }

    @Test fun wordAndRects() {
        val t = line(0.1f)
        assertEquals(3..4, t.wordAt(3)); assertNull(t.wordAt(2))
        val r = t.lineRects(3, 5).single()
        assertEquals(0.4f, r.left, 1e-5f); assertEquals(0.6f, r.right, 1e-5f)
        assertEquals("cd", t.substring(3, 5)); assertEquals(0..4, t.bounds())
    }

    @Test fun twoLinesGiveTwoRects() {
        val a = line(0.1f); val b = line(0.3f)
        val t = PageText("ab cd\nab cd", a.rects + listOf(null) + b.rects)
        assertEquals(2, t.lineRects(0, t.length).size)
        assertEquals(1, t.lineRects(0, 2).size)
    }

    @Test fun selectionDragNeverInverts() {
        val s = TextSelection(0, 3, 5)
        assertEquals(TextSelection(0, 1, 3), s.withEnd(1))
        assertEquals(TextSelection(0, 3, 9), s.withEnd(9))
    }
}

class OutlineFlattenTest {
    private val tree = listOf(
        OutlineItem("Intro", 0, emptyList()),
        OutlineItem("Part 1", 2, listOf(OutlineItem("1.1", 3, emptyList()), OutlineItem("1.2", 4, listOf(OutlineItem("1.2.1", 5, emptyList()))))),
    )

    @Test fun collapsedShowsTopLevelOnly() {
        val rows = PdfOutline.flatten(tree, emptySet())
        assertEquals(listOf("Intro", "Part 1"), rows.map { it.title })
        assertTrue(rows[1].hasChildren && !rows[1].expanded && !rows[0].hasChildren)
    }

    @Test fun expandingRevealsChildrenWithDepth() {
        val rows = PdfOutline.flatten(tree, setOf("1"))
        assertEquals(listOf("Intro", "Part 1", "1.1", "1.2"), rows.map { it.title })
        assertEquals(listOf(0, 0, 1, 1), rows.map { it.depth })
        val deep = PdfOutline.flatten(tree, setOf("1", "1.1"))
        assertEquals(listOf("Intro", "Part 1", "1.1", "1.2", "1.2.1"), deep.map { it.title })
        assertEquals(2, deep.last().depth)
        assertEquals(listOf("Intro", "Part 1"), PdfOutline.flatten(tree, setOf("1.1")).map { it.title }) // a child id alone shows nothing new
    }

    @Test fun emptyOutline() { assertTrue(PdfOutline.flatten(emptyList(), emptySet()).isEmpty()) }
}
