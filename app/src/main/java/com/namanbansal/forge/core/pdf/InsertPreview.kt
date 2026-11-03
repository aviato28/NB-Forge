package com.namanbansal.forge.core.pdf

enum class PreviewKind { Base, Other, Blank }

/**
 * One cell of the Insert preview. [page] is the 0-based page in its own PDF (base or the second PDF; 0 for blanks).
 * [gapBefore]/[gapAfter] are the insertion gap ids (0..baseCount) drawn on that side of the cell, or null.
 */
data class PreviewEntry(val kind: PreviewKind, val page: Int, val gapBefore: Int? = null, val gapAfter: Int? = null)

/**
 * The page list the person will get, in place: base pages with the inserted pages (or blank placeholders) spliced in at gap
 * [insertAt] (0 = before the first page, baseCount = after the last). Every gap 0..baseCount is reachable exactly once; the
 * chosen gap is drawn in front of the inserted block.
 */
object InsertPreview {
    fun entries(baseCount: Int, insertAt: Int, inserted: List<PreviewEntry>): List<PreviewEntry> {
        val at = insertAt.coerceIn(0, baseCount)
        val out = ArrayList<PreviewEntry>(baseCount + inserted.size)
        fun base(i: Int): PreviewEntry {
            val before = if (i == at && inserted.isNotEmpty()) null else i // the block's own marker stands in for this gap
            val after = if (i == baseCount - 1) baseCount else null
            return PreviewEntry(PreviewKind.Base, i, before, after)
        }
        for (i in 0 until at) out += base(i)
        inserted.forEachIndexed { k, e -> out += e.copy(gapBefore = if (k == 0 && at < baseCount) at else null, gapAfter = null) }
        for (i in at until baseCount) out += base(i)
        return out
    }

    fun otherEntries(pages: List<Int>): List<PreviewEntry> = pages.map { PreviewEntry(PreviewKind.Other, it) }
    fun blankEntries(count: Int): List<PreviewEntry> = List(count) { PreviewEntry(PreviewKind.Blank, 0) }

    /** Maps a tapped gap to the legacy position + 1-based page used by the saved options. */
    fun positionFor(gap: Int, baseCount: Int): Pair<InsertPosition, Int> = when {
        gap <= 0 -> InsertPosition.Start to 1
        gap >= baseCount -> InsertPosition.End to baseCount
        else -> InsertPosition.BeforePage to (gap + 1)
    }
}
