package com.namanbansal.forge.tools

/** A search hit: [nameMatches] are character indices in [ToolDef.name] to highlight. */
data class SearchHit(val tool: ToolDef, val score: Int, val nameMatches: Set<Int>)

/**
 * Small "fuzzy-ish" matcher. Every whitespace-separated token must match somewhere
 * (name substring > code > keyword > category > description > in-order subsequence of the name).
 */
object ToolSearch {

    fun search(query: String, tools: List<ToolDef> = ToolRegistry.all): List<SearchHit> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return tools.mapNotNull { match(it, tokens) }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.tool.name.length })
    }

    private fun match(tool: ToolDef, tokens: List<String>): SearchHit? {
        val name = tool.name.lowercase()
        val highlights = mutableSetOf<Int>()
        var total = 0
        for (t in tokens) {
            val idx = name.indexOf(t)
            val score = when {
                idx >= 0 -> {
                    (idx until idx + t.length).forEach { highlights += it }
                    val wordStart = idx == 0 || !name[idx - 1].isLetterOrDigit()
                    (if (idx == 0) 100 else if (wordStart) 90 else 70) + t.length
                }
                tool.code.equals(t, ignoreCase = true) -> 95
                tool.keywords.any { it.lowercase().startsWith(t) } -> 60
                tool.keywords.any { it.lowercase().contains(t) } -> 50
                tool.category.title.lowercase().startsWith(t) || tool.group.lowercase().startsWith(t) -> 40
                tool.description.lowercase().contains(t) -> 25
                else -> subsequence(name, t)?.let { positions ->
                    highlights += positions
                    10
                } ?: return null
            }
            total += score
        }
        return SearchHit(tool, total, highlights)
    }

    /**
     * Indices of [needle] chars appearing in order in [hay], or null. Typo-tolerance only: needs >= 4 chars and the
     * letters must sit close together, otherwise short queries ("rot") match unrelated names ("Currency converter").
     */
    private fun subsequence(hay: String, needle: String): List<Int>? {
        if (needle.length < 4) return null
        val out = ArrayList<Int>(needle.length)
        var from = 0
        for (ch in needle) {
            val i = hay.indexOf(ch, from)
            if (i < 0) return null
            out += i
            from = i + 1
        }
        if (out.last() - out.first() + 1 > needle.length + 3) return null
        return out
    }
}
