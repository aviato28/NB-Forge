package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BookmarksLinksTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun save(src: File, edit: (PDDocument) -> Unit): File {
        val out = File(tmp.root, "o-${System.nanoTime()}.pdf")
        PdfOps.load(src).use { edit(it); it.save(out) }
        return out
    }

    // ---- bookmarks
    @Test fun outlineRoundTrips() {
        val src = PdfFixtures.create(tmp.newFile("b.pdf"), pages = 5)
        val list = listOf(
            BookmarkEntry("Part one", 0, 0), BookmarkEntry("Chapter 1", 1, 1), BookmarkEntry("Section 1.1", 2, 2), BookmarkEntry("Chapter 2", 3, 1),
            BookmarkEntry("Part two", 4, 0), BookmarkEntry("Tab\tand \"quotes\" & é", 4, 1),
        )
        val out = save(src) { PdfBookmarks.write(it, list) }
        val back = PdfOps.load(out).use { PdfBookmarks.read(it) }
        assertEquals(list, back)
        // writing an empty list removes the outline
        val cleared = save(out) { PdfBookmarks.write(it, emptyList()) }
        assertEquals(emptyList<BookmarkEntry>(), PdfOps.load(cleared).use { PdfBookmarks.read(it) })
    }

    @Test fun editOperationsKeepAValidTree() {
        val l = listOf(BookmarkEntry("A", 0, 0), BookmarkEntry("A1", 1, 1), BookmarkEntry("A2", 2, 1), BookmarkEntry("B", 3, 0))
        assertEquals(listOf(0, 1, 1, 0), PdfBookmarks.indent(l, 0).map { it.level }) // first item can't indent
        assertEquals(listOf(0, 1, 1, 1), PdfBookmarks.indent(l, 3).map { it.level })  // B becomes a child of A2's level
        assertEquals(listOf(0, 0, 1, 0), PdfBookmarks.outdent(l, 1).map { it.level })
        assertEquals(listOf("B"), PdfBookmarks.delete(l, 0).map { it.title })
        assertEquals(listOf("A", "B"), PdfBookmarks.delete(PdfBookmarks.delete(l, 1), 1).map { it.title })
        assertEquals(listOf("B", "A", "A1", "A2"), PdfBookmarks.moveSubtree(l, 3, true).map { it.title })
        assertEquals(listOf("B", "A", "A1", "A2"), PdfBookmarks.moveSubtree(l, 0, false).map { it.title })
        assertEquals(listOf("A", "A1", "NEW", "A2", "B"), PdfBookmarks.add(l, "NEW", 4, after = 1).map { it.title })
        assertEquals(listOf("A", "A1", "A2", "NEW", "B").size, PdfBookmarks.add(l, "NEW", 0, after = 0).size)
        assertEquals(listOf("A", "A1", "A2", "B", "NEW"), PdfBookmarks.add(l, "NEW", 0).map { it.title })
        val moved = PdfBookmarks.move(l, 3, 1, 5)
        assertEquals(listOf("A", "B", "A1", "A2"), moved.map { it.title }); assertTrue(moved.zipWithNext().all { (a, b) -> b.level <= a.level + 1 })
        assertEquals(l, PdfBookmarks.decode(PdfBookmarks.encode(l)))
        val tricky = listOf(BookmarkEntry("a\tb\\n\nc", 3, 0))
        assertEquals(tricky, PdfBookmarks.decode(PdfBookmarks.encode(tricky)))
        assertEquals(listOf(0, 1, 1), PdfBookmarks.normalize(listOf(BookmarkEntry("x", 0, 4), BookmarkEntry("y", 0, 5), BookmarkEntry("z", 99, 1)), 3).map { it.level })
    }

    @Test fun headingsAreFoundByFontSize() {
        val f = File(tmp.root, "h.pdf")
        PDDocument().use { doc ->
            fun page(blocks: List<Triple<String, Float, Float>>) {
                val p = PDPage(PDRectangle.A4); doc.addPage(p)
                PDPageContentStream(doc, p).use { cs ->
                    for ((t, size, y) in blocks) { cs.beginText(); cs.setFont(PDType1Font.HELVETICA, size); cs.newLineAtOffset(50f, y); cs.showText(t); cs.endText() }
                }
            }
            val body = (0 until 12).map { Triple("This is ordinary body text on the page number $it.", 11f, 600f - it * 16f) }
            page(listOf(Triple("Introduction", 26f, 780f), Triple("Background", 18f, 740f)) + body)
            page(listOf(Triple("Results", 26f, 780f)) + body)
            doc.save(f)
        }
        val found = PdfOps.load(f).use { HeadingDetector.detect(it) }
        assertEquals(listOf("Introduction", "Background", "Results"), found.map { it.title })
        assertEquals(listOf(0, 1, 0), found.map { it.level }); assertEquals(listOf(0, 0, 1), found.map { it.page })
    }

    // ---- links
    private fun textPdf(): File {
        val f = File(tmp.root, "t.pdf")
        PDDocument().use { doc ->
            val p = PDPage(PDRectangle.A4); doc.addPage(p)
            PDPageContentStream(doc, p).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(50f, 700f)
                cs.showText("Visit https://example.com/docs. Also www.forge.app or mail me@x.org now"); cs.endText()
            }
            doc.save(f)
        }
        return f
    }

    @Test fun urlNormalisingRejectsDangerousSchemes() {
        assertEquals("https://example.com", PdfLinks.normalizeUrl("example.com"))
        assertEquals("mailto:a@b.co", PdfLinks.normalizeUrl("a@b.co"))
        assertEquals("http://x.org/a?b=1", PdfLinks.normalizeUrl(" http://x.org/a?b=1 "))
        assertNull(PdfLinks.normalizeUrl("javascript:alert(1)")); assertNull(PdfLinks.normalizeUrl("file:///etc/passwd"))
        assertNull(PdfLinks.normalizeUrl("")); assertNull(PdfLinks.normalizeUrl("two words.com")); assertNull(PdfLinks.normalizeUrl("nodots"))
    }

    @Test fun addEditDeleteLinks() {
        val src = PdfFixtures.create(tmp.newFile("l.pdf"), pages = 2)
        val withLink = save(src) { PdfLinks.apply(it, LinkEdits(additions = listOf(NewLink(1, 0.2f, 0.3f, 0.6f, 0.4f, "forge.app")))) }
        val l = PdfOps.load(withLink).use { PdfLinks.list(it) }
        assertEquals(1, l.size); assertEquals(1, l[0].page); assertEquals("https://forge.app", l[0].uri)
        assertEquals(0.2f, l[0].left, 0.01f); assertEquals(0.3f, l[0].top, 0.01f); assertEquals(0.6f, l[0].right, 0.01f); assertEquals(0.4f, l[0].bottom, 0.01f)
        val edited = save(withLink) { PdfLinks.apply(it, LinkEdits(urlChanges = mapOf((1 to 0) to "https://new.example/x"))) }
        assertEquals("https://new.example/x", PdfOps.load(edited).use { PdfLinks.list(it) }.single().uri)
        val deleted = save(edited) { PdfLinks.apply(it, LinkEdits(deletes = setOf(1 to 0))) }
        assertTrue(PdfOps.load(deleted).use { PdfLinks.list(it) }.isEmpty())
        try { save(src) { PdfLinks.apply(it, LinkEdits(additions = listOf(NewLink(0, 0f, 0f, 1f, 1f, "javascript:x")))) }; fail() } catch (_: ToolException) {}
    }

    @Test fun rectanglesMapCorrectlyOnRotatedPages() {
        val src = PdfFixtures.create(tmp.newFile("rot.pdf"), rotations = mapOf(0 to 90))
        val out = save(src) { PdfLinks.apply(it, LinkEdits(additions = listOf(NewLink(0, 0.1f, 0.2f, 0.5f, 0.3f, "https://a.example")))) }
        val l = PdfOps.load(out).use { PdfLinks.list(it) }.single()
        assertEquals(0.1f, l.left, 0.01f); assertEquals(0.2f, l.top, 0.01f); assertEquals(0.5f, l.right, 0.01f); assertEquals(0.3f, l.bottom, 0.01f)
    }

    @Test fun plainTextAddressesBecomeLinksAtTheRightPlace() {
        val src = textPdf()
        val found = PdfOps.load(src).use { PdfLinks.detect(it) }
        assertEquals(listOf("https://example.com/docs", "https://www.forge.app", "mailto:me@x.org"), found.map { it.url })
        // text baseline is 700pt from the bottom of an 842pt page: top fraction about 0.17
        assertTrue(found.all { it.top in 0.14f..0.18f && it.bottom in 0.16f..0.19f && it.left < it.right })
        assertTrue(found[0].left < found[1].left && found[1].left < found[2].left)
        val out = save(src) { val (_, _, added) = PdfLinks.apply(it, LinkEdits(autoDetect = true)); assertEquals(3, added) }
        assertEquals(3, PdfOps.load(out).use { PdfLinks.list(it) }.size)
        // running it again adds nothing: they are already links
        val again = save(out) { assertEquals(0, PdfLinks.apply(it, LinkEdits(autoDetect = true)).third) }
        assertEquals(3, PdfOps.load(again).use { PdfLinks.list(it) }.size)
    }

    @Test fun editsSurviveEncoding() {
        val e = LinkEdits(mapOf((0 to 2) to "https://x.example"), setOf(1 to 0), listOf(NewLink(2, 0.1f, 0.2f, 0.3f, 0.4f, "a.example")), true)
        val back = PdfLinks.decodeEdits(PdfLinks.encodeEdits(e))
        assertEquals(e.urlChanges, back.urlChanges); assertEquals(e.deletes, back.deletes); assertEquals(e.additions, back.additions); assertTrue(back.autoDetect)
    }
}
