package com.namanbansal.forge.tools.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.core.device.AngleSmoother
import com.namanbansal.forge.core.device.CompassMath
import com.namanbansal.forge.core.device.SensorEffect
import com.namanbansal.forge.core.device.needsCalibration
import com.namanbansal.forge.core.device.norm360
import com.namanbansal.forge.core.device.rememberSensorHub
import com.namanbansal.forge.core.device.shortestDelta
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.Perms
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** D4: rotation-vector compass, true north when a coarse location is available, calibration prompt and a lock heading. */
@Composable
fun CompassScreen(host: ToolHost) {
    val hub = rememberSensorHub()
    val context = LocalContext.current
    val c = ForgeTheme.colors
    val available = remember(hub) { hub.isAvailable(Sensor.TYPE_ROTATION_VECTOR) }
    val smoother = remember { AngleSmoother(0.18f) }
    var magnetic by remember { mutableFloatStateOf(0f) }
    var accuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }
    var lockedAt by rememberSaveable { mutableStateOf<Float?>(null) }
    var wantTrue by rememberSaveable { mutableStateOf(true) }
    var declination by remember { mutableStateOf<Float?>(null) }
    val location = rememberPermission(Perms.COARSE_LOCATION)
    val matrix = remember { FloatArray(9) }

    if (available) SensorEffect(hub, Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_UI) { s ->
        SensorManager.getRotationMatrixFromVector(matrix, s.values)
        magnetic = smoother.push(CompassMath.heading(matrix))
        accuracy = s.accuracy
    }
    LaunchedEffect(location.granted) {
        if (location.granted) declination = withContext(Dispatchers.IO) { declinationFor(context) }
    }
    val useTrue = wantTrue && declination != null
    val heading = if (useTrue) CompassMath.trueHeading(magnetic, declination ?: 0f) else magnetic

    ToolFrame(host) {
        if (!available) {
            SensorMissing("rotation or magnetic field sensor", icon = ForgeIcons.Compass)
            return@ToolFrame
        }
        Readout("%d°".format(Math.round(heading) % 360), "${CompassMath.cardinal(heading)} · ${if (useTrue) "true north" else "magnetic north"}", size = 56)
        Dial(heading, lockedAt, Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(1f))
        lockedAt?.let { locked ->
            val d = shortestDelta(heading, locked)
            Readout(if (abs(d) < 1f) "On heading" else "%d° %s".format(Math.round(abs(d)), if (d > 0) "to the right" else "to the left"), "Locked at ${Math.round(locked)}°", size = 22)
        }
        if (needsCalibration(accuracy)) CalibrationPrompt()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeButton(
                if (lockedAt == null) "Lock heading" else "Clear lock",
                onClick = { lockedAt = if (lockedAt == null) heading else null },
                secondary = lockedAt != null, icon = if (lockedAt == null) ForgeIcons.Lock else ForgeIcons.Unlock, modifier = Modifier.weight(1f),
            )
            if (declination != null) ForgeButton(if (wantTrue) "Use magnetic" else "Use true north", onClick = { wantTrue = !wantTrue }, secondary = true, icon = ForgeIcons.Globe, modifier = Modifier.weight(1f))
        }
        if (!location.granted) {
            ForgeCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text("True north", style = ForgeType.Item, color = c.text)
                        androidx.compose.material3.Text("Optional: allow approximate location to correct for magnetic declination. It never leaves your phone.", style = ForgeType.Small, color = c.text2)
                    }
                    ForgeButton("Allow", onClick = location::request, modifier = Modifier.height(44.dp))
                }
            }
        } else if (declination == null) {
            InfoNote("No recent location fix, showing magnetic north. Open a maps app once to get one.")
        }
    }
}

@SuppressLint("MissingPermission")
internal fun declinationFor(context: Context): Float? = try {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    loc?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination }
} catch (_: SecurityException) { null }

@Composable
private fun CalibrationPrompt() {
    val c = ForgeTheme.colors
    ForgeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Canvas(Modifier.height(56.dp).aspectRatio(1.6f)) {
                // A lemniscate (figure 8) traced with the Lissajous form x = sin t, y = sin 2t / 2.
                val path = Path()
                val w = size.width / 2 - 4f; val h = size.height / 2 - 4f
                for (i in 0..120) {
                    val t = i / 120f * 2 * Math.PI
                    val x = size.width / 2 + (sin(t) * w).toFloat(); val y = size.height / 2 + (sin(2 * t) * h).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c.accent, style = Stroke(width = 4f))
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text("Calibrate the compass", style = ForgeType.Item, color = c.text)
                androidx.compose.material3.Text("The reading looks unreliable. Wave the phone in a figure-8 a few times, away from magnets and metal.", style = ForgeType.Small, color = c.text2)
            }
        }
    }
}

@Composable
private fun Dial(heading: Float, locked: Float?, modifier: Modifier) {
    val c = ForgeTheme.colors
    val measurer = rememberTextMeasurer()
    val cardinalStyle = TextStyle(fontSize = 20.sp, color = c.text, fontFamily = ForgeType.Item.fontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    val numberStyle = TextStyle(fontSize = 11.sp, color = c.text2, fontFamily = ForgeType.Item.fontFamily)
    Canvas(modifier) {
        val r = size.minDimension / 2f - 16f
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(c.surface, r, center)
        drawCircle(c.border, r, center, style = Stroke(3f))
        rotate(-heading, center) {
            for (deg in 0 until 360 step 5) {
                val major = deg % 30 == 0
                val len = if (deg % 90 == 0) 0f else if (major) 16f else 8f
                if (len > 0f) {
                    rotate(deg.toFloat(), center) {
                        drawLine(if (major) c.text2 else c.border, Offset(center.x, center.y - r + 4f), Offset(center.x, center.y - r + 4f + len), if (major) 3f else 2f)
                    }
                }
            }
            listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (deg, label) ->
                rotate(deg.toFloat(), center) {
                    val layout = measurer.measure(label, if (deg == 0) cardinalStyle.copy(color = c.accent) else cardinalStyle)
                    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - r + 14f))
                }
            }
            for (deg in 30 until 360 step 30) {
                if (deg % 90 == 0) continue
                rotate(deg.toFloat(), center) {
                    val layout = measurer.measure(deg.toString(), numberStyle)
                    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - r + 24f))
                }
            }
            locked?.let { l ->
                rotate(l, center) {
                    drawLine(Color(0xFF2FB36A), Offset(center.x, center.y - r + 2f), Offset(center.x, center.y - r * 0.55f), 6f)
                }
            }
        }
        // Fixed lubber line (where the phone points) and the centre pivot.
        val tri = Path().apply {
            moveTo(center.x, center.y - r + 4f); lineTo(center.x - 12f, center.y - r - 8f); lineTo(center.x + 12f, center.y - r - 8f); close()
        }
        drawPath(tri, c.accent)
        drawLine(c.accent, Offset(center.x, center.y - r * 0.3f), Offset(center.x, center.y - r * 0.8f), 5f)
        drawCircle(c.text, 7f, center)
    }
}
