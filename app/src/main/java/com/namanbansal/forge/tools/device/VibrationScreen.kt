package com.namanbansal.forge.tools.device

import android.hardware.Sensor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.device.GRAVITY
import com.namanbansal.forge.core.device.Ring
import com.namanbansal.forge.core.device.SensorEffect
import com.namanbansal.forge.core.device.VibrationMath
import com.namanbansal.forge.core.device.rememberSensorHub
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.toolkit.KeepScreenOn
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.StatRow
import com.namanbansal.forge.ui.toolkit.ToolFrame
import kotlin.math.abs
import kotlin.math.max

private const val HISTORY = 240

/** D10: live accelerometer seismograph (magnitude minus gravity) with a peak hold and a Mercalli-style label. */
@Composable
fun VibrationScreen(host: ToolHost) {
    val hub = rememberSensorHub()
    val available = remember(hub) { hub.isAvailable(Sensor.TYPE_ACCELEROMETER) }
    val ring = remember { Ring(HISTORY) }
    var version by remember { mutableIntStateOf(0) }
    var current by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }

    if (available) SensorEffect(hub, Sensor.TYPE_ACCELEROMETER) { s ->
        val d = VibrationMath.dynamic(s.values[0], s.values[1], s.values[2])
        current = d
        ring.push(d)
        peak = max(peak, abs(d))
        scale = max(1f, max(scale * 0.995f, abs(d) * 1.2f))
        version++
    }
    val peakG = peak / GRAVITY
    val numeral = VibrationMath.mercalli(peakG)
    KeepScreenOn()
    ToolFrame(host) {
        if (!available) {
            SensorMissing("accelerometer", icon = ForgeIcons.Pulse)
            return@ToolFrame
        }
        Readout("%.3f".format(java.util.Locale.US, abs(current)), "m/s² beyond gravity", size = 48)
        LineGraph(ring.snapshot(), -scale, scale, capacity = HISTORY, centerLine = true, version = version, height = 180.dp)
        StatRow("Peak hold" to "%.3f g".format(java.util.Locale.US, peakG), "Intensity" to numeral)
        Readout(VibrationMath.describe(numeral), "Modified Mercalli style estimate", size = 18, modifier = Modifier)
        ForgeButton("Reset peak", onClick = { peak = 0f; ring.clear(); scale = 1f }, secondary = true, icon = ForgeIcons.Refresh, modifier = Modifier.fillMaxWidth())
        InfoNote("Rest the phone on the surface you want to watch. The scale is a rough guide for felt shaking, not a certified seismometer.")
    }
}
