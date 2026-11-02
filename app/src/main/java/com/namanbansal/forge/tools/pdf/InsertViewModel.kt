package com.namanbansal.forge.tools.pdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.FileResolver
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.BlankSize
import com.namanbansal.forge.core.pdf.InsertPosition
import com.namanbansal.forge.core.pdf.IntakeResult
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.core.pdf.PageSpec
import com.namanbansal.forge.core.pdf.PasswordPrompt
import com.namanbansal.forge.core.pdf.PdfAssembler
import com.namanbansal.forge.core.pdf.PdfInsert
import com.namanbansal.forge.core.pdf.PdfIntake
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The second PDF whose pages are inserted. */
class InsertSource(val file: PickedFile, val source: PdfSource, internal val workFiles: List<File>)

/** P6 Insert pages: blank pages (sized like the neighbour / A4 / Letter) or pages of another PDF, at a chosen place. */
class InsertViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P6"

    private val fromPdfState = persistedBool("insert.fromPdf", false)
    private val countState = persistedInt("insert.count", 1)
    private val sizeState = persisted("insert.size", BlankSize.Neighbour, encode = { it.ordinal }, decode = { BlankSize.entries.getOrNull(it as? Int ?: -1) })
    private val positionState = persisted("insert.position", InsertPosition.End, encode = { it.ordinal }, decode = { InsertPosition.entries.getOrNull(it as? Int ?: -1) })
    private val pageState = persistedInt("insert.page", 1)
    private val rangeState = persistedString("insert.range", "")
    private val otherUriState = persistedString("insert.other", "")

    val fromPdf: StateFlow<Boolean> = fromPdfState.flow
    val blankCount: StateFlow<Int> = countState.flow
    val blankSize: StateFlow<BlankSize> = sizeState.flow
    val position: StateFlow<InsertPosition> = positionState.flow
    /** 1-based page for Before/After. */
    val positionPage: StateFlow<Int> = pageState.flow
    /** Which pages of the second PDF to insert; empty = all. */
    val otherRange: StateFlow<String> = rangeState.flow

    private val _other = MutableStateFlow<InsertSource?>(null)
    val other: StateFlow<InsertSource?> = _other.asStateFlow()
    private val _otherLoading = MutableStateFlow(false)
    val otherLoading: StateFlow<Boolean> = _otherLoading.asStateFlow()
    private val _otherPrompt = MutableStateFlow<PasswordPrompt?>(null)
    val otherPrompt: StateFlow<PasswordPrompt?> = _otherPrompt.asStateFlow()
    private var lockedCopy: File? = null
    private var lockedFile: PickedFile? = null

    fun setFromPdf(on: Boolean) { fromPdfState.value = on }
    fun setBlankCount(n: Int) { countState.value = n.coerceIn(1, MAX_BLANK) }
    fun setBlankSize(s: BlankSize) { sizeState.value = s }
    fun setPosition(p: InsertPosition) { positionState.value = p }
    fun setPositionPage(n: Int) { pageState.value = n.coerceAtLeast(1) }
    fun setOtherRange(t: String) { rangeState.value = t }

    override fun onPdfReady(source: PdfSource) {
        if (stateRestored) {
            pageState.value = pageState.value.coerceIn(1, source.pageCount)
            otherUriState.value.takeIf { it.isNotEmpty() }?.let { pickOther(Uri.parse(it)) }
        } else pageState.value = pageState.value.coerceIn(1, source.pageCount)
    }

    // ---- the second PDF ---------------------------------------------------------------------------------------

    fun pickOther(uri: Uri) {
        viewModelScope.launch {
            _otherLoading.value = true
            try {
                val picked = FileResolver.resolve(app, uri)
                val result = try { PdfIntake.open(app, picked) } catch (e: CancellationException) { throw e } catch (_: Throwable) { IntakeResult.Failed("This file couldn't be opened.") }
                handleOther(picked, result)
            } finally { _otherLoading.value = false }
        }
    }

    private fun handleOther(picked: PickedFile, result: IntakeResult) {
        when (result) {
            is IntakeResult.Ready -> {
                clearOther()
                _other.value = InsertSource(picked, result.source, result.files)
                otherUriState.value = picked.uri.toString()
                _otherPrompt.value = null; lockedCopy = null; lockedFile = null
            }
            is IntakeResult.Locked -> { lockedCopy = result.copy; lockedFile = picked; _otherPrompt.value = PasswordPrompt(picked.name, result.wrong) }
            is IntakeResult.Failed -> { notify("\"${picked.name}\" can't be used: ${result.reason}") }
        }
    }

    fun submitOtherPassword(password: String) {
        val copy = lockedCopy ?: return
        val picked = lockedFile ?: return
        viewModelScope.launch { handleOther(picked, PdfIntake.unlock(app, copy, password)) }
    }

    fun cancelOtherPassword() { lockedCopy?.delete(); lockedCopy = null; lockedFile = null; _otherPrompt.value = null }

    fun clearOther() {
        _other.value?.workFiles?.forEach(File::delete)
        _other.value = null
        otherUriState.value = ""
    }

    override fun onInputsReset() {
        clearOther()
        cancelOtherPassword()
        super.onInputsReset()
    }

    // ---- planning ---------------------------------------------------------------------------------------------

    /** Pages of the second PDF to insert (0-based) or the reason they cannot be chosen yet. */
    fun otherPages(range: String = rangeState.value, other: InsertSource? = _other.value): Result<List<Int>> {
        val o = other ?: return Result.failure(ToolException("Choose the PDF to take pages from."))
        if (range.isBlank()) return Result.success((0 until o.source.pageCount).toList())
        return when (val r = PageRanges.parse(range, o.source.pageCount)) {
            is PageRanges.Result.Ok -> Result.success(r.pages)
            is PageRanges.Result.Error -> Result.failure(ToolException(r.message))
        }
    }

    /** Why Insert cannot run yet, or null. */
    fun blocker(): String? = if (fromPdfState.value) otherPages().exceptionOrNull()?.message else null

    /** How many pages would be inserted (0 if not yet valid). */
    fun insertCount(): Int = if (fromPdfState.value) otherPages().getOrNull()?.size ?: 0 else countState.value

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val base = source.value ?: throw ToolException("Open a PDF first.")
        val fromPdf = fromPdfState.value
        val other = _other.value
        val pages = if (fromPdf) otherPages().getOrThrow() else emptyList()
        val index = PdfInsert.insertIndex(positionState.value, pageState.value, base.pageCount)
        var added = 0
        val saved = scope.save("inserted", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            val files = listOf(base.file) + listOfNotNull(other?.takeIf { fromPdf }?.source?.file)
            PdfAssembler(files, listOf("this PDF", other?.file?.name ?: "second PDF")).use { asm ->
                val inserted: List<PageSpec> = if (fromPdf) pages.map { PageSpec.FromSource(1, it) } else {
                    val neighbour = asm.pageSize(0, PdfInsert.neighbourIndex(index, base.pageCount))
                    val (w, h) = PdfInsert.blankSize(sizeState.value, neighbour)
                    List(countState.value) { PageSpec.Blank(w, h) }
                }
                added = inserted.size
                asm.write(PdfInsert.specs(base.pageCount, index, inserted), out, base.protection, { ctx.ensureActive() }, { scope.progress(it) })
            }
        }
        val where = when (positionState.value) {
            InsertPosition.Start -> "at the start"
            InsertPosition.End -> "at the end"
            InsertPosition.BeforePage -> "before page ${pageState.value}"
            InsertPosition.AfterPage -> "after page ${pageState.value}"
        }
        return ToolOutcome(saved, headline = "Pages inserted", detail = "${pagesLabel(added)} $where · ${pagesLabel(base.pageCount + added)} total")
    }

    companion object { const val MAX_BLANK = 100 }
}
