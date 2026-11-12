package com.namanbansal.forge.tools.util

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.util.RandomError
import com.namanbansal.forge.core.util.Randomiser
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics

/** U8: random number, coin, dice and list picker. */
@Composable
fun RandomScreen(host: ToolHost) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val rnd = remember { Randomiser() }
    val haptics = rememberHaptics()
    val c = ForgeTheme.colors
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var out by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    fun run(block: () -> Unit) { try { block(); error = null; haptics.tick() } catch (e: RandomError) { error = e.message } }

    ToolFrame(host) {
        Segmented(listOf("Number", "Coin", "Dice", "List"), mode, { mode = it; out = ""; detail = ""; error = null })
        when (mode) {
            0 -> {
                var lo by rememberSaveable { mutableStateOf("1") }; var hi by rememberSaveable { mutableStateOf("100") }
                var count by rememberSaveable { mutableIntStateOf(1) }; var unique by rememberSaveable { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Lowest", lo, { lo = it }, Modifier.weight(1f)); NumberField("Highest", hi, { hi = it }, Modifier.weight(1f)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("How many", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f)); Stepper(count, 1..50, { count = it }, "How many") }
                ToggleRow("No repeats", unique, { unique = it })
                ForgeButton("Generate", onClick = {
                    val a = lo.toLongOrNull(); val b = hi.toLongOrNull()
                    if (a == null || b == null) error = "Use whole numbers" else run { val r = rnd.numbers(a, b, count, unique); out = r.joinToString(", "); detail = if (r.size > 1) "Sum ${r.sum()}" else "" }
                }, icon = ForgeIcons.Dice, modifier = Modifier.fillMaxWidth())
            }
            1 -> {
                var heads by rememberSaveable { mutableIntStateOf(0) }; var tails by rememberSaveable { mutableIntStateOf(0) }
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(140.dp).background(if (out == "Tails") c.surface2 else c.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Text(when (out) { "Heads" -> "H"; "Tails" -> "T"; else -> "?" }, style = ForgeType.Display.copy(fontSize = androidx.compose.ui.unit.TextUnit(56f, androidx.compose.ui.unit.TextUnitType.Sp)), color = if (out == "Tails") c.text else Color.White)
                    }
                }
                ForgeButton("Flip the coin", onClick = { val h = rnd.coin(); out = if (h) "Heads" else "Tails"; if (h) heads++ else tails++; detail = "Heads $heads · Tails $tails"; haptics.tick() }, icon = ForgeIcons.Rotate, modifier = Modifier.fillMaxWidth())
            }
            2 -> {
                var sides by rememberSaveable { mutableIntStateOf(6) }; var count by rememberSaveable { mutableIntStateOf(2) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(4, 6, 8, 10, 12, 20, 100).forEach { s -> OptionChip("d$s", sides == s, { sides = s }) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Dice", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f)); Stepper(count, 1..12, { count = it }, "Dice") }
                ForgeButton("Roll", onClick = { run { val r = rnd.dice(sides, count); out = r.joinToString(" "); detail = "Total ${r.sum()}" } }, icon = ForgeIcons.Dice, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                var items by rememberSaveable { mutableStateOf("Pizza\nSushi\nTacos") }
                MultiLineField(items, { items = it }, "Items, one per line")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeButton("Pick one", onClick = { run { out = rnd.pick(Randomiser.parseItems(items)); detail = "" } }, icon = ForgeIcons.Check, modifier = Modifier.weight(1f))
                    ForgeButton("Shuffle", onClick = { run { out = rnd.shuffle(Randomiser.parseItems(items)).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"); detail = "" } }, secondary = true, icon = ForgeIcons.Swap, modifier = Modifier.weight(1f))
                }
            }
        }
        error?.let { Text(it, style = ForgeType.Body, color = Color(0xFFE5484D)) }
        if (out.isNotEmpty() && error == null && mode != 1) ForgeCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(out, style = if (out.length > 24) ForgeType.Title else ForgeType.Display, color = c.accent, textAlign = TextAlign.Center)
                if (detail.isNotEmpty()) Text(detail, style = ForgeType.Small, color = c.text2)
            }
        } else if (mode == 1 && detail.isNotEmpty()) Text(detail, style = ForgeType.Body, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
