package com.namanbansal.forge.core.pdf

/** One page in the Page manager's lightweight model: which source page it shows and how far it has been turned. */
data class PageItem(val id: Int, val source: Int, val rotation: Int = 0)

/** What the UI renders: the ordered pages, the selected ids and what undo/redo can do. */
data class EditorState(
    val pages: List<PageItem>,
    val selected: Set<Int>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val dirty: Boolean,
)

/**
 * The page-index model behind the Page manager. It never touches the PDF: it is a list of [PageItem]s (source page
 * index + extra rotation) with selection and an undo/redo history of list snapshots, so even 300+ pages stay cheap.
 * The result is applied to the document once, at save time ([specs] -> [PdfAssembler]).
 * Not thread-safe: use from one thread (the main thread).
 */
class PageEditor(val pageCount: Int, private val maxHistory: Int = 50) {
    private var pages: List<PageItem> = (0 until pageCount).map { PageItem(it, it) }
    private var selected: Set<Int> = emptySet()
    private var nextId = pageCount
    private val undo = ArrayDeque<List<PageItem>>()
    private val redo = ArrayDeque<List<PageItem>>()
    private var dragStart: List<PageItem>? = null

    fun state() = EditorState(pages, selected, undo.isNotEmpty(), redo.isNotEmpty(), dirty = isDirty())

    private fun isDirty() = pages.size != pageCount || pages.withIndex().any { (i, p) -> p.source != i || p.rotation % 360 != 0 }

    private fun record() {
        undo.addLast(pages)
        while (undo.size > maxHistory) undo.removeFirst()
        redo.clear()
    }

    // ---- selection (not part of undo) ----------------------------------------------------------------------

    fun toggle(id: Int) { selected = if (id in selected) selected - id else selected + id }
    fun selectAll() { selected = pages.map { it.id }.toSet() }
    fun clearSelection() { selected = emptySet() }
    fun select(ids: Set<Int>) { selected = ids.intersect(pages.map { it.id }.toSet()) }

    // ---- edits -------------------------------------------------------------------------------------------

    /** Removes the selected pages. The last remaining page can never be deleted. @return how many were removed. */
    fun deleteSelected(): Int {
        val doomed = pages.filter { it.id in selected }
        if (doomed.isEmpty() || doomed.size >= pages.size) return 0
        record()
        pages = pages.filterNot { it.id in selected }
        selected = emptySet()
        return doomed.size
    }

    /** Puts a copy of each selected page right after it; the copies become the selection. @return how many were added. */
    fun duplicateSelected(): Int {
        if (selected.isEmpty()) return 0
        record()
        val out = ArrayList<PageItem>(pages.size + selected.size)
        val copies = HashSet<Int>()
        for (p in pages) {
            out += p
            if (p.id in selected) { val c = p.copy(id = nextId++); out += c; copies += c.id }
        }
        pages = out
        selected = copies
        return copies.size
    }

    fun rotateSelected(degrees: Int): Int {
        if (selected.isEmpty()) return 0
        record()
        pages = pages.map { if (it.id in selected) it.copy(rotation = PdfRotator.normalize(it.rotation + degrees)) else it }
        return selected.size
    }

    /** Moves the page at [from] to position [to] (one history step). */
    fun move(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        record()
        pages = reorder(pages, from, to)
    }

    /** Drag support: [beginDrag] snapshots once, [moveLive] reorders without history, [endDrag] drops a no-op drag from history. */
    fun beginDrag() { if (dragStart == null) { dragStart = pages; record() } }

    fun moveLive(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        pages = reorder(pages, from, to)
    }

    fun endDrag() {
        val start = dragStart ?: return
        dragStart = null
        if (start == pages && undo.isNotEmpty()) undo.removeLast()
    }

    private fun reorder(list: List<PageItem>, from: Int, to: Int): List<PageItem> =
        list.toMutableList().also { it.add(to, it.removeAt(from)) }

    /** Moves each selected page one step earlier/later, keeping their relative order. */
    fun nudgeSelected(delta: Int): Boolean {
        if (selected.isEmpty() || delta == 0) return false
        val idx = pages.indices.filter { pages[it].id in selected }
        val list = pages.toMutableList()
        val order = if (delta < 0) idx else idx.reversed()
        var moved = false
        val snapshot = pages
        for (i in order) {
            val j = i + if (delta < 0) -1 else 1
            if (j !in list.indices || list[j].id in selected) continue
            java.util.Collections.swap(list, i, j); moved = true
        }
        if (!moved) return false
        undo.addLast(snapshot); while (undo.size > maxHistory) undo.removeFirst(); redo.clear()
        pages = list
        return true
    }

    // ---- history ---------------------------------------------------------------------------------------

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(pages); pages = prev; pruneSelection(); return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(pages); pages = next; pruneSelection(); return true
    }

    private fun pruneSelection() { val ids = pages.map { it.id }.toSet(); selected = selected.intersect(ids) }

    // ---- output / persistence -------------------------------------------------------------------------------

    /** The page list as assembler instructions (all from source 0). */
    fun specs(onlySelected: Boolean = false): List<PageSpec> =
        pages.filter { !onlySelected || it.id in selected }.map { PageSpec.FromSource(0, it.source, it.rotation) }

    /** Flat (id, source, rotation) triples + selected ids, small enough for a SavedStateHandle. Undo history is not kept. */
    fun encode(): IntArray = IntArray(3 + pages.size * 3 + selected.size).also { a ->
        a[0] = nextId; a[1] = pages.size; a[2] = selected.size
        pages.forEachIndexed { i, p -> a[3 + i * 3] = p.id; a[4 + i * 3] = p.source; a[5 + i * 3] = p.rotation }
        selected.forEachIndexed { i, id -> a[3 + pages.size * 3 + i] = id }
    }

    /** Restores [encode]d state if it is consistent with this document; returns false (state untouched) otherwise. */
    fun restore(a: IntArray): Boolean {
        if (a.size < 3) return false
        val n = a[1]; val s = a[2]
        if (n < 1 || a.size != 3 + n * 3 + s) return false
        val list = (0 until n).map { PageItem(a[3 + it * 3], a[4 + it * 3], a[5 + it * 3]) }
        if (list.any { it.source !in 0 until pageCount }) return false
        pages = list; nextId = maxOf(a[0], (list.maxOf { it.id }) + 1)
        selected = (0 until s).map { a[3 + n * 3 + it] }.toSet()
        undo.clear(); redo.clear()
        return true
    }
}
