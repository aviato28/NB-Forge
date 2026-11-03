package com.namanbansal.forge.core.pdf

enum class InsertPosition(val label: String) { Start("Start"), BeforePage("Before page"), AfterPage("After page"), End("End") }

enum class BlankSize(val label: String) { Neighbour("Match neighbour"), A4("A4"), Letter("Letter") }

/** Pure planning for Insert pages: where the new pages go and what the finished page list looks like. */
object PdfInsert {

    /** 0-based index in the base document before which the new pages are placed (0..[baseCount]). [page] is 1-based. */
    fun insertIndex(position: InsertPosition, page: Int, baseCount: Int): Int = when (position) {
        InsertPosition.Start -> 0
        InsertPosition.BeforePage -> (page - 1).coerceIn(0, baseCount)
        InsertPosition.AfterPage -> page.coerceIn(0, baseCount)
        InsertPosition.End -> baseCount
    }

    /** Size in points of a blank page: the neighbour's size, or a standard size ([neighbour] null = no pages to copy: A4). */
    fun blankSize(size: BlankSize, neighbour: Pair<Float, Float>?): Pair<Float, Float> = when (size) {
        BlankSize.A4 -> PdfAssembler.A4_WIDTH to PdfAssembler.A4_HEIGHT
        BlankSize.Letter -> PdfAssembler.LETTER_WIDTH to PdfAssembler.LETTER_HEIGHT
        BlankSize.Neighbour -> neighbour ?: (PdfAssembler.A4_WIDTH to PdfAssembler.A4_HEIGHT)
    }

    /** The page just before the insertion point (or the first page when inserting at the start) as the "neighbour" for blank sizing. */
    fun neighbourIndex(index: Int, baseCount: Int): Int = (if (index > 0) index - 1 else 0).coerceIn(0, (baseCount - 1).coerceAtLeast(0))

    /** Base pages (source 0) with [inserted] spliced in at [index]. */
    fun specs(baseCount: Int, index: Int, inserted: List<PageSpec>): List<PageSpec> =
        (0 until index).map { PageSpec.FromSource(0, it) } + inserted + (index until baseCount).map { PageSpec.FromSource(0, it) }
}
