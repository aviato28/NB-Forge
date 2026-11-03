package com.namanbansal.forge.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Forge line icons: 24x24 grid, 1.75 stroke, round caps/joins (see docs/brand/mockups.html).
 * Built from SVG path data so they stay in sync with the brand sheet. Tint via Icon(tint = ...).
 */
object ForgeIcons {

    private fun line(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    // SVG primitive helpers -> path data
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 0f): String =
        if (r == 0f) "M$x ${y}h${w}v${h}h${-w}z"
        else "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    /** Filled anvil + spark from app icon B, on the 108-unit adaptive-icon grid (no tile). */
    val AnvilGlyph by lazy {
        ImageVector.Builder("anvil", 24.dp, 24.dp, 108f, 108f)
            .addPath(addPathNodes("M26 41H80Q80 53 69 53H65L67 60H73V68H35V60H41L43 53H41Q29 53 26 41Z"), fill = SolidColor(Color.Black))
            .addPath(addPathNodes("M70 24L71.7 28.3L76 30L71.7 31.7L70 36L68.3 31.7L64 30L68.3 28.3Z"), fill = SolidColor(Color.Black))
            .build()
    }

    val Search by lazy { line("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5") }
    val Settings by lazy { line("settings", "M4 7h10M18 7h2M4 17h2M10 17h10", circle(16f, 7f, 2f), circle(8f, 17f, 2f)) }
    val Home by lazy { line("home", "M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z") }
    val Folder by lazy { line("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val File by lazy { line("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5") }
    val FileText by lazy { line("filetext", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M9 13h6M9 17h4") }
    val Merge by lazy { line("merge", rect(3f, 3f, 12f, 12f, 2f), rect(9f, 9f, 12f, 12f, 2f)) }
    val Compress by lazy { line("compress", "M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7") }
    val Scan by lazy { line("scan", "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M4 12h16") }
    val Level by lazy { line("level", rect(2f, 8f, 20f, 8f, 4f), circle(12f, 12f, 2f)) }
    val Image by lazy { line("image", rect(3f, 4f, 18f, 16f, 3f), circle(9f, 10f, 1.5f), "M21 16l-5-5-8 8") }
    val Video by lazy { line("video", rect(3f, 6f, 13f, 12f, 2.5f), "M16 10l5-3v10l-5-3") }
    val Phone by lazy { line("phone", rect(7f, 2f, 10f, 20f, 2.5f), "M11 18h2") }
    val Calc by lazy { line("calc", rect(5f, 3f, 14f, 18f, 2.5f), "M8.5 7.5h7M9 12h.01M12 12h.01M15 12h.01M9 16h.01M12 16h.01M15 16h.01") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7.5") }
    val Rotate by lazy { line("rotate", "M20 12a8 8 0 1 1-2.5-5.8M20 4v4h-4") }
    val Copy by lazy { line("copy", rect(8f, 8f, 12f, 12f, 2f), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2") }
    val Extract by lazy { line("extract", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M12 17v-6M9.5 13.5L12 11l2.5 2.5") }
    val Download by lazy { line("download", "M12 3v12M7 10l5 5 5-5M5 20h14") }
    val Open by lazy { line("open", "M14 4h6v6M20 4l-9 9M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4") }
    val Chevron by lazy { line("chev", "M9 6l6 6-6 6") }
    val Back by lazy { line("back", "M15 6l-6 6 6 6") }
    val Lock by lazy { line("lock", rect(5f, 11f, 14f, 10f, 2.5f), "M8 11V8a4 4 0 0 1 8 0v3") }
    val Unlock by lazy { line("unlock", rect(5f, 11f, 14f, 10f, 2.5f), "M8 11V8a4 4 0 0 1 7.8-1.2") }
    val Drop by lazy { line("drop", "M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z") }
    val Swap by lazy { line("swap", "M7 4L3 8l4 4M3 8h14M17 20l4-4-4-4M21 16H7") }
    val Flash by lazy { line("flash", "M13 3L5 13h6l-1 8 8-10h-6z") }
    val Close by lazy { line("x", "M6 6l12 12M18 6L6 18") }
    val Clock by lazy { line("clock", circle(12f, 12f, 9f), "M12 7v5l3 2") }
    val Compass by lazy { line("compass", circle(12f, 12f, 9f), "M15.5 8.5l-2 5-5 2 2-5z") }
    val Pen by lazy { line("pen", "M4 20l1-4L16 5a2.1 2.1 0 0 1 3 3L8 19z") }
    val Type by lazy { line("type", "M5 6V4h14v2M12 4v16M9 20h6") }
    val Layers by lazy { line("layers", "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5") }
    val Hash by lazy { line("hash", "M5 9h14M5 15h14M10 4L8 20M16 4l-2 16") }
    val Split by lazy { line("split", circle(6f, 6f, 2.5f), circle(6f, 18f, 2.5f), "M8 7.5L20 18M8 16.5L20 6") }
    val Grid by lazy { line("grid", rect(4f, 4f, 7f, 7f, 1.5f), rect(13f, 4f, 7f, 7f, 1.5f), rect(4f, 13f, 7f, 7f, 1.5f), rect(13f, 13f, 7f, 7f, 1.5f)) }
    val Qr by lazy { line("qr", rect(3f, 3f, 7f, 7f, 1.5f), rect(14f, 3f, 7f, 7f, 1.5f), rect(3f, 14f, 7f, 7f, 1.5f), "M14 14h3v3h-3zM20 14v.01M14 20h.01M20 18v3h-3") }
    val Shield by lazy { line("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z") }
    val Crop by lazy { line("crop", "M6 2v14a2 2 0 0 0 2 2h14M2 6h14a2 2 0 0 1 2 2v14") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val Sparkle by lazy { line("sparkle", "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z", "M19 16v4M17 18h4") }
    val Eraser by lazy { line("eraser", "M7 21h10M5 15l9-9 5 5-6 6H8z") }
    val Bookmark by lazy { line("bookmark", "M6 3h12v18l-6-4-6 4z") }
    val Info by lazy { line("info", circle(12f, 12f, 9f), "M12 11v5M12 8h.01") }
    val Link by lazy { line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Eye by lazy { line("eye", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", circle(12f, 12f, 3f)) }
    val Flow by lazy { line("flow", rect(3f, 3f, 6f, 6f, 1.5f), rect(15f, 15f, 6f, 6f, 1.5f), "M9 6h5a3 3 0 0 1 3 3v6") }
    val Diff by lazy { line("diff", rect(3f, 4f, 7f, 16f, 1.5f), rect(14f, 4f, 7f, 16f, 1.5f), "M5.5 9h2M16.5 9h2M17.5 8v2") }
    val Scissors by lazy { line("scissors", circle(6f, 6f, 2.5f), circle(6f, 18f, 2.5f), "M8 7.5L20 18M8 16.5L20 6") }
    val Music by lazy { line("music", "M9 18V5l11-2v13", circle(6.5f, 18f, 2.5f), circle(17.5f, 16f, 2.5f)) }
    val Mute by lazy { line("mute", "M11 5L6 9H3v6h3l5 4z", "M22 9l-6 6M16 9l6 6") }
    val Gauge by lazy { line("gauge", "M4.2 18a9 9 0 1 1 15.6 0", "M12 14l4-5") }
    val Film by lazy { line("film", rect(3f, 4f, 18f, 16f, 2.5f), "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4") }
    val Ruler by lazy { line("ruler", rect(2f, 8f, 20f, 8f, 1.5f), "M6 8v3M10 8v4M14 8v3M18 8v4") }
    val Angle by lazy { line("angle", "M4 20h16M4 20L16 6", "M10 20a6 6 0 0 0-1.6-4.1") }
    val Mic by lazy { line("mic", rect(9f, 3f, 6f, 12f, 3f), "M19 11a7 7 0 0 1-14 0M12 18v3") }
    val Torch by lazy { line("torch", "M8 3h8v4l-2 4v10h-4V11L8 7z", "M8 7h8M12 14v2") }
    val ZoomIn by lazy { line("zoom", circle(11f, 11f, 7f), "M20 20l-3.5-3.5M11 8v6M8 11h6") }
    val Sun by lazy { line("sun", circle(12f, 12f, 4f), "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4") }
    val Pulse by lazy { line("pulse", "M3 12h4l3-8 4 16 3-8h4") }
    val Currency by lazy { line("currency", "M12 2v20M17 6H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6") }
    val Percent by lazy { line("percent", "M19 5L5 19", circle(7f, 7f, 2.5f), circle(17f, 17f, 2.5f)) }
    val Calendar by lazy { line("calendar", rect(3f, 5f, 18f, 16f, 2.5f), "M3 10h18M8 3v4M16 3v4") }
    val Globe by lazy { line("globe", circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18") }
    val Dice by lazy { line("dice", rect(4f, 4f, 16f, 16f, 3f), "M9 9h.01M15 15h.01M15 9h.01M9 15h.01M12 12h.01") }
    val Counter by lazy { line("counter", rect(3f, 5f, 18f, 14f, 3f), "M8 12h3M9.5 10.5v3M14 12h3") }
    val Bank by lazy { line("bank", "M3 10l9-6 9 6M5 10v8M10 10v8M14 10v8M19 10v8M3 21h18") }
    val Heart by lazy { line("heart", "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z") }
    val Archive by lazy { line("archive", rect(3f, 4f, 18f, 5f, 1.5f), "M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4") }
    val Palette by lazy { line("palette", "M12 3a9 9 0 1 0 0 18c1.1 0 1.5-.9 1.2-1.7-.4-1 .2-2.3 1.4-2.3H17a4 4 0 0 0 4-4c0-5.5-4-10-9-10z", "M7.5 12h.01M9.5 7.5h.01M14.5 7.5h.01") }
    val Pin by lazy { line("pin", "M9 3h6l-1 6 4 4H6l4-4zM12 13v8") }
    val Refresh by lazy { line("refresh", "M20 12a8 8 0 1 1-2.5-5.8M20 4v4h-4") }
    val Code by lazy { line("code", "M8 7l-5 5 5 5M16 7l5 5-5 5") }
    val Wand by lazy { line("wand", "M4 20L15 9M14 4v2M18 8h2M18.5 4.5l-1.4 1.4M10 5.5l1 1") }
    val Share by lazy { line("share", circle(6f, 12f, 2.5f), circle(18f, 6f, 2.5f), circle(18f, 18f, 2.5f), "M8.2 10.8l7.6-3.6M8.2 13.2l7.6 3.6") }
    val Trash by lazy { line("trash", "M4 7h16M10 7V4h4v3M6 7l1 13h10l1-13M10 11v6M14 11v6") }
    val Highlight by lazy { line("highlight", "M9 11l7-7 4 4-7 7z", "M9 11l-5 5v4h4l3-3", "M4 21h16") }
    val Underline by lazy { line("underline", "M7 4v7a5 5 0 0 0 10 0V4", "M5 21h14") }
    val Strike by lazy { line("strike", "M4 12h16", "M16.5 7A4.5 3 0 0 0 12 5c-2.8 0-4.5 1.3-4.5 3s1.7 2.7 4.5 3", "M8 17a4.5 3 0 0 0 4 2c2.8 0 4.5-1.3 4.5-3") }
    val Note by lazy { line("note", "M5 4h14a1 1 0 0 1 1 1v10l-5 5H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M15 20v-4a1 1 0 0 1 1-1h4") }
    val Pointer by lazy { line("pointer", "M5 3l14 7-6 2-2 6z") }
    val Save by lazy { line("save", "M5 4h11l3 3v13H5z", "M8 4v5h7V4", "M8 20v-6h8v6") }
    val More by lazy { line("more", "M5 12h.01M12 12h.01M19 12h.01") }
    val Grip by lazy { line("grip", "M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01") }
    val Minus by lazy { line("minus", "M5 12h14") }
    val Undo by lazy { line("undo", "M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3") }
    val Redo by lazy { line("redo", "M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3") }
    val Moon by lazy { line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z") }
    val ChevronUp by lazy { line("chev-up", "M6 15l6-6 6 6") }
    val ChevronDown by lazy { line("chev-down", "M6 9l6 6 6-6") }
    val RotateLeft by lazy { line("rotate-left", "M4 12a8 8 0 1 0 2.5-5.8M4 4v4h4") }
    val Rotate180 by lazy { line("rotate-180", "M4 12a8 8 0 0 1 14-5.3M18 4v3.5h-3.5M20 12a8 8 0 0 1-14 5.3M6 20v-3.5h3.5") }
}
