package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.core.pdf.PageSpec
import com.namanbansal.forge.core.pdf.PdfAssembler
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/**
 * P5 Extract pages. The page grid and the range text are two views of ONE selection: tapping pages rewrites the text,
 * and a valid text selects those pages. Pages are written in their original order. Both survive process death.
 */
class ExtractViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P5"

    private val selectedState = persistedIntSet("extract.selected")
    private val textState = persistedString("extract.text", "")
    private val viewState = persistedInt("extract.view", 0)
    val selected: StateFlow<Set<Int>> = selectedState.flow
    val rangeText: StateFlow<String> = textState.flow
    /** 0 = pick on the grid, 1 = type ranges. */
    val view: StateFlow<Int> = viewState.flow

    private var pageCount = 0

    override fun onPdfReady(source: PdfSource) {
        pageCount = source.pageCount
        if (stateRestored) selectedState.value = selectedState.value.filter { it in 0 until pageCount }.toSet()
    }

    fun setView(v: Int) { viewState.value = v }

    fun toggle(page: Int) {
        selectedState.update { if (page in it) it - page else it + page }
        textState.value = PageRanges.format(selectedState.value)
    }

    fun selectAll() { selectedState.value = (0 until pageCount).toSet(); textState.value = PageRanges.format(selectedState.value) }

    fun selectNone() { selectedState.value = emptySet(); textState.value = "" }

    fun setText(text: String) {
        textState.value = text
        (PageRanges.parse(text, pageCount) as? PageRanges.Result.Ok)?.let { selectedState.value = it.sortedUnique.toSet() }
        if (text.isBlank()) selectedState.value = emptySet()
    }

    /** Message for the text field, or null when the text is empty or valid. */
    fun textError(text: String = textState.value): String? =
        if (text.isBlank()) null else (PageRanges.parse(text, pageCount) as? PageRanges.Result.Error)?.message

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val pages = selectedState.value.filter { it in 0 until src.pageCount }.sorted()
        if (pages.isEmpty()) throw ToolException("Select at least one page first.")
        val saved = scope.save("extracted", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            PdfAssembler.build(
                listOf(src.file), pages.map { PageSpec.FromSource(0, it) }, out, src.protection,
                checkCancelled = { ctx.ensureActive() }, onProgress = { scope.progress(it) },
            )
        }
        return ToolOutcome(saved, headline = "Pages extracted", detail = "${pagesLabel(pages.size)} (${PageRanges.format(pages)})")
    }
}
