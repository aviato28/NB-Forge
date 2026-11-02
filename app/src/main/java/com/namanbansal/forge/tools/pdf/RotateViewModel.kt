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
import kotlinx.coroutines.flow.StateFlow

/** P4 Rotate: which pages are selected and how far each has been turned (raw clockwise degrees, may exceed 360). */
class RotateViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P4"

    // Both survive process death (SavedStateHandle): selection as ints, rotations as flat (page, degrees) pairs.
    private val selectedState = persistedIntSet("rotate.selected")
    private val rotationsState = persisted<Map<Int, Int>>(
        "rotate.rotations", emptyMap(),
        encode = { m -> m.flatMap { listOf(it.key, it.value) }.toIntArray() },
        decode = { a -> (a as? IntArray)?.toList()?.chunked(2)?.filter { it.size == 2 }?.associate { it[0] to it[1] } },
    )
    val selected: StateFlow<Set<Int>> = selectedState.flow
    val rotations: StateFlow<Map<Int, Int>> = rotationsState.flow

    private var pageCount = 0

    override fun onPdfReady(source: PdfSource) {
        pageCount = source.pageCount
        if (stateRestored) {
            selectedState.value = selectedState.value.filter { it in 0 until pageCount }.toSet()
            rotationsState.value = rotationsState.value.filterKeys { it in 0 until pageCount }
        } else {
            selectedState.value = (0 until pageCount).toSet() // default: whole document, so "rotate + save" is two taps
            rotationsState.value = emptyMap()
        }
    }

    fun togglePage(index: Int) = selectedState.update { if (index in it) it - index else it + index }

    fun selectAll() { selectedState.value = (0 until pageCount).toSet() }

    fun selectNone() { selectedState.value = emptySet() }

    /** Turn every selected page by [degrees] clockwise (negative = anticlockwise). */
    fun rotateSelected(degrees: Int) {
        val sel = selectedState.value
        if (sel.isEmpty()) return
        rotationsState.update { current ->
            val next = current.toMutableMap()
            for (i in sel) {
                val v = (next[i] ?: 0) + degrees
                if (v == 0) next.remove(i) else next[i] = v // keep raw turns (360 stays 360) so the preview never spins backwards
            }
            next
        }
    }

    fun resetRotations() { rotationsState.value = emptyMap() }

    /** Number of pages whose net rotation is non-zero. */
    fun changedCount(rotations: Map<Int, Int> = rotationsState.value): Int = rotations.count { PdfRotator.normalize(it.value) != 0 }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        val steps = rotationsState.value.filter { PdfRotator.normalize(it.value) != 0 }.mapValues { PdfRotator.normalize(it.value) }
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
