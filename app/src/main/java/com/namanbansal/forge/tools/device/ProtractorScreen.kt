package com.namanbansal.forge.tools.device

import android.hardware.Sensor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.device.AngleMath
import com.namanbansal.forge.core.device.Inclinometer
import com.namanbansal.forge.core.device.LevelMath
import com.namanbansal.forge.core.device.LocalCameraBackend
import com.namanbansal.forge.core.device.LowPass3
import com.namanbansal.forge.core.device.SensorEffect
import com.namanbansal.forge.core.device.rememberSensorHub
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.KeepScreenOn
import com.namanbansal.forge.ui.toolkit.Perms
import com.namanbansal.forge.ui.toolkit.PermissionGate
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.ToolFrame
import androidx.compose.material3.Text
import kotlin.math.abs

/** D3: camera-overlay protractor with two draggable arms, plus an inclinometer that uses the tilt sensor. */
@Composable
fun ProtractorScreen(host: ToolHost) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    ToolFrame(host, scroll = mode == 1) {
        Segmented(listOf("Camera protractor", "Inclinometer"), mode, { mode = it })
        if (mode == 0) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PermissionGate(
                    Perms.CAMERA, ForgeIcons.Angle, "Camera access",
                    "The camera shows what you are measuring behind the arms. Nothing is recorded or sent anywhere.",
                ) { CameraProtractor() }
            }
        } else {
            InclinometerPane()
        }
    }
}

@Composable
private fun CameraProtractor() {
    val c = ForgeTheme.colors
    val backend = LocalCameraBackend.current
    // Arm tips as fractions of the view so rotation keeps them in place.
    var a1x by rememberSaveable { mutableFloatStateOf(0.85f) }
    var a1y by rememberSaveable { mutableFloatStateOf(0.62f) }
    var a2x by rememberSaveable { mutableFloatStateOf(0.5f) }
    var a2y by rememberSaveable { mutableFloatStateOf(0.18f) }
    var drag by remember { mutableIntStateOf(0) }
    val arm = Color.White
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black, RoundedCornerShape(20.dp))) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        backend.Preview(Modifier.fillMaxSize(), null, null) {}
        val cx = w * 0.5f; val cy = h * 0.62f
        val angle = AngleMath.between(cx, cy, a1x * w, a1y * h, a2x * w, a2y * h)
        Canvas(
            Modifier.fillMaxSize().pointerInput(w, h) {
                detectDragGestures(
                    onDragStart = { p ->
                        val d1 = (p - Offset(a1x * w, a1y * h)).getDistance()
                        val d2 = (p - Offset(a2x * w, a2y * h)).getDistance()
                        drag = if (d1 <= d2) 1 else 2
                    },
                    onDragEnd = { drag = 0 }, onDragCancel = { drag = 0 },
                ) { change, delta ->
                    change.consume()
                    if (drag == 1) { a1x = (a1x + delta.x / w).coerceIn(0f, 1f); a1y = (a1y + delta.y / h).coerceIn(0f, 1f) }
                    else if (drag == 2) { a2x = (a2x + delta.x / w).coerceIn(0f, 1f); a2y = (a2y + delta.y / h).coerceIn(0f, 1f) }
                }
            },
        ) {
            val center = Offset(cx, cy)
            fun extend(tx: Float, ty: Float): Offset {
                val v = Offset(tx * w - cx, ty * h - cy)
                val len = v.getDistance().coerceAtLeast(1f)
                return Offset(cx + v.x / len * (w + h), cy + v.y / len * (w + h))
            }
            drawLine(arm.copy(alpha = 0.9f), center, extend(a1x, a1y), 5f)
            drawLine(arm.copy(alpha = 0.9f), center, extend(a2x, a2y), 5f)
            drawArc(c.accent, startAngle = Math.toDegrees(kotlin.math.atan2((a1y * h - cy).toDouble(), (a1x * w - cx).toDouble())).toFloat(),
                sweepAngle = signedSweep(cx, cy, a1x * w, a1y * h, a2x * w, a2y * h), useCenter = true, topLeft = Offset(cx - 70f, cy - 70f), size = androidx.compose.ui.geometry.Size(140f, 140f), alpha = 0.55f)
            drawCircle(arm, 10f, center)
            drawCircle(c.accent, 26f, Offset(a1x * w, a1y * h), style = Stroke(5f))
            drawCircle(c.accent, 26f, Offset(a2x * w, a2y * h), style = Stroke(5f))
        }
        Text(
            LevelMath.tenths(angle).let { it.replace("°", "°") }, style = ForgeType.Title, color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

private fun signedSweep(cx: Float, cy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val a1 = Math.toDegrees(kotlin.math.atan2((ay - cy).toDouble(), (ax - cx).toDouble()))
    val a2 = Math.toDegrees(kotlin.math.atan2((by - cy).toDouble(), (bx - cx).toDouble()))
    var d = a2 - a1
    while (d > 180) d -= 360
    while (d < -180) d += 360
    return d.toFloat()
}

@Composable
private fun InclinometerPane() {
    val hub = rememberSensorHub()
    val available = remember(hub) { hub.isAvailable(Sensor.TYPE_GRAVITY) || hub.isAvailable(Sensor.TYPE_ACCELEROMETER) }
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var angle by remember { mutableFloatStateOf(0f) }
    val filter = remember { LowPass3(0.15f) }
    val type = if (hub.isAvailable(Sensor.TYPE_GRAVITY)) Sensor.TYPE_GRAVITY else Sensor.TYPE_ACCELEROMETER
    if (available) SensorEffect(hub, type) { s ->
        filter.push(s.values[0], s.values[1], s.values[2])
        angle = if (sub == 0) Inclinometer.edgeSlope(filter.y, filter.x, filter.z) else Inclinometer.sight(filter.x, filter.y, filter.z)
    }
    KeepScreenOn()
    if (!available) { SensorMissing("accelerometer", icon = ForgeIcons.Angle); return }
    Segmented(listOf("Long edge on slope", "Sight through camera"), sub, { sub = it })
    Readout(LevelMath.tenths(angle), "elevation", size = 64, emphasised = true)
    val grade = Inclinometer.grade(angle)
    Readout(if (grade == null) "vertical" else "%.1f %%".format(java.util.Locale.US, abs(grade)), "slope grade", size = 24)
    InfoNote(if (sub == 0) "Lay the phone's long edge along the slope or pipe. 0° is flat, 90° is vertical." else "Hold the phone upright and look along its back at the top of a tree or building. The angle is how far above the horizon the camera points.")
}
