package com.namanbansal.forge.core.pdf.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode

/** A bookmark: [page] is 0-based, null when it points nowhere we can open. */
data class OutlineItem(val title: String, val page: Int?, val children: List<OutlineItem>)

/** One visible line of the outline list; [id] is the path of child indexes ("2.0.1"). */
data class OutlineRow(val id: String, val title: String, val page: Int?, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

object PdfOutline {
    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 5000

    fun parse(doc: PDDocument): List<OutlineItem> {
        val root = try { doc.documentCatalog.documentOutline } catch (_: Exception) { null } ?: return emptyList()
        val budget = intArrayOf(MAX_NODES)
        return children(doc, root, 0, budget)
    }

    private fun children(doc: PDDocument, node: PDOutlineNode, depth: Int, budget: IntArray): List<OutlineItem> {
        if (depth >= MAX_DEPTH) return emptyList()
        val out = ArrayList<OutlineItem>()
        try {
            for (item in node.children()) {
                if (budget[0]-- <= 0) break
                out += OutlineItem(item.title?.trim().orEmpty().ifEmpty { "Untitled" }, pageOf(doc, item), children(doc, item, depth + 1, budget))
            }
        } catch (_: Exception) { /* a damaged outline keeps what was read so far */ }
        return out
    }

    private fun pageOf(doc: PDDocument, item: PDOutlineItem): Int? = try {
        val dest = item.destination ?: (item.action as? PDActionGoTo)?.destination
        pageOfDestination(doc, dest)
    } catch (_: Exception) { null }

    /** Page index (0-based) a destination points to, resolving named destinations; null if unknown. */
    fun pageOfDestination(doc: PDDocument, dest: PDDestination?): Int? {
        val d = when (dest) {
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(dest)
            is PDPageDestination -> dest
            else -> null
        } ?: return null
        val n = d.retrievePageNumber().takeIf { it >= 0 } ?: d.findPageNumber()
        return n.takeIf { it in 0 until doc.numberOfPages }
    }

    /** The rows to show given which ids are [expanded]. */
    fun flatten(items: List<OutlineItem>, expanded: Set<String>): List<OutlineRow> {
        val out = ArrayList<OutlineRow>()
        fun walk(list: List<OutlineItem>, prefix: String, depth: Int) {
            list.forEachIndexed { i, it ->
                val id = if (prefix.isEmpty()) "$i" else "$prefix.$i"
                val open = id in expanded
                out += OutlineRow(id, it.title, it.page, depth, it.children.isNotEmpty(), open)
                if (open) walk(it.children, id, depth + 1)
            }
        }
        walk(items, "", 0)
        return out
    }
}
