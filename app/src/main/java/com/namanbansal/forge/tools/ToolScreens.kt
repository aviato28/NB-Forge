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
        "U1" to { host -> com.namanbansal.forge.tools.util.UnitsScreen(host) },
        "U2" to { host -> com.namanbansal.forge.tools.util.CurrencyScreen(host) },
        "U3" to { host -> com.namanbansal.forge.tools.util.CalculatorScreen(host) },
        "U4" to { host -> com.namanbansal.forge.tools.util.PercentScreen(host) },
        "U5" to { host -> com.namanbansal.forge.tools.util.DateScreen(host) },
        "U6" to { host -> com.namanbansal.forge.tools.util.ZonesScreen(host) },
        "U7" to { host -> com.namanbansal.forge.tools.util.TimersScreen(host) },
        "U8" to { host -> com.namanbansal.forge.tools.util.RandomScreen(host) },
        "U9" to { host -> com.namanbansal.forge.tools.util.TallyScreen(host) },
        "U10" to { host -> com.namanbansal.forge.tools.util.LoanScreen(host) },
        "U11" to { host -> com.namanbansal.forge.tools.util.HealthScreen(host) },
        "U12" to { host -> com.namanbansal.forge.tools.util.QrGenScreen(host) },
        "D1" to { host -> com.namanbansal.forge.tools.device.LevelScreen(host) },
        "D2" to { host -> com.namanbansal.forge.tools.device.RulerScreen(host) },
        "D3" to { host -> com.namanbansal.forge.tools.device.ProtractorScreen(host) },
        "D4" to { host -> com.namanbansal.forge.tools.device.CompassScreen(host) },
        "D5" to { host -> com.namanbansal.forge.tools.device.SoundScreen(host) },
        "D6" to { host -> com.namanbansal.forge.tools.device.FlashlightScreen(host) },
        "D7" to { host -> com.namanbansal.forge.tools.device.MagnifierScreen(host) },
        "D8" to { host -> com.namanbansal.forge.tools.device.ScannerScreen(host) },
        "D9" to { host -> com.namanbansal.forge.tools.device.LightScreen(host) },
        "D10" to { host -> com.namanbansal.forge.tools.device.VibrationScreen(host) },
        "I2" to { host -> com.namanbansal.forge.tools.image.CompressScreen(host) },
        "I3" to { host -> com.namanbansal.forge.tools.image.ConvertScreen(host) },
        "I5" to { host -> com.namanbansal.forge.tools.image.StripScreen(host) },
        "I7" to { host -> com.namanbansal.forge.tools.image.UpscaleScreen(host) },
        "I6" to { host -> com.namanbansal.forge.tools.image.PaletteScreen(host) },
        "I11" to { host -> com.namanbansal.forge.tools.image.Base64Screen(host) },
        "F1" to { host -> com.namanbansal.forge.tools.image.ZipScreen(host) },
        "I4" to { host -> com.namanbansal.forge.tools.image.BgRemoveScreen(host) },
        "I9" to { host -> com.namanbansal.forge.tools.image.CollageScreen(host) },
        "I10" to { host -> com.namanbansal.forge.tools.image.MemeScreen(host) },
        "I12" to { host -> com.namanbansal.forge.tools.image.AnnotateScreen(host) },
        "I1" to { host -> com.namanbansal.forge.tools.image.ResizeScreen(host) },
        "P1" to { host -> MergeScreen(host) },
        "P2" to { host -> SplitScreen(host) },
        "P3" to { host -> PageManagerScreen(host) },
        "P4" to { host -> RotateScreen(host) },
        "P5" to { host -> ExtractScreen(host) },
        "P6" to { host -> InsertScreen(host) },
        "P23" to { host -> com.namanbansal.forge.tools.pdf.MetadataScreen(host) },
        "P11" to { host -> com.namanbansal.forge.tools.pdf.FlattenScreen(host) },
        "P12" to { host -> com.namanbansal.forge.tools.pdf.RepairScreen(host) },
        "P29" to { host -> com.namanbansal.forge.tools.pdf.ProtectScreen(host) },
        "P30" to { host -> com.namanbansal.forge.tools.pdf.UnlockScreen(host) },
        "P9" to { host -> com.namanbansal.forge.tools.pdf.CompressScreen(host) },
        "P10" to { host -> com.namanbansal.forge.tools.pdf.GrayscaleScreen(host) },
        "P14" to { host -> com.namanbansal.forge.tools.pdf.OptimiseScreen(host) },
        "P20" to { host -> com.namanbansal.forge.tools.pdf.WatermarkScreen(host) },
        "P21" to { host -> com.namanbansal.forge.tools.pdf.StampScreen(host) },
        "P40" to { host -> ViewerScreen(host) },
        "P25" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.FILL_SIGN) },
        "P27" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.FILL_SIGN) },
        "P15" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.EDIT, startTool = com.namanbansal.forge.tools.pdf.EditTool.EXISTING) },
        "P16" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.EDIT, startTool = com.namanbansal.forge.tools.pdf.EditTool.TEXT) },
        "P18" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.EDIT, startTool = com.namanbansal.forge.tools.pdf.EditTool.WHITEOUT) },
        "P19" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.EDIT, startTool = com.namanbansal.forge.tools.pdf.EditTool.REDACT) },
        "P17" to { host -> ViewerScreen(host, startMode = com.namanbansal.forge.tools.pdf.ViewerModeId.ANNOTATE) },
    )
}
