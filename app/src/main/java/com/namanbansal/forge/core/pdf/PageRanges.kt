package com.namanbansal.forge.core.pdf

/**
 * Parser for page-range text such as `1-3, 5, 8-10` (1-based, as people type it). Also accepts `7-` (to the last page),
 * `-3` (up to page 3), `;` or spaces as separators and en dashes. Pure Kotlin, shared by Split, Extract and Insert.
 */
object PageRanges {

    sealed interface Result {
        /** Valid input: 0-based inclusive ranges in the order typed. */
        class Ok(val ranges: List<IntRange>) : Result {
            /** Every page index in typed order (duplicates kept). */
            val pages: List<Int> get() = ranges.flatMap { it.toList() }
            /** Distinct page indices, ascending. */
            val sortedUnique: List<Int> get() = pages.toSortedSet().toList()
        }
        /** Invalid input with a message that is safe to show under the text field. */
        class Error(val message: String) : Result
    }

    fun parse(text: String, pageCount: Int): Result {
        val tokens = text.replace('–', '-').replace('—', '-').split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result.Error("Enter page numbers, like 1-3, 5, 8-10.")
        val out = ArrayList<IntRange>()
        for (raw in tokens) {
            // Allow "1 - 3" and spaces-as-separators inside a token only when they are not part of a range.
            val token = raw.replace(Regex("\\s*-\\s*"), "-")
            for (part in token.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                when (val r = parseOne(part, pageCount)) {
                    is Single -> out += r.range
                    is Failure -> return Result.Error(r.message)
                }
            }
        }
        return Result.Ok(out)
    }

    private sealed interface One
    private class Single(val range: IntRange) : One
    private class Failure(val message: String) : One

    private fun parseOne(part: String, pageCount: Int): One {
        val dash = part.indexOf('-')
        if (dash < 0) {
            val n = part.toIntOrNull() ?: return Failure("\"$part\" isn't a page number.")
            return check(n, part, pageCount)?.let { Failure(it) } ?: Single((n - 1)..(n - 1))
        }
        if (part.count { it == '-' } > 1) return Failure("\"$part\" isn't a valid range.")
        val left = part.substring(0, dash)
        val right = part.substring(dash + 1)
        if (left.isEmpty() && right.isEmpty()) return Failure("\"-\" needs page numbers around it.")
        val a = if (left.isEmpty()) 1 else left.toIntOrNull() ?: return Failure("\"$part\" isn't a valid range.")
        val b = if (right.isEmpty()) pageCount else right.toIntOrNull() ?: return Failure("\"$part\" isn't a valid range.")
        check(a, left.ifEmpty { "1" }, pageCount)?.let { return Failure(it) }
        check(b, right.ifEmpty { pageCount.toString() }, pageCount)?.let { return Failure(it) }
        if (a > b) return Failure("\"$part\" goes backwards. Write it as $b-$a.")
        return Single((a - 1)..(b - 1))
    }

    private fun check(n: Int, label: String, pageCount: Int): String? = when {
        n < 1 -> "Pages start at 1 (\"$label\")."
        n > pageCount -> "Page $n doesn't exist. This PDF has ${if (pageCount == 1) "1 page" else "$pageCount pages"}."
        else -> null
    }

    /** Formats 0-based page indices as 1-based text with runs collapsed: [0,1,2,4] -> "1-3, 5". */
    fun format(pages: Collection<Int>): String {
        val sorted = pages.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = ArrayList<String>()
        var start = sorted.first()
        var prev = start
        fun flush() { parts += if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}" }
        for (p in sorted.drop(1)) {
            if (p == prev + 1) { prev = p; continue }
            flush(); start = p; prev = p
        }
        flush()
        return parts.joinToString(", ")
    }

    /** Label for one range as shown on a chip or used in a file name: "p5" or "p1-3". */
    fun label(range: IntRange): String = if (range.first == range.last) "p${range.first + 1}" else "p${range.first + 1}-${range.last + 1}"
}
