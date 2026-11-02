package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfRotator
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** P4 Rotate: which pages are selected and how far each has been turned (raw clockwise degrees, may exceed 360). */
class RotateViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P4"

    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected: StateFlow<Set<Int>> = _selected.asStateFlow()

    private val _rotations = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val rotations: StateFlow<Map<Int, Int>> = _rotations.asStateFlow()

    private var pageCount = 0

    override fun onPdfReady(source: PdfSource) {
        pageCount = source.pageCount
        _selected.value = (0 until pageCount).toSet() // default: whole document, so "rotate + save" is two taps
        _rotations.value = emptyMap()
    }

    fun togglePage(index: Int) = _selected.update { if (index in it) it - index else it + index }

    fun selectAll() { _selected.value = (0 until pageCount).toSet() }

    fun selectNone() { _selected.value = emptySet() }

    /** Turn every selected page by [degrees] clockwise (negative = anticlockwise). */
    fun rotateSelected(degrees: Int) {
        val sel = _selected.value
        if (sel.isEmpty()) return
        _rotations.update { current ->
            val next = current.toMutableMap()
            for (i in sel) {
                val v = (next[i] ?: 0) + degrees
                if (v == 0) next.remove(i) else next[i] = v // keep raw turns (360 stays 360) so the preview never spins backwards
            }
            next
        }
    }

    fun resetRotations() { _rotations.value = emptyMap() }

    /** Number of pages whose net rotation is non-zero. */
    fun changedCount(rotations: Map<Int, Int> = _rotations.value): Int = rotations.count { PdfRotator.normalize(it.value) != 0 }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val steps = _rotations.value.filter { PdfRotator.normalize(it.value) != 0 }.mapValues { PdfRotator.normalize(it.value) }
        if (steps.isEmpty()) throw ToolException("Rotate at least one page first.")
        var changed = 0
        val saved = scope.save("rotated", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            changed = PdfRotator.rotate(
                source = src.file, out = out, rotations = steps,
                checkCancelled = { ctx.ensureActive() },
                onProgress = { scope.progress(it) },
                protection = src.protection,
            )
        }
        return ToolOutcome(saved, headline = "Rotated successfully", detail = "${pagesLabel(changed)} rotated")
    }
}
