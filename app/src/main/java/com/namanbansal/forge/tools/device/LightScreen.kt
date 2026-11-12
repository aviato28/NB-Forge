package com.namanbansal.forge.tools.device

import android.hardware.Sensor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.device.LightRef
import com.namanbansal.forge.core.device.RunningStats
import com.namanbansal.forge.core.device.SensorEffect
import com.namanbansal.forge.core.device.rememberSensorHub
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.StatRow
import com.namanbansal.forge.ui.toolkit.ToolFrame
import kotlin.math.log10

/** D9: ambient light sensor in lux with a reference chart. */
@Composable
fun LightScreen(host: ToolHost) {
    val hub = rememberSensorHub()
    val available = remember(hub) { hub.isAvailable(Sensor.TYPE_LIGHT) }
    var lux by remember { mutableFloatStateOf(Float.NaN) }
    val stats = remember { RunningStats() }
    var tick by remember { mutableFloatStateOf(0f) }
    val c = ForgeTheme.colors

    if (available) SensorEffect(hub, Sensor.TYPE_LIGHT, android.hardware.SensorManager.SENSOR_DELAY_UI) { s ->
        lux = s.values[0]; stats.push(lux); tick++
    }
    ToolFrame(host) {
        if (!available) {
            SensorMissing("ambient light sensor", icon = ForgeIcons.Sun)
            ReferenceChart("Typical light levels", LightRef.REFERENCES.map { LightRef.format(it.lux) + " lx" to it.label })
            return@ToolFrame
        }
        val shown = if (lux.isNaN()) "--" else LightRef.format(lux)
        Readout(shown, if (lux.isNaN()) "Waiting for the sensor" else "lux · " + LightRef.describe(lux), size = 64, emphasised = true)
        // Log bar from 0.1 lx to 100,000 lx.
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawRoundRect(c.surface2, Offset.Zero, Size(size.width, size.height), CornerRadius(size.height / 2))
            if (!lux.isNaN()) {
                val f = ((log10(lux.coerceAtLeast(0.1f)) + 1f) / 6f).coerceIn(0.02f, 1f)
                drawRoundRect(c.accent, Offset.Zero, Size(size.width * f, size.height), CornerRadius(size.height / 2))
            }
        }
        if (stats.count > 0) {
            @Suppress("UNUSED_EXPRESSION") tick
            StatRow("Min" to LightRef.format(stats.min), "Average" to LightRef.format(stats.avg), "Max" to LightRef.format(stats.max))
        }
        val idx = if (lux.isNaN()) -1 else LightRef.REFERENCES.indexOfLast { it.lux <= lux }
        ReferenceChart("Typical light levels", LightRef.REFERENCES.map { LightRef.format(it.lux) + " lx" to it.label }, highlight = idx)
        InfoNote("Phone light sensors sit near the front camera and are not calibrated. Treat the number as a good estimate, not lab equipment.")
    }
}
