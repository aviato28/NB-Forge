package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfTextSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun searchDoc(pages: Int = 6) = PdfTextSearch(
        PdfFixtures.withText(tmp.newFile("t${System.nanoTime()}.pdf"), pages, body = { p ->
            if (p % 2 == 0) listOf("The quarterly figures are final", "Revenue grew") else listOf("Nothing to see here")
        }),
    )

    private fun all(s: PdfTextSearch, q: String): List<TextHit> {
        val out = ArrayList<TextHit>()
        s.search(q) { out += it }
        return out
    }

    @Test fun findsHitsOnTheRightPagesCaseInsensitively() {
        searchDoc().use { s ->
            val hits = all(s, "FIGURES")
            assertEquals(listOf(1, 3, 5), hits.map { it.page }) // pages 2, 4, 6 (0-based 1,3,5)
            assertTrue(hits.all { it.match.equals("figures", true) })
            assertTrue(hits.first().before.contains("quarterly"))
            assertTrue(hits.first().after.contains("are final"))
        }
    }

    @Test fun highlightBoxesAreInsideThePageAndSitWhereTheTextIs() {
        searchDoc(2).use { s ->
            val hit = all(s, "figures").single()
            val r = hit.rects.single()
            assertTrue(r.left in 0f..1f && r.right in 0f..1f && r.top in 0f..1f && r.bottom in 0f..1f)
            assertTrue(r.right > r.left && r.bottom > r.top)
            // The body line is written at x=48 pt on a 595 pt wide page: "The quarterly " precedes it, so left is well past the margin.
            assertTrue("left=${r.left}", r.left > 48f / 595f)
            assertTrue("box should be narrow, was ${r.right - r.left}", r.right - r.left < 0.2f)
            // It is in the upper part of the page (text near the top of a 300pt page).
            assertTrue("top=${r.top}", r.top < 0.6f)
        }
    }

    @Test fun multiWordQueriesAndRepeatsAndMisses() {
        searchDoc().use { s ->
            assertEquals(3, all(s, "quarterly figures").size)
            assertEquals(3, all(s, "quarterly   figures").size) // whitespace-insensitive
            assertTrue(all(s, "page").size >= 6) // the "Page n" headings
            assertTrue(all(s, "zzzz-not-here").isEmpty())
            assertTrue(all(s, "   ").isEmpty())
        }
    }

    @Test fun respectsMaxHitsAndCancel() {
        searchDoc(12).use { s ->
            val out = ArrayList<TextHit>()
            val total = s.search("figures", maxHits = 2) { out += it }
            assertEquals(2, total); assertEquals(2, out.size)
            var calls = 0
            try { s.search("figures", checkCancelled = { if (++calls > 2) throw CancellationException() }) {}; fail() } catch (_: CancellationException) {}
        }
    }

    @Test fun progressReachesOne() {
        searchDoc(5).use { s ->
            val p = ArrayList<Float>()
            s.search("figures", onProgress = { p += it }) {}
            assertEquals(1f, p.last(), 0.0001f)
            assertEquals(p.sorted(), p)
        }
    }
}
