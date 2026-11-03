package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.ZipWriter
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.Bookmark
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfSplitter
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import com.namanbansal.forge.core.pdf.SplitPart
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SplitMode(val label: String) { Ranges("Ranges"), EveryN("Every N"), Single("Pages"), Bookmarks("Bookmarks") }

/** The files a split would make, or why it cannot be made yet. */
sealed interface SplitPlan {
    class Valid(val parts: List<SplitPart>) : SplitPlan
    class Invalid(val message: String) : SplitPlan
}

/** P2 Split: four modes, a live plan (validated as you type) and many output files plus an optional ZIP. */
class SplitViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P2"

    private val modeState = persisted("split.mode", SplitMode.Ranges, encode = { it.ordinal }, decode = { SplitMode.entries.getOrNull(it as? Int ?: -1) })
    private val rangesState = persistedString("split.ranges", "")
    private val everyState = persistedInt("split.every", 1)
    private val zipState = persistedBool("split.zip", false)

    val mode: StateFlow<SplitMode> = modeState.flow
    val rangesText: StateFlow<String> = rangesState.flow
    val everyN: StateFlow<Int> = everyState.flow
    val makeZip: StateFlow<Boolean> = zipState.flow

    private val _bookmarks = MutableStateFlow<List<Bookmark>?>(null)
    /** Null while the outline is being read, empty when the PDF has no bookmarks. */
    val bookmarks: StateFlow<List<Bookmark>?> = _bookmarks.asStateFlow()

    fun setMode(m: SplitMode) { modeState.value = m }
    fun setRanges(text: String) { rangesState.value = text }
    fun setEveryN(n: Int) { everyState.value = n.coerceAtLeast(1) }
    fun setMakeZip(on: Boolean) { zipState.value = on }

    override fun onPdfReady(source: PdfSource) {
        _bookmarks.value = null
        if (stateRestored) everyState.value = everyState.value.coerceIn(1, source.pageCount)
        launchBookmarks(source)
    }

    private var bookmarkJob: Job? = null

    private fun launchBookmarks(source: PdfSource) {
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch(Dispatchers.IO) {
            val marks = try { PdfSplitter.readBookmarks(source.file) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            _bookmarks.value = marks
            if (marks.isEmpty() && modeState.value == SplitMode.Bookmarks) modeState.value = SplitMode.Ranges
        }
    }

    override fun onInputsReset() {
        bookmarkJob?.cancel()
        _bookmarks.value = null
        super.onInputsReset()
    }

    fun plan(): SplitPlan {
        val total = source.value?.pageCount ?: return SplitPlan.Invalid("Open a PDF first.")
        return buildPlan(mode.value, rangesState.value, everyState.value, _bookmarks.value, total)
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val parts = when (val p = plan()) {
            is SplitPlan.Valid -> p.parts
            is SplitPlan.Invalid -> throw ToolException(p.message)
        }
        val outputs = ArrayList<SavedOutput>()
        val ctx = currentCoroutineContext()
        PdfSplitter.run(
            source = src.file, parts = parts, protection = src.protection,
            checkCancelled = { scope.checkCancelled() },
            onProgress = { scope.progress(it * if (zipState.value) 0.9f else 1f) },
        ) { part, write ->
            outputs += scope.save(part.label, "pdf", "application/pdf") { out -> write(out) }
        }
        var archive: SavedOutput? = null
        if (zipState.value) {
            archive = scope.save("split", "zip", "application/zip") { out ->
                ZipWriter.write(out, outputs.map { o -> o.displayName to { app.contentResolver.openInputStream(o.uri) } }, checkCancelled = { ctx.ensureActive() })
            }
        }
        scope.progress(1f)
        val files = if (outputs.size == 1) "1 file" else "${outputs.size} files"
        return ToolOutcome(outputs.first(), headline = "Split into $files", detail = pagesLabel(src.pageCount) + if (archive != null) " · ZIP included" else "", outputs = outputs, archive = archive)
    }

    companion object {
        /** Pure planning, shared with the UI so the chips and the real run always agree. */
        fun buildPlan(mode: SplitMode, rangesText: String, everyN: Int, bookmarks: List<Bookmark>?, total: Int): SplitPlan = try {
            val parts = when (mode) {
                SplitMode.Ranges -> when (val r = PageRanges.parse(rangesText, total)) {
                    is PageRanges.Result.Ok -> PdfSplitter.byRanges(r.ranges)
                    is PageRanges.Result.Error -> return SplitPlan.Invalid(if (rangesText.isBlank()) "Type the pages for each file, like 1-3, 5, 8-10." else r.message)
                }
                SplitMode.EveryN -> PdfSplitter.everyN(everyN, total)
                SplitMode.Single -> PdfSplitter.singlePages(total)
                SplitMode.Bookmarks -> when {
                    bookmarks == null -> return SplitPlan.Invalid("Reading bookmarks…")
                    else -> PdfSplitter.byBookmarks(bookmarks, total)
                }
            }
            if (parts.size > PdfSplitter.MAX_PARTS) SplitPlan.Invalid("That would make ${parts.size} files. The most is ${PdfSplitter.MAX_PARTS}.") else SplitPlan.Valid(parts)
        } catch (e: ToolException) {
            SplitPlan.Invalid(e.message ?: "Those settings can't be used.")
        }
    }
}
