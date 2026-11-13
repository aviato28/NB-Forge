package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Number styles for the page-number item. [pattern] uses {n} (this page) and {N} (last number). */
enum class NumberFormat(val label: String, val pattern: String, val roman: Boolean = false) {
    PLAIN("1", "{n}"), PAGE_OF("Page 1 of N", "Page {n} of {N}"), SLASH("1/N", "{n}/{N}"), ROMAN("i, ii, iii", "{n}", true), DASHED("- 1 -", "- {n} -");
}

/** Header/footer positions: top or bottom row, left/centre/right. 0..2 top, 3..5 bottom. */
object StampPosition { const val TOP_LEFT = 0; const val TOP_CENTER = 1; const val TOP_RIGHT = 2; const val BOTTOM_LEFT = 3; const val BOTTOM_CENTER = 4; const val BOTTOM_RIGHT = 5 }

class StampItem(val enabled: Boolean, val template: String, val position: Int)

class StampSpec(
    val pageNumber: StampItem = StampItem(true, "", StampPosition.BOTTOM_CENTER),
    val numberFormat: NumberFormat = NumberFormat.PLAIN,
    val start: Int = 1,
    val skipFirst: Boolean = false,
    val header: StampItem = StampItem(false, "", StampPosition.TOP_CENTER),
    val footer: StampItem = StampItem(false, "", StampPosition.BOTTOM_LEFT),
    val batesEnabled: Boolean = false,
    val batesPrefix: String = "",
    val batesDigits: Int = 6,
    val batesStart: Int = 1,
    val batesPosition: Int = StampPosition.BOTTOM_RIGHT,
    val fontSize: Float = 10f,
    val margin: Float = 28f,
    val fileName: String = "document.pdf",
    val date: String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date()),
)

/** Page numbers, headers / footers with {date} {filename} {n} {N}, and Bates numbers. */
object PdfStamper {

    fun pageNumberText(spec: StampSpec, pageIndex: Int, pageCount: Int): String? {
        if (spec.skipFirst && pageIndex == 0) return null
        val first = if (spec.skipFirst) 1 else 0
        val n = spec.start + pageIndex - first
        val last = spec.start + (pageCount - first) - 1
        return spec.numberFormat.pattern
            .replace("{n}", if (spec.numberFormat.roman) TextSafe.roman(n) else n.toString())
            .replace("{N}", if (spec.numberFormat.roman) TextSafe.roman(last) else last.toString())
    }

    fun bates(spec: StampSpec, pageIndex: Int): String = spec.batesPrefix + (spec.batesStart + pageIndex).toString().padStart(spec.batesDigits.coerceIn(1, 12), '0')

    fun expand(template: String, spec: StampSpec, pageIndex: Int, pageCount: Int): String {
        return template.replace("{date}", spec.date).replace("{filename}", spec.fileName)
            .replace("{n}", (spec.start + pageIndex).toString()).replace("{N}", (spec.start + pageCount - 1).toString())
            .replace("{bates}", bates(spec, pageIndex))
    }

    /** What would be drawn on page [pageIndex]: (position, text) pairs. */
    fun itemsFor(spec: StampSpec, pageIndex: Int, pageCount: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        if (spec.header.enabled && spec.header.template.isNotBlank()) out += spec.header.position to expand(spec.header.template, spec, pageIndex, pageCount)
        if (spec.footer.enabled && spec.footer.template.isNotBlank()) out += spec.footer.position to expand(spec.footer.template, spec, pageIndex, pageCount)
        if (spec.pageNumber.enabled) pageNumberText(spec, pageIndex, pageCount)?.let { out += spec.pageNumber.position to it }
        if (spec.batesEnabled) out += spec.batesPosition to bates(spec, pageIndex)
        return out
    }

    fun apply(doc: PDDocument, spec: StampSpec, pages: Set<Int>? = null, ctx: TransformCtx = TransformCtx({}, {})): Int {
        val font = PDType1Font.HELVETICA
        val total = doc.numberOfPages
        var stamped = 0
        for ((i, page) in doc.pages.withIndex()) {
            ctx.checkCancelled()
            if (pages != null && i !in pages) continue
            val items = itemsFor(spec, i, total)
            if (items.isEmpty()) continue
            val (vw, vh) = PageGeometry.viewSize(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(PageGeometry.viewToUser(page))
                cs.setNonStrokingColor(0.2f, 0.2f, 0.22f)
                for ((pos, raw) in items) {
                    val text = TextSafe.sanitize(raw, font)
                    val w = TextSafe.width(text, font, spec.fontSize)
                    val x = when (pos % 3) { 0 -> spec.margin; 1 -> (vw - w) / 2; else -> vw - spec.margin - w }
                    val y = if (pos < 3) vh - spec.margin - spec.fontSize * 0.8f else spec.margin
                    cs.beginText(); cs.setFont(font, spec.fontSize); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
                }
                cs.restoreGraphicsState()
            }
            stamped++
            ctx.onProgress((i + 1).toFloat() / total * 0.9f)
        }
        return stamped
    }
}
