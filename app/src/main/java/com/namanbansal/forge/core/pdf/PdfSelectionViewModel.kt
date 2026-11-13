package com.namanbansal.forge.core.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.StateFlow

/** A one-PDF tool that acts on some pages: keeps the page selection (grid and range text in sync) across rotation and process death. */
abstract class PdfSelectionViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    // Optional page selection shared by the tools that act on some pages (Watermark, Page numbers, Crop...).
    private val selState = persistedIntSet("pdf.sel")
    private val selText = persistedString("pdf.selText", "")
    val selection: StateFlow<Set<Int>> = selState.flow
    val selectionText: StateFlow<String> = selText.flow
    protected var pageCount = 0
        private set

    fun setSelection(pages: Set<Int>) { selState.value = pages; selText.value = PageSelectionSync.toText(pages) }
    fun setSelectionText(text: String) {
        selText.value = text
        selState.value = PageSelectionSync.fromText(text, pageCount, selState.value).selection
    }
    fun selectionError(text: String = selText.value): String? = PageSelectionSync.error(text, pageCount)

    /** Subclasses override and call super. By default every page is selected. */
    override fun onPdfReady(source: PdfSource) {
        pageCount = source.pageCount
        if (stateRestored) selState.value = selState.value.filter { it in 0 until pageCount }.toSet() else setSelection(PageSelectionSync.all(pageCount))
    }

    /** The selected pages, or null when all pages are selected. */
    protected fun selectedOrNull(): Set<Int>? = selState.value.takeIf { it.size < pageCount }
}
