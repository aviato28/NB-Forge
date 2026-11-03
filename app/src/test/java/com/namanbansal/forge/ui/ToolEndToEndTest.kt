package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.pdf.BlankSize
import com.namanbansal.forge.core.pdf.InsertPosition
import com.namanbansal.forge.tools.pdf.ExtractViewModel
import com.namanbansal.forge.tools.pdf.InsertViewModel
import com.namanbansal.forge.tools.pdf.MergeStatus
import com.namanbansal.forge.tools.pdf.MergeViewModel
import com.namanbansal.forge.tools.pdf.PageManagerViewModel
import com.namanbansal.forge.tools.pdf.RotateViewModel
import com.namanbansal.forge.tools.pdf.SplitMode
import com.namanbansal.forge.tools.pdf.SplitPlan
import com.namanbansal.forge.tools.pdf.SplitViewModel
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.zip.ZipInputStream

/** Whole-tool runs: pick -> options -> save through a fake MediaStore -> read the saved PDF back. */
@RunWith(RobolectricTestRunner::class)
class ToolEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var media: FakeMediaProvider

    @Before fun init() {
        PDFBoxResourceLoader.init(app)
        PdfBoxRasterizer.install()
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        media.dir = tmp.newFolder("media")
    }

    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun pump(timeoutMs: Long = 60_000, until: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not reached in time", until())
    }

    private fun pdf(name: String, pages: Int, pw: String? = null, unique: Boolean = true): PickedFile {
        val f = PdfFixtures.withText(File(tmp.root, name), pages, userPassword = pw, uniqueHeights = unique)
        return PickedFile(Uri.fromFile(f), name, f.length(), "application/pdf")
    }

    private fun h(i: Int) = 300 + i
    private fun saved(): List<File> = media.rows.values.map { it.file }
    private fun heights(f: File, pw: String = "") = PdfFixtures.pageHeights(f, pw)

    private fun runToResult(vm: com.namanbansal.forge.core.ToolViewModel) {
        vm.start()
        pump { vm.phase.value == ToolPhase.Result || vm.phase.value == ToolPhase.Failed }
        assertEquals(vm.error.value, ToolPhase.Result, vm.phase.value)
    }

    // ---- Merge ------------------------------------------------------------------------------------------------

    @Test fun mergeEndToEndWithEncryptedFileReorderRemoveAndBlockers() {
        val vm = MergeViewModel(app, SavedStateHandle())
        val a = pdf("a.pdf", 2); val b = pdf("b.pdf", 3, pw = "pw"); val c = pdf("c.pdf", 1)
        val bad = PickedFile(Uri.fromFile(PdfFixtures.corrupt(File(tmp.root, "broken.pdf"))), "broken.pdf", 2048, "application/pdf")
        vm.onFilesPicked(listOf(a, b, c, bad))
        pump { vm.items.value.size == 4 && vm.items.value.none { it.status == MergeStatus.Opening } }

        // The encrypted file asks for a password; the corrupt one is named in its error.
        assertEquals("b.pdf", vm.passwordPrompt.value!!.fileName)
        assertTrue(vm.blocker()!!.contains("password"))
        vm.submitPassword("nope"); pump { (vm.items.value[1].status as? MergeStatus.Locked)?.wrong == true }
        vm.submitPassword("pw"); pump { vm.items.value[1].status == MergeStatus.Ready }
        val failed = vm.items.value.last().status as MergeStatus.Failed
        assertTrue(failed.reason, failed.reason.contains("broken.pdf"))
        assertTrue(vm.blocker()!!.contains("can't be read"))
        vm.remove(vm.items.value.last().id)
        assertNull(vm.blocker())
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), vm.inputs.value.map { it.name })

        // Reorder: c first.
        vm.move(2, 0); vm.dragFinished()
        assertEquals(listOf("c.pdf", "a.pdf", "b.pdf"), vm.inputs.value.map { it.name })
        vm.setBlankBetween(false)
        runToResult(vm)
        val out = saved().single()
        assertEquals("c_merged.pdf", vm.result.value!!.output.displayName)
        assertEquals(listOf(h(1), h(1), h(2), h(1), h(2), h(3)), heights(out))
        assertEquals("3 files · 6 pages", vm.result.value!!.detail)
    }

    @Test fun mergeKeepsPasswordWhenEveryFileSharesIt() {
        val vm = MergeViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("p1.pdf", 2, pw = "same"), pdf("p2.pdf", 1, pw = "same")))
        pump { vm.items.value.size == 2 && vm.items.value.none { it.status == MergeStatus.Opening } }
        vm.submitPassword("same"); pump { vm.items.value[0].status == MergeStatus.Ready }
        vm.submitPassword("same"); pump { vm.items.value.all { it.status == MergeStatus.Ready } }
        runToResult(vm)
        PDDocument.load(saved().single(), "same").use { assertTrue(it.isEncrypted); assertEquals(3, it.numberOfPages) }
    }

    @Test fun mergeAddsMoreFilesLaterAndBlankPagesBetween() {
        val vm = MergeViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("one.pdf", 1)))
        pump { vm.items.value.single().status == MergeStatus.Ready }
        assertTrue(vm.blocker()!!.contains("at least two"))
        vm.addUris(listOf(pdf("two.pdf", 2).uri, pdf("three.pdf", 1).uri))
        pump { vm.items.value.size == 3 && vm.items.value.all { it.status == MergeStatus.Ready } }
        vm.setBlankBetween(true)
        runToResult(vm)
        assertEquals(listOf(h(1), h(1), h(1), h(2), h(2), h(1)), heights(saved().single()))
    }

    @Test fun mergeStateSurvivesRecreation() {
        val handle = SavedStateHandle()
        val vm = MergeViewModel(app, handle)
        vm.onFilesPicked(listOf(pdf("x1.pdf", 1), pdf("x2.pdf", 1)))
        pump { vm.items.value.size == 2 && vm.items.value.all { it.status == MergeStatus.Ready } }
        vm.setBlankBetween(true)
        val again = MergeViewModel(app, handle) // same handle = process death + restore
        pump { again.items.value.size == 2 && again.items.value.all { it.status == MergeStatus.Ready } }
        assertTrue(again.blankBetween.value)
        assertEquals(listOf("x1.pdf", "x2.pdf"), again.inputs.value.map { it.name })
    }

    // ---- Split ------------------------------------------------------------------------------------------------

    @Test fun splitRangesSavesEveryFileAndZip() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("report.pdf", 10)))
        pump { vm.source.value != null && vm.bookmarks.value != null }
        vm.setRanges("1-3, 5, 8-10"); vm.setMakeZip(true)
        runToResult(vm)
        val r = vm.result.value!!
        assertEquals(listOf("report_p1-3.pdf", "report_p5.pdf", "report_p8-10.pdf"), r.outputs.map { it.displayName })
        assertEquals("Split into 3 files", r.headline)
        assertEquals("report_split.zip", r.archive!!.displayName)
        val parts = media.rows.values.filter { it.values.getAsString("mime_type") == "application/pdf" }.map { it.file }
        assertEquals(listOf(3, 1, 3), parts.map { heights(it).size })
        val names = ArrayList<String>()
        ZipInputStream(media.rows.values.first { it.values.getAsString("_display_name") == "report_split.zip" }.file.inputStream()).use { z ->
            var e = z.nextEntry; while (e != null) { names += e.name; e = z.nextEntry }
        }
        assertEquals(r.outputs.map { it.displayName }, names)
    }

    @Test fun splitModesPlanAndBookmarksDisabledWhenAbsent() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("nomarks.pdf", 7)))
        pump { vm.bookmarks.value != null }
        assertEquals(emptyList<Any>(), vm.bookmarks.value)
        vm.setMode(SplitMode.EveryN); vm.setEveryN(3)
        assertEquals(3, (vm.plan() as SplitPlan.Valid).parts.size)
        vm.setMode(SplitMode.Single)
        assertEquals(7, (vm.plan() as SplitPlan.Valid).parts.size)
        vm.setMode(SplitMode.Bookmarks)
        assertTrue((vm.plan() as SplitPlan.Invalid).message.contains("no bookmarks"))
        vm.setMode(SplitMode.Ranges); vm.setRanges("9")
        assertTrue((vm.plan() as SplitPlan.Invalid).message.contains("doesn't exist"))
        vm.setRanges("")
        assertTrue(vm.plan() is SplitPlan.Invalid)
    }

    @Test fun splitCancelRemovesTheFilesAlreadySaved() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("big.pdf", 120, unique = false)))
        pump { vm.source.value != null && vm.bookmarks.value != null }
        vm.setMode(SplitMode.Single)
        val t0 = System.currentTimeMillis()
        vm.start()
        pump { media.rows.isNotEmpty() || vm.phase.value == ToolPhase.Result || vm.phase.value == ToolPhase.Failed }
        println("first output after ${System.currentTimeMillis() - t0} ms, phase=${vm.phase.value}")
        assertTrue("${vm.phase.value} ${vm.error.value}", vm.phase.value != ToolPhase.Failed)
        vm.cancel()
        try { pump(10_000) { vm.phase.value != ToolPhase.Running } } catch (e: AssertionError) {
            Thread.getAllStackTraces().forEach { (t, st) -> if (st.any { it.className.contains("namanbansal") }) println("T ${t.name}: " + st.filter { it.className.contains("namanbansal") || it.className.contains("Shadow") }.take(8).joinToString(" | ")) }
            println("rows=${media.rows.size} phase=${vm.phase.value}"); throw e
        }
        assertTrue("${vm.phase.value} ${vm.error.value}", vm.phase.value == ToolPhase.Options || vm.phase.value == ToolPhase.Result)
        if (vm.phase.value == ToolPhase.Options) assertTrue("partial outputs must be deleted", media.rows.isEmpty())
    }

    @Test fun splitKeepsPasswordOnParts() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("sec.pdf", 3, pw = "k")))
        pump { vm.passwordPrompt.value != null }
        vm.submitPassword("k")
        pump { vm.source.value != null && vm.bookmarks.value != null }
        vm.setMode(SplitMode.Single)
        runToResult(vm)
        assertEquals(3, saved().size)
        saved().forEach { PDDocument.load(it, "k").use { d -> assertTrue(d.isEncrypted) } }
    }

    // ---- Page manager ----------------------------------------------------------------------------------------

    @Test fun pageManagerSaveMatchesTheEditsAndUndoWorks() {
        val vm = PageManagerViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("pm.pdf", 8)))
        pump { vm.source.value != null && vm.state.value.pages.size == 8 }
        assertFalse(vm.state.value.dirty)
        val ids = vm.state.value.pages.map { it.id }
        vm.dragStart(); vm.dragMove(7, 0); vm.dragEnd()                     // 8,1,2,...
        vm.toggle(ids[1]); vm.duplicateSelected()                            // page "2" twice, copy selected
        vm.rotateSelected()
        vm.selectNone(); vm.toggle(ids[2]); vm.deleteSelected()              // page "3" gone
        assertTrue(vm.state.value.dirty)
        runToResult(vm)
        val out = saved().single()
        assertEquals("pm_edited.pdf", vm.result.value!!.output.displayName)
        assertEquals(listOf(h(8), h(1), h(2), h(2), h(4), h(5), h(6), h(7)), heights(out))
        assertEquals(listOf(0, 0, 0, 90, 0, 0, 0, 0), PdfFixtures.pageRotations(out))
        // Undo everything.
        vm.backToOptions()
        repeat(10) { vm.undo() }
        assertFalse(vm.state.value.dirty)
        assertEquals((0 until 8).toList(), vm.state.value.pages.map { it.source })
    }

    @Test fun pageManagerExtractSelectionAndPasswordAndRestore() {
        val handle = SavedStateHandle()
        val vm = PageManagerViewModel(app, handle)
        vm.onFilesPicked(listOf(pdf("pe.pdf", 6, pw = "q")))
        pump { vm.passwordPrompt.value != null }
        vm.submitPassword("q")
        pump { vm.state.value.pages.size == 6 }
        val ids = vm.state.value.pages.map { it.id }
        vm.toggle(ids[4]); vm.toggle(ids[1]); vm.rotateSelected()
        // Process death: a new view model with the same saved state gets the same edits back (needs the password again).
        val again = PageManagerViewModel(app, handle)
        pump { again.passwordPrompt.value != null }
        again.submitPassword("q")
        pump { again.source.value != null }
        assertEquals(6, again.state.value.pages.size); assertEquals(2, again.state.value.selected.size)
        assertEquals(listOf(90, 90), again.state.value.pages.filter { it.id in again.state.value.selected }.map { it.rotation })
        again.extractSelected()
        pump { again.phase.value == ToolPhase.Result }
        val out = saved().single()
        assertEquals("pe_extracted.pdf", again.result.value!!.output.displayName)
        assertEquals(listOf(h(2), h(5)), heights(out, "q"))
        PDDocument.load(out, "q").use { assertTrue(it.isEncrypted) }
    }

    // ---- Extract ----------------------------------------------------------------------------------------------

    @Test fun extractGridAndRangeTextStayInSyncAndSaveSortedPages() {
        val vm = ExtractViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("ex.pdf", 9)))
        pump { vm.source.value != null }
        vm.toggle(4); vm.toggle(0); vm.toggle(1)
        assertEquals("1-2, 5", vm.rangeText.value)
        vm.setText("7-9, 2")
        assertEquals(setOf(6, 7, 8, 1), vm.selected.value)
        vm.setText("7-99")
        assertTrue(vm.textError()!!.contains("doesn't exist"))
        assertEquals(setOf(6, 7, 8, 1), vm.selected.value) // invalid text leaves the selection alone
        vm.setText("")
        assertTrue(vm.selected.value.isEmpty())
        vm.setText("8, 2-3")
        runToResult(vm)
        assertEquals(listOf(h(2), h(3), h(8)), heights(saved().single()))
        assertEquals("3 pages (2-3, 8)", vm.result.value!!.detail)
    }

    // ---- Insert -----------------------------------------------------------------------------------------------

    @Test fun insertBlankAndFromPdfEndToEnd() {
        val vm = InsertViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("base.pdf", 4)))
        pump { vm.source.value != null }
        vm.setBlankCount(2); vm.setPosition(InsertPosition.AfterPage); vm.setPositionPage(2)
        runToResult(vm)
        assertEquals(listOf(h(1), h(2), h(2), h(2), h(3), h(4)), heights(saved().last()))
        assertEquals("2 pages after page 2 · 6 pages total", vm.result.value!!.detail)

        vm.backToOptions()
        vm.setBlankSize(BlankSize.Letter); vm.setPosition(InsertPosition.Start); vm.setBlankCount(1)
        runToResult(vm)
        assertEquals(listOf(792, h(1), h(2), h(3), h(4)), heights(saved().last()))

        vm.backToOptions()
        vm.setFromPdf(true)
        vm.pickOther(pdf("other.pdf", 5).uri)
        pump { vm.other.value != null }
        vm.setOtherRange("5, 1"); vm.setPosition(InsertPosition.End)
        runToResult(vm)
        assertEquals(listOf(h(1), h(2), h(3), h(4), h(5), h(1)), heights(saved().last()))
    }

    @Test fun insertFromPdfNeedsAFileAndAValidRangeAndKeepsPassword() {
        val vm = InsertViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("lockedbase.pdf", 3, pw = "bp")))
        pump { vm.passwordPrompt.value != null }
        vm.submitPassword("bp"); pump { vm.source.value != null }
        vm.setFromPdf(true)
        assertTrue(vm.blocker()!!.contains("Choose the PDF"))
        vm.pickOther(pdf("lockedother.pdf", 2, pw = "op").uri)
        pump { vm.otherPrompt.value != null }
        vm.submitOtherPassword("op"); pump { vm.other.value != null }
        vm.setOtherRange("9")
        assertTrue(vm.blocker()!!.contains("doesn't exist"))
        vm.setOtherRange("")
        assertNull(vm.blocker())
        assertEquals(2, vm.insertCount())
        runToResult(vm)
        PDDocument.load(saved().single(), "bp").use { assertTrue(it.isEncrypted); assertEquals(5, it.numberOfPages) }
    }

    // ---- Rotate keeps the password ---------------------------------------------------------------------------

    @Test fun rotateKeepsTheOriginalPassword() {
        val vm = RotateViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("rot.pdf", 2, pw = "rp")))
        pump { vm.passwordPrompt.value != null }
        vm.submitPassword("rp"); pump { vm.source.value != null }
        vm.rotateSelected(90)
        runToResult(vm)
        PDDocument.load(saved().single(), "rp").use { d -> assertTrue(d.isEncrypted); assertEquals(listOf(90, 90), d.pages.map { it.rotation }) }
    }

    @Test fun rotateSelectionAndTurnsSurviveRecreation() {
        val handle = SavedStateHandle()
        val vm = RotateViewModel(app, handle)
        vm.onFilesPicked(listOf(pdf("rr.pdf", 4)))
        pump { vm.source.value != null }
        vm.selectNone(); vm.togglePage(2); vm.rotateSelected(90)
        val again = RotateViewModel(app, handle)
        pump { again.source.value != null }
        assertEquals(setOf(2), again.selected.value)
        assertEquals(mapOf(2 to 90), again.rotations.value)
    }

    // ---- Viewer -----------------------------------------------------------------------------------------------

    @Test fun viewerSearchesAndRemembersWhereYouWere() {
        val handle = SavedStateHandle()
        val vm = ViewerViewModel(app, handle)
        val f = PdfFixtures.withText(File(tmp.root, "v.pdf"), 6, body = { p -> if (p == 2 || p == 5) listOf("the final figures") else listOf("nothing") })
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "v.pdf", f.length(), "application/pdf")))
        pump { vm.source.value != null && vm.aspects.value != null }
        vm.search("figures")
        pump { vm.hits.value.size == 2 && !vm.searching.value }
        assertEquals(listOf(1, 4), vm.hits.value.map { it.page })
        assertEquals(0, vm.current.value)
        vm.nextHit(); assertEquals(1, vm.current.value)
        vm.nextHit(); assertEquals(0, vm.current.value) // wraps
        vm.previousHit(); assertEquals(1, vm.current.value)
        vm.clearSearch(); assertTrue(vm.hits.value.isEmpty())
        vm.search("zzz"); pump { !vm.searching.value }
        assertTrue(vm.hits.value.isEmpty())

        vm.rememberPage(3); vm.toggleInvert(); vm.rememberZoom(2.5f)
        val again = ViewerViewModel(app, handle)
        pump { again.source.value != null }
        assertEquals(3, again.lastPage.value); assertTrue(again.invert.value); assertEquals(2.5f, again.zoom.value)
        assertNotNull(again.aspects)
    }
}
