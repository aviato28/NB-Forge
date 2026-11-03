package com.namanbansal.forge.core.pdf

/**
 * Split planning for the visual "Cut" mode and the colour-coded preview shared by every mode.
 * A cut is stored as the 0-based index of the page that STARTS a new file (1..total-1), so "cut between page 3 and 4" = 3.
 */
object SplitGroups {

    /** Keeps only starts that are real gaps between pages. */
    fun cleanStarts(starts: Collection<Int>, total: Int): Set<Int> = starts.filter { it in 1 until total }.toSet()

    /** Contiguous files from the cuts: no cuts = one file with every page. */
    fun partsFromStarts(starts: Collection<Int>, total: Int): List<SplitPart> {
        if (total <= 0) return emptyList()
        val bounds = listOf(0) + cleanStarts(starts, total).sorted() + total
        return bounds.zipWithNext().map { (a, b) ->
            val range = a..(b - 1)
            SplitPart(PageRanges.label(range), range.toList())
        }
    }

    /** For each page, the index of the first part that contains it, or -1 when no part uses it (e.g. pages left out of Ranges). */
    fun owners(parts: List<SplitPart>, total: Int): IntArray {
        val owner = IntArray(total) { -1 }
        parts.forEachIndexed { i, part -> part.pages.forEach { p -> if (p in 0 until total && owner[p] == -1) owner[p] = i } }
        return owner
    }

    /** "File 2 · pages 4–6" (single page: "page 5"; scattered pages: "pages 1–3, 5"). */
    fun title(index: Int, part: SplitPart): String {
        val pages = part.summary.replace('-', '–')
        return "File ${index + 1} · ${if (part.pages.size == 1) "page" else "pages"} $pages"
    }
}
