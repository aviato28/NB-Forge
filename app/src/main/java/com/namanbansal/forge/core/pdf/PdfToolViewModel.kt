package com.namanbansal.forge.core.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Shown by the password dialog. [wrong] = the last attempt failed. */
data class PasswordPrompt(val fileName: String, val wrong: Boolean)

/**
 * Base for tools that take one PDF: copies it into a private working file, handles password-protected files
 * (prompt -> retry -> wrong-password message) and exposes [source] plus lazily rendered [thumbnails].
 * Subclasses react to a ready document in [onPdfReady] and read [source] in `execute`.
 */
abstract class PdfToolViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {

    private val _source = MutableStateFlow<PdfSource?>(null)
    /** Null until the PDF is open (e.g. while waiting for a password). */
    val source: StateFlow<PdfSource?> = _source.asStateFlow()

    private val _thumbnails = MutableStateFlow<PdfThumbnails?>(null)
    val thumbnails: StateFlow<PdfThumbnails?> = _thumbnails.asStateFlow()

    private val _passwordPrompt = MutableStateFlow<PasswordPrompt?>(null)
    val passwordPrompt: StateFlow<PasswordPrompt?> = _passwordPrompt.asStateFlow()

    private val workFiles = mutableListOf<File>()
    private var lockedCopy: File? = null

    /** How much memory the page-bitmap cache may use; the viewer asks for more than a thumbnail grid needs. */
    protected open val thumbnailCacheBytes: Int get() = PdfThumbnails.defaultCacheBytes()

    /**
     * The file that pages are rendered from. By default the working copy itself; the viewer returns a copy without the
     * annotations its own overlay draws. Runs off the main thread's critical path (suspending); may keep extra files via [trackWorkFile].
     */
    protected open suspend fun renderFileFor(source: PdfSource): File = source.file

    /** Marks [f] as a temporary file to delete when the document is closed. */
    protected fun trackWorkFile(f: File) { workFiles += f }

    /** Called on the main thread once the document is open (also after a successful password). */
    protected open fun onPdfReady(source: PdfSource) {}

    override suspend fun onInputsReady(files: List<PickedFile>) {
        val file = files.first()
        // Some providers (chat apps, cloud drives) hand out no name or a generic type; the file itself tells the truth.
        if (file.kind != FileKind.PDF && !PdfLoader.looksLikePdf(app, file.uri)) throw ToolException("\"${file.name}\" isn't a PDF. Please choose a PDF file.")
        val copy = PdfLoader.copyToWork(app, file.uri)
        workFiles += copy
        handle(copy, file.name, PdfLoader.open(copy, workDir(), null))
    }

    private fun workDir() = File(app.cacheDir, PdfLoader.WORK_DIR)

    private suspend fun handle(copy: File, name: String, result: PdfOpenResult) {
        when (result) {
            is PdfOpenResult.Ready -> {
                if (result.source.file != copy) workFiles += result.source.file
                lockedCopy = null
                _passwordPrompt.value = null
                val renderFile = renderFileFor(result.source)
                val thumbs = try { PdfThumbnails(renderFile, cacheBytes = thumbnailCacheBytes) } catch (_: Exception) {
                    throw ToolException("This PDF can't be displayed on your device.")
                }
                _thumbnails.value = thumbs
                _source.value = result.source
                onPdfReady(result.source)
            }
            PdfOpenResult.NeedsPassword -> { lockedCopy = copy; _passwordPrompt.value = PasswordPrompt(name, wrong = false) }
            PdfOpenResult.WrongPassword -> { lockedCopy = copy; _passwordPrompt.value = PasswordPrompt(name, wrong = true) }
            is PdfOpenResult.Unreadable -> throw ToolException(result.reason)
        }
    }

    /**
     * Swaps the open document for [file] (a plain working copy with the same pages, e.g. just saved with edits) without
     * going back through the picker; [onPdfReady] runs again.
     */
    protected suspend fun reopen(file: File, protection: PdfProtection?) {
        val old = _source.value ?: return
        val oldThumbs = _thumbnails.value
        workFiles += file
        handle(file, primaryName(), PdfOpenResult.Ready(PdfSource(file, old.pageCount, old.wasEncrypted, protection)))
        oldThumbs?.close()
    }

    /** Password dialog "Unlock". */
    fun submitPassword(password: String) {
        val copy = lockedCopy ?: return
        val name = _passwordPrompt.value?.fileName ?: primaryName()
        viewModelScope.launch {
            try {
                handle(copy, name, PdfLoader.open(copy, workDir(), password))
            } catch (t: ToolException) {
                changeFiles()
                notify(t.message ?: "Could not open this PDF.")
            }
        }
    }

    /** Password dialog "Cancel": back to the picker. */
    fun cancelPassword() { changeFiles() }

    private fun primaryName() = inputs.value.firstOrNull()?.name ?: "file"

    override fun onInputsReset() {
        _thumbnails.value?.close()
        _thumbnails.value = null
        _source.value = null
        _passwordPrompt.value = null
        lockedCopy = null
        workFiles.forEach { it.delete() }
        workFiles.clear()
        super.onInputsReset()
    }
}
