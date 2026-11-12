package com.namanbansal.forge.tools.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.namanbansal.forge.core.device.LocalAudioLevelSource
import com.namanbansal.forge.core.device.Ring
import com.namanbansal.forge.core.device.RunningStats
import com.namanbansal.forge.core.device.SoundMath
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.KeepScreenOn
import com.namanbansal.forge.ui.toolkit.Perms
import com.namanbansal.forge.ui.toolkit.PermissionGate
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.StatRow
import com.namanbansal.forge.ui.toolkit.ToolFrame

private const val HISTORY = 150

/** D5: dB SPL estimate from the microphone, with min / average / max, a live graph and a reference chart. */
@Composable
fun SoundScreen(host: ToolHost) {
    ToolFrame(host) {
        PermissionGate(
            Perms.MIC, ForgeIcons.Mic, "Microphone access",
            "The sound meter listens through the microphone to measure how loud it is. Audio is analysed live and never recorded or sent anywhere.",
            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        ) { SoundMeter() }
    }
}

@Composable
private fun SoundMeter() {
    val source = LocalAudioLevelSource.current
    val owner = LocalLifecycleOwner.current
    val c = ForgeTheme.colors
    val stats = remember { RunningStats() }
    val ring = remember { Ring(HISTORY) }
    var version by remember { mutableIntStateOf(0) }
    var db by remember { mutableFloatStateOf(Float.NaN) }
    var paused by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn()
    LaunchedEffect(source, owner, paused) {
        if (paused) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            source.levels().collect { dbfs ->
                val spl = SoundMath.estimateSpl(dbfs)
                db = spl; stats.push(spl); ring.push(spl); version++
            }
        }
    }
    Readout(if (db.isNaN()) "--" else "%.0f".format(java.util.Locale.US, db), "dB (estimate) · " + if (db.isNaN()) "listening…" else SoundMath.describe(db), size = 72, emphasised = true)
    LineGraph(ring.snapshot(), 20f, 120f, capacity = HISTORY, version = version, height = 150.dp)
    if (stats.count > 0) StatRow("Min" to "%.0f".format(java.util.Locale.US, stats.min), "Average" to "%.0f".format(java.util.Locale.US, stats.avg), "Max" to "%.0f".format(java.util.Locale.US, stats.max))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ForgeButton(if (paused) "Resume" else "Pause", onClick = { paused = !paused }, secondary = true, icon = if (paused) ForgeIcons.Refresh else ForgeIcons.Minus, modifier = Modifier.weight(1f))
        ForgeButton("Reset", onClick = { stats.reset(); ring.clear(); version++ }, secondary = true, icon = ForgeIcons.Trash, modifier = Modifier.weight(1f))
    }
    InfoNote("This is an estimate. Phone microphones are not calibrated and filter loud sounds, so expect up to about 10 dB of error. Use it for comparing places, not for legal noise limits.")
    val idx = if (db.isNaN()) -1 else SoundMath.REFERENCES.indexOfLast { it.db <= db }
    ReferenceChart("Typical sounds", SoundMath.REFERENCES.map { "${it.db} dB" to it.label }, highlight = idx)
}
