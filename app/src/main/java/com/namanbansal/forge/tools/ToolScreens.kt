package com.namanbansal.forge.tools

import androidx.compose.runtime.Composable

/** Context a tool screen gets from the host. */
class ToolHost(val tool: ToolDef, val onBack: () -> Unit)

/**
 * code -> screen for every implemented tool. A tool is only routed here when its code is also
 * in ToolRegistry.AVAILABLE; otherwise the shared "Coming soon" screen is shown.
 */
object ToolScreens {
    val screens: Map<String, @Composable (ToolHost) -> Unit> = mapOf(
        // "P1" to { host -> MergeScreen(host) },
    )
}
