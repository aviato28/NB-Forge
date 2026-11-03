package com.namanbansal.forge.viewer

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.pdf.PageRasterizer
import com.namanbansal.forge.core.pdf.viewer.PdfDocIndex
import com.namanbansal.forge.core.pdf.viewer.PdfOutline
import com.namanbansal.forge.core.pdf.viewer.TILE_PX
import com.namanbansal.forge.core.pdf.viewer.TileKey
import com.namanbansal.forge.core.pdf.viewer.TileRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ViewerDocTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun doc(rotation: Int = 0): File = PdfFixtures.withText(File(tmp.root, "d$rotation.pdf"), 3, body = { listOf("Hello brave world") }).also { f ->
        if (rotation != 0) PDDocument.load(f).use { d -> d.getPage(0).rotation = rotation; d.save(f) }
    }

    @Test fun wordsAreFoundAndMappedIntoThePage() {
        PdfDocIndex(doc()).use { idx ->
            val t = idx.text(0)
            val i = t.text.indexOf("brave")
            val r = t.lineRects(i, i + 5).single()
            assertTrue("inside the page: $r", r.left in 0f..1f && r.right in 0f..1f && r.right > r.left)
            // "Hello brave world" starts 48pt in (of 595) and "brave" begins about 36pt after that.
            assertTrue("x about ${(48 + 36) / 595f}: ${r.left}", r.left in 0.12f..0.2f)
            assertTrue("a line near the top of the page, y=${r.top}", r.top in 0.0f..0.5f)
            assertEquals(i..i + 4, t.wordAt(t.charAt((r.left + r.right) / 2, (r.top + r.bottom) / 2)))
        }
    }

    @Test fun rotatedPagesMapToTheDisplayedOrientation() {
        PdfDocIndex(doc(90)).use { idx ->
            val t = idx.text(0)
            val i = t.text.indexOf("brave")
            val r = t.lineRects(i, i + 5).single()
            // turned a quarter: the line now runs top to bottom, so its box is tall and narrow
            assertTrue("tall box $r", (r.bottom - r.top) > (r.right - r.left))
        }
    }

    @Test fun pagesWithoutTextAreEmptyNotErrors() {
        val f = PdfFixtures.create(File(tmp.root, "blank.pdf"), 2)
        PdfDocIndex(f).use { idx ->
            assertEquals("", idx.text(0).text.trim())
            assertNull(idx.text(0).bounds())
            assertEquals(-1, idx.text(1).charAt(0.5f, 0.5f))
        }
    }

    private fun linkedDoc(): File {
        val f = File(tmp.root, "links.pdf")
        PDDocument().use { d ->
            repeat(3) { d.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(PDRectangle.A4)) }
            val web = PDAnnotationLink().apply { rectangle = PDRectangle(100f, 700f, 100f, 20f); action = PDActionURI().apply { uri = "https://example.com" } }
            val jump = PDAnnotationLink().apply { rectangle = PDRectangle(100f, 600f, 100f, 20f); destination = PDPageFitDestination().apply { page = d.getPage(2) } }
            d.getPage(0).annotations = listOf(web, jump)
            val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
            val ch1 = PDOutlineItem().apply { title = "Chapter 1"; destination = PDPageFitDestination().apply { page = d.getPage(1) } }
            val sec = PDOutlineItem().apply { title = "Section 1.1"; destination = PDPageFitDestination().apply { page = d.getPage(2) } }
            ch1.addLast(sec); outline.addLast(ch1)
            outline.addLast(PDOutlineItem().apply { title = "Dead end" })
            d.save(f)
        }
        return f
    }

    @Test fun readsExternalAndInternalLinks() {
        PdfDocIndex(linkedDoc()).use { idx ->
            val links = idx.links(0)
            assertEquals(2, links.size)
            val web = links.first { it.uri != null }; val jump = links.first { it.page != null }
            assertEquals("https://example.com", web.uri)
            assertEquals(2, jump.page)
            assertEquals(100f / 595.28f, web.rect.left, 0.01f)
            assertTrue(idx.links(1).isEmpty())
        }
    }

    @Test fun readsTheOutlineTree() {
        PdfDocIndex(linkedDoc()).use { idx ->
            val o = idx.outline()
            assertEquals(listOf("Chapter 1", "Dead end"), o.map { it.title })
            assertEquals(1, o[0].page); assertEquals(2, o[0].children.single().page); assertNull(o[1].page)
        }
        PDDocument.load(linkedDoc()).use { assertEquals(2, PdfOutline.parse(it).size) }
    }

    @Test fun documentsWithoutAnOutlineGiveAnEmptyList() {
        PdfDocIndex(doc()).use { assertTrue(it.outline().isEmpty()) }
    }
}

/** Counts renders and draws a flat grey so tests can check tile plumbing without a real renderer. */
private class FakeRasterizer(val sizeMs: Long = 0) : PageRasterizer {
    val rendered = AtomicInteger()
    override val pageCount = 50
    override fun pageSize(page: Int) = 595 to 842
    override fun render(page: Int, bitmap: Bitmap) {}
    override fun renderTile(page: Int, bitmap: Bitmap, pageWidthPx: Int, left: Int, top: Int, w: Int, h: Int) {
        if (sizeMs > 0) Thread.sleep(sizeMs)
        rendered.incrementAndGet()
    }
    override fun close() {}
}

@RunWith(RobolectricTestRunner::class)
class TileRendererTest {
    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("timed out", cond())
    }

    private fun keys(page: Int, n: Int, width: Int = 8000) = (0 until n).map { TileKey(page, width, it % 15, it / 15) }

    @Test fun rendersWantedTilesAndMemoryStaysWithinTheBudget() {
        val fake = FakeRasterizer()
        val budget = 6L * TILE_PX * TILE_PX * 4 // six tiles
        val r = TileRenderer(fake, budget, Dispatchers.IO.limitedParallelism(1))
        try {
            // Scroll through 100 tiles, 4 on screen at a time.
            for (step in 0 until 25) {
                val want = keys(0, 100).subList(step * 4, step * 4 + 4)
                r.setWanted(0, want)
                waitFor { want.all { r.peek(it) != null } }
                assertTrue("cache ${r.cachedBytes()} within budget $budget", r.cachedBytes() <= budget)
            }
            assertEquals(100, fake.rendered.get())
            assertTrue(r.cachedCount() <= 6)
        } finally { r.close() }
    }

    @Test fun visibleTilesAreKeptEvenIfTheyExceedTheBudget() {
        val r = TileRenderer(FakeRasterizer(), TILE_PX.toLong() * TILE_PX * 4 * 2, Dispatchers.IO.limitedParallelism(1))
        try {
            val want = keys(0, 5)
            r.setWanted(0, want)
            waitFor { want.all { r.peek(it) != null } }
        } finally { r.close() }
    }

    @Test fun tilesThatScrollAwayAreNeverStarted() {
        val fake = FakeRasterizer(sizeMs = 30)
        val r = TileRenderer(fake, 64L shl 20, Dispatchers.IO.limitedParallelism(1))
        try {
            r.setWanted(0, keys(0, 40))
            waitFor { fake.rendered.get() >= 1 }
            r.setWanted(0, emptyList()) // scrolled off
            Thread.sleep(150)
            val after = fake.rendered.get()
            Thread.sleep(150)
            assertEquals(after, fake.rendered.get())
            assertTrue("only a few of 40 were rendered: $after", after < 10)
        } finally { r.close() }
    }

    @Test fun drawsRealPagesThroughTheTestRasterizer() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        val f = PdfFixtures.withText(File.createTempFile("tile", ".pdf"), 1)
        val r = TileRenderer(PdfBoxRasterizer(f), 8L shl 20, Dispatchers.IO.limitedParallelism(1))
        try {
            val k = TileKey(0, 1190, 1, 0)
            r.setWanted(0, listOf(k))
            waitFor { r.peek(k) != null }
            val t = r.peek(k)!!
            assertEquals(TILE_PX, t.width); assertEquals(TILE_PX, t.height)
            assertTrue(r.revision.intValue > 0)
        } finally { r.close() }
    }
}
