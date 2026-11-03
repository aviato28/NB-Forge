package com.namanbansal.forge.core.pdf

/**
 * Pure rules behind the shared page picker: the grid selection and the range text ("1-3, 5") are two views of one set of
 * pages. Typing a valid range updates the selection; tapping pages rewrites the text; invalid text leaves the selection alone.
 */
object PageSelectionSync {

    /** Result of applying typed text: the selection to use and an inline error (null when the text is empty or valid). */
    class Applied(val selection: Set<Int>, val error: String?)

    /** Text typed by the user -> selection. Blank clears; a parse error keeps [current] and reports the message. */
    fun fromText(text: String, pageCount: Int, current: Set<Int>): Applied {
        if (text.isBlank()) return Applied(emptySet(), null)
        return when (val r = PageRanges.parse(text, pageCount)) {
            is PageRanges.Result.Ok -> Applied(r.sortedUnique.toSet(), null)
            is PageRanges.Result.Error -> Applied(current, r.message)
        }
    }

    /** Selection -> text shown in the field. */
    fun toText(selection: Collection<Int>): String = PageRanges.format(selection)

    /** The inline error for [text], or null if it is blank or valid. */
    fun error(text: String, pageCount: Int): String? =
        if (text.isBlank()) null else (PageRanges.parse(text, pageCount) as? PageRanges.Result.Error)?.message

    fun all(pageCount: Int): Set<Int> = (0 until pageCount).toSet()

    fun invert(selection: Set<Int>, pageCount: Int): Set<Int> = all(pageCount) - selection

    /**
     * Result of dragging from [anchor] to [current]: every page in between is switched on ([makeSelected]) or off,
     * on top of the selection [base] that existed when the drag started. Works in both directions.
     */
    fun dragRun(base: Set<Int>, anchor: Int, current: Int, makeSelected: Boolean): Set<Int> {
        val run = minOf(anchor, current)..maxOf(anchor, current)
        return if (makeSelected) base + run else base - run.toSet()
    }
}
