package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ZipWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class PdfAssemblyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun doc(pages: Int, name: String = "d${System.nanoTime()}.pdf", pw: String? = null) =
        PdfFixtures.withText(tmp.newFile(name), pages, userPassword = pw)

    private fun build(src: File, specs: List<PageSpec>, protection: PdfProtection? = null): File {
        val out = tmp.newFile("o${System.nanoTime()}.pdf")
        out.outputStream().use { PdfAssembler.build(listOf(src), specs, it, protection) }
        return out
    }

    // heights: page i (1-based) is 300 + i points tall in the fixtures
    private fun h(i: Int) = 300 + i

    // ---- Assembler (Extract / Page manager / Insert share it) ---------------------------------------------------

    @Test fun extractsInGivenOrderWithDuplicates() {
        val src = doc(6)
        val out = build(src, listOf(4, 1, 1, 0).map { PageSpec.FromSource(0, it) })
        assertEquals(listOf(h(5), h(2), h(2), h(1)), PdfFixtures.pageHeights(out))
    }

    @Test fun rotationIsAddedToThePagesOwnRotation() {
        val src = PdfFixtures.create(tmp.newFile("r.pdf"), pages = 3, rotations = mapOf(1 to 90))
        val out = build(src, listOf(PageSpec.FromSource(0, 0, 90), PageSpec.FromSource(0, 1, 90), PageSpec.FromSource(0, 2, -90)))
        assertEquals(listOf(90, 180, 270), PdfFixtures.pageRotations(out))
    }

    @Test fun blankPagesAndMixedSources() {
        val a = doc(2); val b = doc(3)
        val out = tmp.newFile("mix.pdf")
        out.outputStream().use {
            PdfAssembler.build(
                listOf(a, b),
                listOf(PageSpec.FromSource(0, 0), PageSpec.Blank(PdfAssembler.A4_WIDTH, PdfAssembler.A4_HEIGHT), PageSpec.FromSource(1, 2), PageSpec.Blank(612f, 792f)),
                it,
            )
        }
        assertEquals(listOf(h(1), 842, h(3), 792), PdfFixtures.pageHeights(out))
    }

    @Test fun badSpecsAreFriendlyErrors() {
        val src = doc(2)
        try { build(src, listOf(PageSpec.FromSource(0, 5))); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("doesn't exist")) }
        try { build(src, listOf(PageSpec.FromSource(3, 0))); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("isn't open")) }
        try { build(src, emptyList()); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("no pages")) }
    }

    @Test fun corruptSourceNamesTheFile() {
        val bad = PdfFixtures.corrupt(tmp.newFile("broken.pdf"))
        try { PdfAssembler(listOf(bad), listOf("broken.pdf")); fail() } catch (e: ToolException) { assertTrue(e.message!!, e.message!!.contains("broken.pdf")) }
    }

    @Test fun cancelStopsBetweenPagesAndProgressIsMonotonic() {
        val src = doc(40)
        val progress = ArrayList<Float>()
        var calls = 0
        try {
            PdfAssembler.build(listOf(src), (0 until 40).map { PageSpec.FromSource(0, it) }, java.io.ByteArrayOutputStream(),
                checkCancelled = { if (++calls > 10) throw CancellationException("x") }, onProgress = { progress += it })
            fail()
        } catch (_: CancellationException) {}
        assertEquals(progress.sorted(), progress)
    }

    @Test fun protectionIsReappliedWithSamePasswordAndPermissions() {
        val src = doc(3, pw = "pw1")
        val opened = runBlocking { PdfLoader.open(src, tmp.newFolder(), "pw1") } as PdfOpenResult.Ready
        assertTrue(opened.source.wasEncrypted)
        val prot = opened.source.protection!!
        assertEquals("pw1", prot.userPassword)
        val out = build(opened.source.file, listOf(PageSpec.FromSource(0, 2), PageSpec.FromSource(0, 0)), prot)
        PDDocument.load(out, "pw1").use { assertTrue(it.isEncrypted); assertEquals(2, it.numberOfPages) }
        try { PDDocument.load(out, "wrong"); fail() } catch (_: java.io.IOException) {}
        // And without protection the copy is open.
        PDDocument.load(build(opened.source.file, listOf(PageSpec.FromSource(0, 0)))).use { assertFalse(it.isEncrypted) }
    }

    @Test fun protectionForAllNeedsEverySourceEncryptedWithTheSamePassword() {
        val p1 = PdfProtection("a", -4); val p2 = PdfProtection("a", -3844); val p3 = PdfProtection("b", -4)
        fun s(p: PdfProtection?) = PdfSource(File("x"), 1, p != null, p)
        assertEquals("a", PdfProtection.forAll(listOf(s(p1), s(p2)))!!.userPassword)
        assertEquals(null, PdfProtection.forAll(listOf(s(p1), s(p3))))
        assertEquals(null, PdfProtection.forAll(listOf(s(p1), s(null))))
        assertEquals(null, PdfProtection.forAll(emptyList()))
    }

    // ---- Split ------------------------------------------------------------------------------------------------

    @Test fun splitPlans() {
        assertEquals(listOf("p1-3", "p5", "p8-10"), PdfSplitter.byRanges(listOf(0..2, 4..4, 7..9)).map { it.label })
        val n = PdfSplitter.everyN(4, 10)
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7), listOf(8, 9)), n.map { it.pages })
        assertEquals(listOf("p1-4", "p5-8", "p9-10"), n.map { it.label })
        assertEquals(listOf("p1", "p2", "p3"), PdfSplitter.singlePages(3).map { it.label })
        assertEquals(1, PdfSplitter.everyN(50, 10).size)
        try { PdfSplitter.everyN(0, 10); fail() } catch (_: ToolException) {}
    }

    @Test fun splitByBookmarkPlanHandlesFrontMatterAndSamePageMarks() {
        val parts = PdfSplitter.byBookmarks(listOf(Bookmark("Ch 2", 4), Bookmark("Ch 1", 2), Bookmark("Dup of Ch1", 2), Bookmark("Bad", 99)), 8)
        assertEquals(listOf("0_start", "1_Ch 1", "2_Ch 2"), parts.map { it.label })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5, 6, 7)), parts.map { it.pages })
        try { PdfSplitter.byBookmarks(emptyList(), 5); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("no bookmarks")) }
    }

    private fun docWithBookmarks(): File {
        val f = doc(9)
        PDDocument.load(f).use { d ->
            val outline = PDDocumentOutline(); d.documentCatalog.documentOutline = outline
            for ((title, page) in listOf("Intro" to 0, "Methods" to 3, "Results / Data" to 6)) {
                val item = PDOutlineItem(); item.title = title
                item.destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().also { it.page = d.getPage(page) }
                outline.addLast(item)
            }
            d.save(f)
        }
        return f
    }

    @Test fun readsTopLevelBookmarksAndNoneWhenAbsent() {
        val marks = PdfSplitter.readBookmarks(docWithBookmarks())
        assertEquals(listOf("Intro" to 0, "Methods" to 3, "Results / Data" to 6), marks.map { it.title to it.page })
        assertTrue(PdfSplitter.readBookmarks(doc(3)).isEmpty())
        assertTrue(PdfSplitter.readBookmarks(PdfFixtures.corrupt(tmp.newFile("c.pdf"))).isEmpty())
    }

    private fun runSplit(src: File, parts: List<SplitPart>, protection: PdfProtection? = null): Map<String, File> {
        val outs = LinkedHashMap<String, File>()
        runBlocking {
            PdfSplitter.run(src, parts, protection, checkCancelled = {}, onProgress = {}) { part, write ->
                val f = tmp.newFile("part-${part.label.replace(Regex("[^A-Za-z0-9-]"), "_")}-${outs.size}.pdf")
                f.outputStream().use { write(it) }
                outs[part.label] = f
            }
        }
        return outs
    }

    @Test fun splitByRangesWritesEachRangeAsItsOwnFile() {
        val outs = runSplit(doc(10), PdfSplitter.byRanges(listOf(0..2, 4..4, 7..9)))
        assertEquals(listOf(listOf(h(1), h(2), h(3)), listOf(h(5)), listOf(h(8), h(9), h(10))), outs.values.map { PdfFixtures.pageHeights(it) })
    }

    @Test fun splitEveryNAndSinglePages() {
        val src = doc(7)
        val every = runSplit(src, PdfSplitter.everyN(3, 7))
        assertEquals(listOf(3, 3, 1), every.values.map { PdfFixtures.pageHeights(it).size })
        val single = runSplit(src, PdfSplitter.singlePages(7))
        assertEquals(7, single.size)
        assertEquals((1..7).map { listOf(h(it)) }, single.values.map { PdfFixtures.pageHeights(it) })
    }

    @Test fun splitByBookmarksUsesRealOutline() {
        val src = docWithBookmarks()
        val parts = PdfSplitter.byBookmarks(PdfSplitter.readBookmarks(src), 9)
        val outs = runSplit(src, parts)
        assertEquals(listOf(3, 3, 3), outs.values.map { PdfFixtures.pageHeights(it).size })
        assertEquals(h(4), PdfFixtures.pageHeights(outs.values.elementAt(1)).first())
        assertTrue(parts.last().label.contains("Results   Data") || parts.last().label.contains("Results Data"))
    }

    @Test fun splitKeepsPasswordOnEveryPart() {
        val src = doc(4, pw = "s3cret")
        val ready = runBlocking { PdfLoader.open(src, tmp.newFolder(), "s3cret") } as PdfOpenResult.Ready
        val outs = runSplit(ready.source.file, PdfSplitter.singlePages(4), ready.source.protection)
        outs.values.forEach { PDDocument.load(it, "s3cret").use { d -> assertTrue(d.isEncrypted); assertEquals(1, d.numberOfPages) } }
    }

    @Test fun splitRefusesAbsurdPartCountAndCancels() {
        val src = doc(2)
        try { runSplit(src, (0 until 501).map { SplitPart("p$it", listOf(0)) }); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("501")) }
        try { runSplit(src, emptyList()); fail() } catch (e: ToolException) {}
        var n = 0
        try {
            runBlocking { PdfSplitter.run(src, PdfSplitter.singlePages(2), null, checkCancelled = { if (++n > 1) throw CancellationException() }, onProgress = {}) { _, w -> w(java.io.ByteArrayOutputStream()) } }
            fail()
        } catch (_: CancellationException) {}
    }

    @Test fun zipContainsEveryEntryWithUniqueNames() {
        val bos = java.io.ByteArrayOutputStream()
        ZipWriter.write(bos, listOf("a.pdf" to { "one".byteInputStream() }, "a.pdf" to { "two".byteInputStream() }, "b.pdf" to { "three".byteInputStream() }))
        val names = ArrayList<String>()
        ZipInputStream(bos.toByteArray().inputStream()).use { z -> var e = z.nextEntry; while (e != null) { names += e.name; e = z.nextEntry } }
        assertEquals(listOf("a.pdf", "a (1).pdf", "b.pdf"), names)
    }

    // ---- Merge ------------------------------------------------------------------------------------------------

    private fun merge(inputs: List<MergeInput>, blank: Boolean = false, protection: PdfProtection? = null): File {
        val out = tmp.newFile("m${System.nanoTime()}.pdf")
        out.outputStream().use { PdfMerger.merge(inputs, it, blank, protection, tempDir = tmp.newFolder()) }
        return out
    }

    @Test fun mergesThreeFilesInOrderAndMixedSizes() {
        val a = doc(2); val b = PdfFixtures.create(tmp.newFile("lan.pdf"), pages = 2, sizes = listOf(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER)); val c = doc(3)
        val out = merge(listOf(MergeInput(a, "a"), MergeInput(b, "b"), MergeInput(c, "c")))
        assertEquals(listOf(h(1), h(2), 792, 792, h(1), h(2), h(3)), PdfFixtures.pageHeights(out))
    }

    @Test fun mergeCanInsertBlankPagesBetweenFiles() {
        val out = merge(listOf(MergeInput(doc(2), "a"), MergeInput(doc(1), "b"), MergeInput(doc(1), "c")), blank = true)
        // blanks copy the size of the previous file's last page
        assertEquals(listOf(h(1), h(2), h(2), h(1), h(1), h(1)), PdfFixtures.pageHeights(out))
    }

    @Test fun mergeHandles350PagesAcrossFiles() {
        val out = merge(listOf(MergeInput(doc(150), "a"), MergeInput(doc(120), "b"), MergeInput(doc(80), "c")))
        assertEquals(350, PdfFixtures.pageHeights(out).size)
    }

    @Test fun mergeNamesTheCorruptFileAndNeedsTwoInputs() {
        val bad = PdfFixtures.corrupt(tmp.newFile("bad_one.pdf"))
        try { merge(listOf(MergeInput(doc(1), "good.pdf"), MergeInput(bad, "bad_one.pdf"))); fail() } catch (e: ToolException) { assertTrue(e.message!!, e.message!!.contains("bad_one.pdf")) }
        try { merge(listOf(MergeInput(doc(1), "only.pdf"))); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("at least two")) }
    }

    @Test fun mergeWithDecryptedEncryptedInputKeepsPasswordWhenAllShareIt() {
        val locked = doc(2, pw = "pw")
        val ready = runBlocking { PdfLoader.open(locked, tmp.newFolder(), "pw") } as PdfOpenResult.Ready
        val ready2 = runBlocking { PdfLoader.open(doc(1, pw = "pw"), tmp.newFolder(), "pw") } as PdfOpenResult.Ready
        val prot = PdfProtection.forAll(listOf(ready.source, ready2.source))
        val out = merge(listOf(MergeInput(ready.source.file, "a"), MergeInput(ready2.source.file, "b")), protection = prot)
        PDDocument.load(out, "pw").use { assertEquals(3, it.numberOfPages) }
        // an encrypted file is rejected by the merger itself (callers must decrypt first, which PdfLoader does)
        try { merge(listOf(MergeInput(locked, "locked.pdf"), MergeInput(doc(1), "b"))); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("locked.pdf")) }
    }

    @Test fun mergeCancelsBetweenFiles() {
        var n = 0
        try {
            PdfMerger.merge(listOf(MergeInput(doc(1), "a"), MergeInput(doc(1), "b"), MergeInput(doc(1), "c")), java.io.ByteArrayOutputStream(), checkCancelled = { if (++n > 1) throw CancellationException() })
            fail()
        } catch (_: CancellationException) {}
    }
}
