package com.namanbansal.forge.core

import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.ToolStatus

/** "Next" chips on the result screen: Available tools that accept the output file, minus the one just used. */
object NextSuggestions {
    fun forOutput(
        output: FileKind,
        currentToolCode: String,
        tools: List<ToolDef> = ToolRegistry.all,
        limit: Int = 4,
    ): List<ToolDef> = tools
        .filter { it.status == ToolStatus.Available && it.code != currentToolCode && output in it.accepts }
        .take(limit)
}
