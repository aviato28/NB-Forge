package com.namanbansal.forge.tools.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.namanbansal.forge.core.device.AndroidTorchController
import com.namanbansal.forge.core.device.LocalTorchController
import com.namanbansal.forge.core.device.TorchPatterns
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** D6: torch with brightness levels (Android 13+), strobe and SOS. The torch goes off whenever Forge leaves the screen. */
@Composable
fun FlashlightScreen(host: ToolHost) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val torch = LocalTorchController.current ?: remember(context) { AndroidTorchController(context) }
    val c = ForgeTheme.colors
    var on by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var hz by rememberSaveable { mutableFloatStateOf(5f) }
    var level by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    val lvl = level.coerceIn(1, torch.maxLevel)

    LaunchedEffect(torch, owner, on, mode, hz, lvl) {
        if (!torch.available) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                if (!on) { torch.set(false); awaitCancellation() }
                when (mode) {
                    0 -> { torch.set(true, lvl); awaitCancellation() }
                    else -> {
                        val pattern = if (mode == 1) TorchPatterns.strobe(hz) else TorchPatterns.sos()
                        while (isActive) for ((lit, ms) in pattern) { torch.set(lit, lvl); delay(ms) }
                    }
                }
            } finally {
                torch.set(false)
            }
        }
    }

    ToolFrame(host) {
        if (!torch.available) {
            SensorMissing("camera flash", icon = ForgeIcons.Torch)
            return@ToolFrame
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(168.dp).background(if (on) c.accent else c.surface2, CircleShape)
                    .border(if (on) 8.dp else 2.dp, if (on) c.tint else c.border, CircleShape)
                    .clickable { on = !on }
                    .semantics { contentDescription = "Torch"; stateDescription = if (on) "On" else "Off" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(ForgeIcons.Torch, null, tint = if (on) Color.White else c.text2, modifier = Modifier.size(64.dp))
            }
        }
        Text(if (on) when (mode) { 0 -> "Torch on"; 1 -> "Strobe running"; else -> "SOS running" } else "Tap to switch on", style = ForgeType.Section, color = c.text, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Segmented(listOf("Steady", "Strobe", "SOS"), mode, { mode = it })
        if (mode == 0 && torch.maxLevel > 1) {
            Column {
                Text("Brightness ${(lvl * 100f / torch.maxLevel).roundToInt()}%", style = ForgeType.Label, color = c.text2)
                Slider(
                    value = lvl.toFloat(), onValueChange = { level = it.roundToInt() }, valueRange = 1f..torch.maxLevel.toFloat(), steps = (torch.maxLevel - 2).coerceIn(0, 8),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
                )
            }
        }
        if (mode == 1) {
            Column {
                Text("Strobe speed %.1f flashes per second".format(java.util.Locale.US, hz), style = ForgeType.Label, color = c.text2)
                Slider(value = hz, onValueChange = { hz = (it * 2).roundToInt() / 2f }, valueRange = 1f..15f, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border))
            }
            InfoNote("Flashing lights can trigger seizures in people with photosensitive epilepsy. Use with care.")
        }
        InfoNote("The torch switches off when you leave this screen. To keep it on, add the Forge torch tile to your Quick Settings panel (pencil icon, then drag Torch).")
    }
}
