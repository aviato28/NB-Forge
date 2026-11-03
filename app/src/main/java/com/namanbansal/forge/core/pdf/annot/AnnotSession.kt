package com.namanbansal.forge.core.pdf.annot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the shared top bar needs from any editing mode: undo / redo and whether there is something to save. */
interface UndoRedo {
    val canUndo: StateFlow<Boolean>
    val canRedo: StateFlow<Boolean>
    fun undo()
    fun redo()
}

/**
 * The editing state of the annotate mode: every annotation of the document (the ones read from the PDF plus new ones) with an
 * undo / redo history. Pure Kotlin so it is unit-tested; the UI only reads [annots]. [baseline] is what the file contains now:
 * [dirty] is whether [annots] differs from it, so undoing everything makes the document clean again.
 */
class AnnotSession(val baseline: List<Annot>, initial: List<Annot>? = null, private val maxHistory: Int = 100) : UndoRedo {
    private val _annots = MutableStateFlow(initial ?: baseline)
    val annots: StateFlow<List<Annot>> = _annots.asStateFlow()
    private val undoStack = ArrayDeque<List<Annot>>()
    private val redoStack = ArrayDeque<List<Annot>>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    private val _dirty = MutableStateFlow(_annots.value != baseline)
    override val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    override val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    val current: List<Annot> get() = _annots.value
    fun find(id: String): Annot? = current.firstOrNull { it.id == id }

    private fun commit(next: List<Annot>) {
        if (next == current) return
        undoStack.addLast(current)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        set(next)
    }

    private fun set(next: List<Annot>) {
        _annots.value = next
        _canUndo.value = undoStack.isNotEmpty(); _canRedo.value = redoStack.isNotEmpty()
        _dirty.value = next != baseline
    }

    fun add(a: Annot) = commit(current + a)
    fun addAll(list: List<Annot>) { if (list.isNotEmpty()) commit(current + list) }
    fun remove(ids: Collection<String>) { if (ids.isNotEmpty()) commit(current.filter { it.id !in ids }) }
    fun replace(a: Annot) = commit(current.map { if (it.id == a.id) a else it })

    override fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        set(prev)
    }

    override fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        set(next)
    }

    /** After a successful save the saved state becomes the new baseline in a fresh session. */
    fun newId(): String = "n" + java.lang.Long.toString(System.nanoTime(), 36) + counter++
    private var counter = 0
}
