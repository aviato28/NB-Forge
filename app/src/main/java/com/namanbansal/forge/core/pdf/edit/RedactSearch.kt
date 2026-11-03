package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.viewer.PageText

/** The ready-made patterns of "Search & redact". */
enum class RedactPreset(val label: String) {
    EMAIL("Email addresses"), PHONE("Phone numbers"), LONG_NUMBER("Long numbers & cards"), DATE("Dates")
}

/** One thing "Search & redact" found: its page, the text and a little context for the review list, and the boxes to black out. */
data class RedactHit(val id: String, val page: Int, val text: String, val before: String, val after: String, val rects: List<NormRect>)

object RedactSearch {
    private const val MONTHS = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
    private val EMAIL = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val PHONE_CANDIDATE = Regex("(?<![\\w.])[+(]?\\d[\\d\\s().-]{7,20}\\d(?!\\w)")
    private val LONG_RUN = Regex("(?<![\\d.])\\d{9,}(?!\\d)")
    private val CARD_LIKE = Regex("(?<!\\d)\\d{4}(?:[ -]\\d{4}){2,4}(?:[ -]\\d{1,4})?(?!\\d)")
    private val DATES = listOf(
        Regex("\\b\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b"),
        Regex("\\b\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}\\b"),
        Regex("\\b\\d{1,2}(?:st|nd|rd|th)?\\s+$MONTHS,?\\s+\\d{4}\\b", RegexOption.IGNORE_CASE),
        Regex("\\b$MONTHS\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}\\b", RegexOption.IGNORE_CASE),
    )

    /** Character ranges (end exclusive) of every match of [preset] in [text], overlapping matches merged. */
    fun find(text: String, preset: RedactPreset): List<IntRange> {
        val raw = ArrayList<IntRange>()
        when (preset) {
            RedactPreset.EMAIL -> EMAIL.findAll(text).forEach { raw += it.range }
            RedactPreset.PHONE -> PHONE_CANDIDATE.findAll(text).forEach { m ->
                val digits = m.value.count { it.isDigit() }
                val shaped = m.value.any { it == '+' || it == '(' || it == ' ' || it == '-' || it == '.' }
                val trimmed = m.value.trimEnd(' ', '.', '-', ')', '(').let { if (it.count { c -> c == '(' } > it.count { c -> c == ')' }) it else m.value.trimEnd(' ', '.', '-') }
                if (digits in 9..15 && shaped && !looksLikeDate(m.value)) raw += m.range.first until (m.range.first + trimmed.length)
            }
            RedactPreset.LONG_NUMBER -> { LONG_RUN.findAll(text).forEach { raw += it.range }; CARD_LIKE.findAll(text).forEach { raw += it.range } }
            RedactPreset.DATE -> DATES.forEach { r -> r.findAll(text).forEach { raw += it.range } }
        }
        return merge(raw).map { it.first until it.last + 1 }
    }

    /** Case-insensitive literal matches of [term]. */
    fun find(text: String, term: String): List<IntRange> {
        val t = term.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = text.indexOf(t, ignoreCase = true)
        while (i >= 0) { out += i until i + t.length; i = text.indexOf(t, i + t.length, ignoreCase = true) }
        return out
    }

    private fun looksLikeDate(s: String) = Regex("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$").matches(s.trim())

    private fun merge(r: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (x in r.sortedWith(compareBy({ it.first }, { -it.last }))) {
            val last = out.lastOrNull()
            if (last != null && x.first <= last.last) { if (x.last > last.last) out[out.lastIndex] = last.first..x.last } else out += x
        }
        return out
    }

    /** Hits of one page: [preset] or [term] (exactly one of them). Ranges that have no glyph box are skipped. */
    fun hitsOn(page: Int, text: PageText, preset: RedactPreset?, term: String?): List<RedactHit> {
        val ranges = if (preset != null) find(text.text, preset) else find(text.text, term.orEmpty())
        return ranges.mapNotNull { r ->
            val rects = text.lineRects(r.first, r.last + 1)
            if (rects.isEmpty()) return@mapNotNull null
            RedactHit(
                "$page:${r.first}", page, text.text.substring(r.first, r.last + 1).replace('\n', ' ').trim(),
                text.text.substring((r.first - 24).coerceAtLeast(0), r.first).replace('\n', ' ').trimStart(),
                text.text.substring(r.last + 1, (r.last + 25).coerceAtMost(text.length)).replace('\n', ' ').trimEnd(), rects,
            )
        }
    }

    /** The marks for [hits]: one per line box, padded a hair so a glyph's edges are covered. */
    fun marksFor(hits: List<RedactHit>, newId: () -> String): List<RedactMark> = hits.flatMap { h ->
        h.rects.map { r -> RedactMark(newId(), h.page, NormRect((r.left - 0.001f).coerceAtLeast(0f), (r.top - 0.001f).coerceAtLeast(0f), (r.right + 0.001f).coerceAtMost(1f), (r.bottom + 0.001f).coerceAtMost(1f)), RedactMark.ORIGIN_SEARCH) }
    }
}
