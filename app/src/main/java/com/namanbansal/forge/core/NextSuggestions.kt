package com.namanbansal.forge.core

import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.ToolStatus

/**
 * "Next" chips on the result screen: Available tools that accept the output file, minus the one just used.
 * A hand-picked order per tool ([PREFERRED]) comes first (Merge -> Compress / Watermark / Protect, Split -> Merge ...);
 * tools that are not Available yet are skipped, so the chips light up on their own as tools ship. Any remaining
 * slots are filled with other tools that accept the file.
 */
object NextSuggestions {

    /** Tools that take several files at once; a many-file result (Split) is handed to them whole. */
    val MULTI_INPUT: Set<String> = setOf("P1")

    private val PREFERRED: Map<String, List<String>> = mapOf(
        "P1" to listOf("P9", "P20", "P29", "P21", "P40"),     // Merge: shrink, brand, secure, number, read
        "P2" to listOf("P1", "P9", "P40"),                    // Split: put parts back together, shrink
        "P3" to listOf("P9", "P29", "P20", "P40"),            // Page manager
        "P4" to listOf("P3", "P9", "P29", "P40"),             // Rotate
        "P5" to listOf("P1", "P9", "P29", "P40"),             // Extract
        "P6" to listOf("P3", "P9", "P29", "P40"),             // Insert
        "P9" to listOf("P29", "P20", "P1", "P40"),            // Compress
        "P20" to listOf("P29", "P9", "P40"),                  // Watermark
        "P40" to listOf("P3", "P1", "P5", "P2"),              // Viewer: organise
    )

    fun forOutput(
        output: FileKind,
        currentToolCode: String,
        tools: List<ToolDef> = ToolRegistry.all,
        limit: Int = 4,
    ): List<ToolDef> {
        val usable = tools.filter { it.status == ToolStatus.Available && it.code != currentToolCode && output in it.accepts }
        val byCode = usable.associateBy { it.code }
        val preferred = PREFERRED[currentToolCode].orEmpty().mapNotNull { byCode[it] }
        // The viewer is never a useful "next step" filler: it is only suggested when listed explicitly above.
        val rest = usable.filter { it !in preferred && it.code != "P40" }
        return (preferred + rest).take(limit)
    }
}
