package com.namanbansal.forge.tools

import androidx.compose.runtime.Composable
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.tools.pdf.ExtractScreen
import com.namanbansal.forge.tools.pdf.InsertScreen
import com.namanbansal.forge.tools.pdf.MergeScreen
import com.namanbansal.forge.tools.pdf.PageManagerScreen
import com.namanbansal.forge.tools.pdf.RotateScreen
import com.namanbansal.forge.tools.pdf.SplitScreen
import com.namanbansal.forge.tools.pdf.ViewerScreen

/**
 * Context a tool screen gets from the host: its [tool], how to leave, and how to open another tool
 * with files already loaded (used by the result screen's "Next" chips).
 */
class ToolHost(
    val tool: ToolDef,
    val onBack: () -> Unit,
    val onOpenTool: (code: String, files: List<PickedFile>) -> Unit = { _, _ -> },
)

/**
 * code -> screen for every implemented tool. A tool is only routed here when its code is also
 * in ToolRegistry.AVAILABLE; otherwise the shared "Coming soon" screen is shown.
 */
object ToolScreens {
    val screens: Map<String, @Composable (ToolHost) -> Unit> = mapOf(
        "P1" to { host -> MergeScreen(host) },
        "P2" to { host -> SplitScreen(host) },
        "P3" to { host -> PageManagerScreen(host) },
        "P4" to { host -> RotateScreen(host) },
        "P5" to { host -> ExtractScreen(host) },
        "P6" to { host -> InsertScreen(host) },
        "P40" to { host -> ViewerScreen(host) },
        "P25" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.FILL_SIGN) },
        "P27" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.FILL_SIGN) },
        "P17" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.ANNOTATE) },
    )
}
