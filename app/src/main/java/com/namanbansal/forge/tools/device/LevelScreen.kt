package com.namanbansal.forge.tools.device

import android.hardware.Sensor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.device.LevelMath
import com.namanbansal.forge.core.device.LowPass3
import com.namanbansal.forge.core.device.SensorEffect
import com.namanbansal.forge.core.device.Tilt
import com.namanbansal.forge.core.device.ZeroCrossDetector
import com.namanbansal.forge.core.device.rememberSensorHub
import com.namanbansal.forge.core.device.rememberToolPrefs
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.toolkit.KeepScreenOn
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlin.math.abs
import kotlin.math.hypot

private val LevelGreen = Color(0xFF2FB36A)

/** D1: bubble level for flat surfaces and a bar level for a phone standing on its edge. */
@Composable
fun LevelScreen(host: ToolHost) {
    val hub = rememberSensorHub()
    val prefs = rememberToolPrefs()
    val haptics = rememberHaptics()
    val c = ForgeTheme.colors
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 surface, 1 edge
    var locked by rememberSaveable { mutableStateOf(false) }
    var frozenX by rememberSaveable { mutableFloatStateOf(0f) }
    var frozenY by rememberSaveable { mutableFloatStateOf(0f) }
    var frozenEdge by rememberSaveable { mutableFloatStateOf(0f) }
    var offX by remember { mutableFloatStateOf(prefs.float("level.offX", 0f)) }
    var offY by remember { mutableFloatStateOf(prefs.float("level.offY", 0f)) }
    var offEdge by remember { mutableFloatStateOf(prefs.float("level.offEdge", 0f)) }
    var rawTilt by remember { mutableStateOf(Tilt(0f, 0f, 0f)) }
    var rawEdge by remember { mutableFloatStateOf(0f) }
    val filter = remember { LowPass3(0.15f) }
    val zero = remember { ZeroCrossDetector() }
    val hasGravity = remember(hub) { hub.isAvailable(Sensor.TYPE_GRAVITY) }
    val hasAny = hasGravity || remember(hub) { hub.isAvailable(Sensor.TYPE_ACCELEROMETER) }
    val sensorType = if (hasGravity) Sensor.TYPE_GRAVITY else Sensor.TYPE_ACCELEROMETER

    if (hasAny) SensorEffect(hub, sensorType) { s ->
        filter.push(s.values[0], s.values[1], s.values[2])
        rawTilt = LevelMath.surfaceTilt(filter.x, filter.y, filter.z)
        rawEdge = LevelMath.edgeAngle(filter.x, filter.y)
        if (!locked) {
            val shown = if (mode == 0) hypot(rawTilt.x - offX, rawTilt.y - offY) else abs(rawEdge - offEdge)
            if (zero.update(shown)) haptics.tick()
        }
    }

    val x = if (locked) frozenX else rawTilt.x - offX
    val y = if (locked) frozenY else rawTilt.y - offY
    val edge = if (locked) frozenEdge else rawEdge - offEdge
    val total = if (mode == 0) hypot(x, y) else abs(edge)
    val isLevel = total < 0.5f

    KeepScreenOn()
    ToolFrame(host) {
        Segmented(listOf("Surface", "Edge"), mode, { mode = it })
        if (!hasAny) {
            SensorMissing("accelerometer", icon = ForgeIcons.Level)
            return@ToolFrame
        }
        Readout(
            value = if (mode == 0) LevelMath.tenths(total) else LevelMath.tenths(edge),
            caption = when {
                locked -> "Locked"
                isLevel -> "Level"
                mode == 0 -> "Off level"
                else -> "Off horizontal / vertical"
            },
            emphasised = isLevel && !locked, size = 56,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tilt ${LevelMath.tenths(total)}" },
        )
        if (mode == 0) {
            val (bx, by) = LevelMath.bubbleOffset(Tilt(x, y, total))
            BubbleDial(bx, by, isLevel, Modifier.fillMaxWidth().padding(horizontal = 24.dp).aspectRatio(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AxisValue("Left / right", x)
                AxisValue("Up / down", y)
            }
        } else {
            BarVial(edge, isLevel, Modifier.fillMaxWidth().height(120.dp))
            InfoNote("Stand the phone on its long edge on the surface you want to check, or on its short edge to check an upright post. The reading is the angle away from the nearest level or plumb position.")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeButton(
                if (locked) "Unlock" else "Lock reading", onClick = {
                    if (!locked) { frozenX = x; frozenY = y; frozenEdge = edge }
                    locked = !locked
                }, secondary = !locked, icon = if (locked) ForgeIcons.Unlock else ForgeIcons.Lock, modifier = Modifier.weight(1f),
            )
            ForgeButton("Calibrate", onClick = {
                if (mode == 0) { offX = rawTilt.x; offY = rawTilt.y; prefs.putFloat("level.offX", offX); prefs.putFloat("level.offY", offY) }
                else { offEdge = rawEdge; prefs.putFloat("level.offEdge", offEdge) }
                locked = false
            }, secondary = true, icon = ForgeIcons.Check, modifier = Modifier.weight(1f))
        }
        if (offX != 0f || offY != 0f || offEdge != 0f) {
            ForgeButton("Reset calibration", onClick = {
                offX = 0f; offY = 0f; offEdge = 0f
                prefs.remove("level.offX"); prefs.remove("level.offY"); prefs.remove("level.offEdge")
            }, secondary = true, icon = ForgeIcons.Refresh, modifier = Modifier.fillMaxWidth())
        } else {
            InfoNote("Calibrate on a surface you know is level to remove any sensor or case error.")
        }
    }
}

@Composable
private fun AxisValue(label: String, v: Float) {
    Readout(LevelMath.tenths(v), label, size = 22)
}

@Composable
private fun BubbleDial(bx: Float, by: Float, level: Boolean, modifier: Modifier) {
    val c = ForgeTheme.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(c.surface, r, center)
            drawCircle(if (level) LevelGreen else c.border, r, center, style = Stroke(width = if (level) 6f else 3f))
            drawCircle(c.border, r * 0.5f, center, style = Stroke(width = 2f))
            drawCircle(c.border, r * 0.12f, center, style = Stroke(width = 2f))
            drawLine(c.border, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 2f)
            drawLine(c.border, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 2f)
            val br = r * 0.14f
            val travel = r - br - 4f
            val p = Offset(center.x + bx * travel, center.y + by * travel)
            drawCircle((if (level) LevelGreen else c.accent).copy(alpha = 0.22f), br * 1.6f, p)
            drawCircle(if (level) LevelGreen else c.accent, br, p)
            drawCircle(Color.White.copy(alpha = 0.5f), br * 0.3f, Offset(p.x - br * 0.3f, p.y - br * 0.3f))
        }
    }
}

/** Horizontal vial: the bubble slides towards the high end, +/-10 degrees across the tube. */
@Composable
private fun BarVial(angle: Float, level: Boolean, modifier: Modifier) {
    val c = ForgeTheme.colors
    Canvas(modifier) {
        val h = 64f * density / 2.625f
        val top = (size.height - h) / 2
        drawRoundRect(c.surface, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(if (level) LevelGreen else c.border, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2), style = Stroke(if (level) 6f else 3f))
        val mid = size.width / 2
        val gap = h * 0.9f
        drawLine(c.text2, Offset(mid - gap, top + 8f), Offset(mid - gap, top + h - 8f), 3f)
        drawLine(c.text2, Offset(mid + gap, top + 8f), Offset(mid + gap, top + h - 8f), 3f)
        val br = h * 0.36f
        val travel = size.width / 2 - h / 2 - br
        val bx = mid + (-(angle) / 10f).coerceIn(-1f, 1f) * travel
        drawCircle((if (level) LevelGreen else c.accent), br, Offset(bx, top + h / 2))
    }
}
