package com.namanbansal.forge.tools.pdf

import com.namanbansal.forge.core.pdf.PageSpec
import com.namanbansal.forge.core.friendlyMessage
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.EditorState
import com.namanbansal.forge.core.pdf.PageEditor
import com.namanbansal.forge.core.pdf.PdfAssembler
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P3 Page manager. All edits go to a lightweight [PageEditor] (page indexes + rotations + undo history); the PDF itself is
 * only touched once, when saving, so 300+ pages stay smooth. The page list and selection survive process death.
 */
class PageManagerViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P3"

    // Survives process death; reset automatically when the user picks another file.
    private val savedEditor = persisted("pm.state", IntArray(0), decode = { it as? IntArray })
    private var editor = PageEditor(0)
    private val _state = MutableStateFlow(editor.state())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** True for the next run when "Extract" (selected pages only) was pressed instead of Save. */
    private var extractOnly = false

    override fun onPdfReady(source: PdfSource) {
        editor = PageEditor(source.pageCount)
        if (stateRestored) editor.restore(savedEditor.value)
        publish()
    }

    override fun onInputsReset() {
        editor = PageEditor(0)
        _state.value = editor.state()
        super.onInputsReset()
    }

    private fun publish() {
        _state.value = editor.state()
        savedEditor.value = editor.encode()
    }

    fun toggle(id: Int) { editor.toggle(id); publish() }
    fun selectAll() { editor.selectAll(); publish() }
    fun selectNone() { editor.clearSelection(); publish() }
    fun invertSelection() {
        val st = editor.state()
        editor.select(st.pages.map { it.id }.toSet() - st.selected); publish()
    }

    fun rotateSelected() { if (editor.rotateSelected(90) > 0) publish() }

    fun deleteSelected() {
        if (editor.state().selected.size >= editor.state().pages.size) { notify("A PDF needs at least one page."); return }
        if (editor.deleteSelected() > 0) publish()
    }

    fun duplicateSelected() { if (editor.duplicateSelected() > 0) publish() }

    fun undo() { if (editor.undo()) publish() }
    fun redo() { if (editor.redo()) publish() }

    // Drag: one history step per drag; the grid shows every intermediate order.
    fun dragStart() { editor.beginDrag() }
    fun dragMove(from: Int, to: Int) { editor.moveLive(from, to); _state.value = editor.state() }
    fun dragEnd() { editor.endDrag(); publish() }
    fun moveByOne(from: Int, to: Int) { editor.move(from, to); publish() }

    /** Extract button: save only the selected pages as a new PDF. */
    fun extractSelected() {
        if (editor.state().selected.isEmpty()) { notify("Select the pages to extract first."); return }
        extractOnly = true
        start()
    }

    /** What "Share" hands to the share sheet: the selected pages as a PDF, or the one selected page as a PNG (with its turn applied). */
    class SharePayload(val file: java.io.File, val name: String, val mime: String)

    suspend fun stageSelected(asImage: Boolean): SharePayload? {
        val src = source.value ?: return null
        val specs = editor.specs(true)
        if (specs.isEmpty()) { notify("Select the pages to share first."); return null }
        if (asImage && specs.size != 1) { notify("Select one page to share it as an image."); return null }
        val original = inputs.value.firstOrNull()?.name ?: "document.pdf"
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val pages = specs.map { (it as PageSpec.FromSource).page }
                val name = if (asImage) com.namanbansal.forge.core.share.PageSharer.imageName(original, pages.first())
                else com.namanbansal.forge.core.share.PageSharer.pdfName(original, pages)
                val dst = com.namanbansal.forge.core.share.ShareStaging.target(app.cacheDir, name)
                if (asImage) com.namanbansal.forge.core.share.PageSharer.pageImage(src.file, pages.first(), dst, rotation = (specs.first() as PageSpec.FromSource).addRotation)
                else com.namanbansal.forge.core.share.PageSharer.specsPdf(src.file, specs, dst, src.protection)
                SharePayload(dst, name, if (asImage) "image/png" else "application/pdf")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (t: Throwable) { notify(t.friendlyMessage()); null }
    }

    override fun backToOptions() { extractOnly = false; super.backToOptions() }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val onlySelected = extractOnly
        val specs = editor.specs(onlySelected)
        if (specs.isEmpty()) throw ToolException("There are no pages to save.")
        val saved = scope.save(if (onlySelected) "extracted" else "edited", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            PdfAssembler.build(
                sources = listOf(src.file), specs = specs, out = out, protection = src.protection,
                checkCancelled = { ctx.ensureActive() }, onProgress = { scope.progress(it) },
            )
        }
        extractOnly = false
        return ToolOutcome(saved, headline = if (onlySelected) "Pages extracted" else "Saved successfully", detail = pagesLabel(specs.size))
    }

}
