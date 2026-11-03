package com.namanbansal.forge.tools.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.FileResolver
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.IntakeResult
import com.namanbansal.forge.core.pdf.MergeInput
import com.namanbansal.forge.core.pdf.PasswordPrompt
import com.namanbansal.forge.core.pdf.PdfIntake
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfMerger
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.core.pdf.PdfSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.io.File

/** Where one file in the Merge list is. */
sealed interface MergeStatus {
    data object Opening : MergeStatus
    data object Ready : MergeStatus
    /** Password needed ([wrong]: the last try failed). */
    data class Locked(val wrong: Boolean) : MergeStatus
    data class Failed(val reason: String) : MergeStatus
}

/** One row of the Merge list. */
class MergeItem(
    val id: Long,
    val file: PickedFile,
    val status: MergeStatus,
    val source: PdfSource? = null,
    val cover: Bitmap? = null,
    internal val copy: File? = null,
    internal val workFiles: List<File> = emptyList(),
) {
    val pages: Int get() = source?.pageCount ?: 0
    fun with(status: MergeStatus, source: PdfSource? = this.source, cover: Bitmap? = this.cover, copy: File? = this.copy, workFiles: List<File> = this.workFiles) =
        MergeItem(id, file, status, source, cover, copy, workFiles)
}

/**
 * P1 Merge: an editable list of PDFs (add, remove, drag to reorder, per-file password and error states) that PDFBox's
 * merger concatenates. Picked files appear immediately as "Opening" rows and are copied/opened one by one.
 */
class MergeViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {

    override val toolCode = "P1"

    private val blankState = persistedBool("merge.blank", false)
    val blankBetween: StateFlow<Boolean> = blankState.flow
    fun setBlankBetween(on: Boolean) { blankState.value = on }

    private val _items = MutableStateFlow<List<MergeItem>>(emptyList())
    val items: StateFlow<List<MergeItem>> = _items.asStateFlow()

    private var nextId = 1L

    /** The first locked file, if any: the screen shows the password dialog for it. */
    val passwordPrompt: StateFlow<PasswordPrompt?> = _items.map { list ->
        list.firstOrNull { it.status is MergeStatus.Locked }?.let { PasswordPrompt(it.file.name, (it.status as MergeStatus.Locked).wrong) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    override fun onInputsReset() {
        _items.value.forEach { it.workFiles.forEach(File::delete); it.copy?.delete() }
        _items.value = emptyList()
        super.onInputsReset()
    }

    override suspend fun onInputsReady(files: List<PickedFile>) {
        load(files)
    }

    /** "Add files" on the options screen. */
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val files = uris.map { FileResolver.resolve(app, it) }
            replaceInputs(inputs.value + files)
            load(files)
        }
    }

    private fun load(files: List<PickedFile>) {
        val added = files.map { MergeItem(nextId++, it, MergeStatus.Opening) }
        _items.value = _items.value + added
        viewModelScope.launch {
            for (item in added) {
                if (_items.value.none { it.id == item.id }) continue // removed while queued
                val result = try { PdfIntake.open(app, item.file) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { IntakeResult.Failed("This file couldn't be opened.") }
                apply(item.id, result)
            }
        }
    }

    private fun apply(id: Long, result: IntakeResult) {
        val current = _items.value.firstOrNull { it.id == id }
        if (current == null) { // removed while opening: clean up what we made
            (result as? IntakeResult.Ready)?.files?.forEach(File::delete)
            (result as? IntakeResult.Locked)?.copy?.delete()
            return
        }
        val next = when (result) {
            is IntakeResult.Ready -> current.with(MergeStatus.Ready, result.source, result.cover, copy = null, workFiles = result.files)
            is IntakeResult.Locked -> current.with(MergeStatus.Locked(result.wrong), copy = result.copy)
            is IntakeResult.Failed -> current.with(MergeStatus.Failed("\"${current.file.name}\" can't be used: ${result.reason}"))
        }
        _items.value = _items.value.map { if (it.id == id) next else it }
        if (result is IntakeResult.Failed) notify("\"${current.file.name}\" couldn't be read. Remove it to continue.")
    }

    fun submitPassword(password: String) {
        val item = _items.value.firstOrNull { it.status is MergeStatus.Locked } ?: return
        val copy = item.copy ?: return
        viewModelScope.launch { apply(item.id, PdfIntake.unlock(app, copy, password)) }
    }

    /** Password dialog "Cancel": the locked file is left out of the merge. */
    fun skipLocked() {
        val item = _items.value.firstOrNull { it.status is MergeStatus.Locked } ?: return
        remove(item.id)
    }

    fun remove(id: Long) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        item.workFiles.forEach(File::delete); item.copy?.delete()
        val rest = _items.value.filterNot { it.id == id }
        _items.value = rest
        if (rest.isEmpty()) changeFiles() else replaceInputs(rest.map { it.file })
    }

    /** Live reorder while dragging (the list order is the merge order). */
    fun move(from: Int, to: Int) {
        val list = _items.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        _items.value = list
    }

    fun dragFinished() { replaceInputs(_items.value.map { it.file }) }

    /** Why Merge cannot run yet, or null when it can. */
    fun blocker(list: List<MergeItem> = _items.value): String? {
        val ready = list.count { it.status == MergeStatus.Ready }
        return when {
            list.any { it.status == MergeStatus.Opening } -> "Opening files…"
            list.any { it.status is MergeStatus.Locked } -> "Enter the password to include the locked file"
            list.firstOrNull { it.status is MergeStatus.Failed } != null -> "Remove the file that can't be read to continue"
            ready < 2 -> "Add at least two PDFs to merge"
            else -> null
        }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val list = _items.value
        blocker(list)?.let { throw ToolException(it + ".") }
        val ready = list.filter { it.status == MergeStatus.Ready }
        val protection = PdfProtection.forAll(ready.mapNotNull { it.source })
        val blank = blankState.value
        var pages = 0
        val saved = scope.save("merged", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            pages = PdfMerger.merge(
                inputs = ready.map { MergeInput(it.source!!.file, it.file.name) },
                out = out, blankBetween = blank, protection = protection,
                tempDir = File(app.cacheDir, PdfLoader.WORK_DIR),
                checkCancelled = { ctx.ensureActive() }, onProgress = { scope.progress(it) },
            )
        }
        return ToolOutcome(saved, headline = "Merged successfully", detail = "${ready.size} files · ${pagesLabel(pages)}")
    }
}
