package com.namanbansal.forge.tools.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.core.device.RulerMath
import com.namanbansal.forge.core.device.rememberToolPrefs
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame

private const val KEY = "ruler.pxPerMm"

/** D2: on-screen ruler (cm on the left, inches on the right) calibrated from display metrics or a credit card. */
@Composable
fun RulerScreen(host: ToolHost) {
    val context = LocalContext.current
    val prefs = rememberToolPrefs()
    val c = ForgeTheme.colors
    val metrics = context.resources.displayMetrics
    val defaultPxPerMm = remember { RulerMath.pxPerMm(if (metrics.ydpi > 0f) metrics.ydpi else metrics.densityDpi.toFloat()) }
    var pxPerMm by remember { mutableFloatStateOf(prefs.float(KEY, defaultPxPerMm)) }
    var calibrating by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableFloatStateOf(pxPerMm) }
    val calibrated = pxPerMm != defaultPxPerMm

    ToolFrame(host, scroll = false, padding = false) {
        if (calibrating) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Hold a credit or bank card flat against the screen and slide until the outline is exactly the card's length (85.6 mm).", style = ForgeType.Body, color = c.text2)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val w = with(androidx.compose.ui.platform.LocalDensity.current) { (RulerMath.CARD_HEIGHT_MM * draft).toDp() }
                    val h = with(androidx.compose.ui.platform.LocalDensity.current) { (RulerMath.CARD_MM * draft).toDp() }
                    Box(Modifier.size(w, h).background(c.tint, RoundedCornerShape(10.dp)).border(2.dp, c.accent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text("85.6 mm", style = ForgeType.Label, color = c.accent)
                    }
                }
                Slider(
                    value = draft / defaultPxPerMm, onValueChange = { draft = it * defaultPxPerMm }, valueRange = 0.7f..1.3f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ForgeButton("Cancel", onClick = { calibrating = false }, secondary = true, modifier = Modifier.weight(1f))
                    ForgeButton("Save", onClick = { pxPerMm = draft; prefs.putFloat(KEY, draft); calibrating = false }, icon = ForgeIcons.Check, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Ruler(pxPerMm, Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (calibrated) "Calibrated to your card" else "Using your screen's reported size", style = ForgeType.Small, color = c.text2)
                    ForgeButton("Calibrate with a card", onClick = { draft = pxPerMm; calibrating = true }, secondary = true, icon = ForgeIcons.Ruler)
                    if (calibrated) ForgeButton("Reset", onClick = { pxPerMm = defaultPxPerMm; prefs.remove(KEY) }, secondary = true, icon = ForgeIcons.Refresh)
                }
            }
        }
    }
}

@Composable
private fun Ruler(pxPerMm: Float, modifier: Modifier) {
    val c = ForgeTheme.colors
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 12.sp, color = c.text, fontFamily = ForgeType.Item.fontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Canvas(modifier) {
        val lengthMm = (size.height / pxPerMm).toInt() + 1
        val d = density
        for (t in RulerMath.metricTicks(lengthMm)) {
            val y = t.mm * pxPerMm
            val len = when (t.kind) { RulerMath.Kind.CM -> 34f; RulerMath.Kind.HALF -> 22f; else -> 13f } * d / 2.6f
            drawLine(c.text2, Offset(0f, y), Offset(len, y), strokeWidth = if (t.kind == RulerMath.Kind.CM) 3f else 2f)
            t.label?.let { if (t.mm > 0) drawText(measurer.measure(it, label), topLeft = Offset(len + 6f, y - 14f)) }
        }
        val inches = (lengthMm / RulerMath.MM_PER_INCH).toInt() + 1
        for (t in RulerMath.inchTicks(inches)) {
            val y = t.mm * pxPerMm
            if (y > size.height) break
            val len = when (t.kind) { RulerMath.Kind.CM -> 34f; RulerMath.Kind.HALF -> 24f; else -> 13f } * d / 2.6f
            drawLine(c.accent, Offset(size.width, y), Offset(size.width - len, y), strokeWidth = if (t.kind == RulerMath.Kind.CM) 3f else 2f)
            t.label?.let {
                if (t.mm > 0) {
                    val l = measurer.measure(it, label.copy(color = c.accent))
                    drawText(l, topLeft = Offset(size.width - len - 6f - l.size.width, y - 14f))
                }
            }
        }
        val unit = measurer.measure("cm", label.copy(color = c.text2))
        drawText(unit, topLeft = Offset(50f * d / 2.6f, 4f))
        val inch = measurer.measure("in", label.copy(color = c.accent))
        drawText(inch, topLeft = Offset(size.width - 50f * d / 2.6f - inch.size.width, 4f))
    }
}
