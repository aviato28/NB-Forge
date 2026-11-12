package com.namanbansal.forge.tools.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.device.ScanActions
import com.namanbansal.forge.core.device.rememberToolPrefs
import com.namanbansal.forge.core.util.NumberText
import com.namanbansal.forge.core.util.UnitCategory
import com.namanbansal.forge.core.util.UnitDef
import com.namanbansal.forge.core.util.Units
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.SectionHeader
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberSnack
import androidx.compose.ui.platform.LocalContext

private const val FAV_KEY = "units.favs"

/** U1: unit converter with many categories, swap, favourite conversions and a precise formatter. */
@Composable
fun UnitsScreen(host: ToolHost) {
    val c = ForgeTheme.colors
    val prefs = rememberToolPrefs()
    val context = LocalContext.current
    val snack = rememberSnack()
    var catId by rememberSaveable { mutableStateOf(prefs.string("units.cat", Units.length.id)) }
    val cat = Units.category(catId)
    var fromId by rememberSaveable(catId) { mutableStateOf(prefs.string("units.from.$catId", cat.defaultFrom)) }
    var toId by rememberSaveable(catId) { mutableStateOf(prefs.string("units.to.$catId", cat.defaultTo)) }
    var input by rememberSaveable { mutableStateOf("1") }
    var favs by remember { mutableStateOf(prefs.stringSet(FAV_KEY)) }
    var picking by rememberSaveable { mutableStateOf(0) } // 0 none, 1 from, 2 to
    val from = cat.unit(fromId); val to = cat.unit(toId)
    val value = NumberText.parse(input)
    val result = value?.let { cat.convert(it, from, to) }
    val key = "${cat.id}|${from.id}|${to.id}"

    fun setUnits(f: String, t: String) { fromId = f; toId = t; prefs.putString("units.from.$catId", f); prefs.putString("units.to.$catId", t) }

    ToolFrame(host) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Units.categories.forEach { k -> OptionChip(k.name, k.id == catId, { catId = k.id; prefs.putString("units.cat", k.id) }) }
        }
        if (favs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Favourites", style = ForgeType.Small, color = c.text2)
                favs.sorted().forEach { f ->
                    val (kc, a, b) = f.split("|").let { Triple(it[0], it[1], it[2]) }
                    val k = Units.category(kc)
                    OptionChip("${k.unit(a).symbol} → ${k.unit(b).symbol}", f == key, { catId = kc; prefs.putString("units.cat", kc); fromId = a; toId = b; prefs.putString("units.from.$kc", a); prefs.putString("units.to.$kc", b) })
                }
            }
        }
        ForgeCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceButton("From", "${from.name} (${from.symbol})", { picking = 1 }, Modifier.fillMaxWidth())
                NumberField("Value", input, { input = it }, error = if (input.isNotBlank() && value == null) "Enter a number" else null)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(ForgeIcons.Swap, "Swap units", { setUnits(toId, fromId); result?.let { input = NumberText.format(it, group = false).takeIf { s -> !s.contains('e') && s != "—" } ?: input } })
            Box(Modifier.padding(horizontal = 6.dp))
            RoundIconButton(ForgeIcons.Bookmark, if (key in favs) "Remove from favourites" else "Save as favourite", {
                favs = if (key in favs) favs - key else favs + key; prefs.putStringSet(FAV_KEY, favs)
            }, active = key in favs)
        }
        ForgeCard(Modifier.fillMaxWidth(), onClick = result?.let { r -> { ScanActions.copy(context, "Result", NumberText.format(r, group = false)); snack("Result copied") } }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceButton("To", "${to.name} (${to.symbol})", { picking = 2 }, Modifier.fillMaxWidth())
                Text(result?.let { NumberText.format(it) } ?: "—", style = ForgeType.Display, color = c.accent, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                Text(if (result != null) "${to.symbol} · tap to copy" else "Enter a value above", style = ForgeType.Small, color = c.text2)
            }
        }
        SectionHeader("All ${cat.name.lowercase()} units")
        ForgeCard(Modifier.fillMaxWidth()) {
            cat.units.forEach { u ->
                Row(Modifier.fillMaxWidth().clickable { setUnits(fromId, u.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name, style = ForgeType.Item, color = if (u.id == toId) c.accent else c.text)
                        Text(u.symbol, style = ForgeType.Small, color = c.text2)
                    }
                    Text(value?.let { NumberText.format(cat.convert(it, from, u)) } ?: "—", style = ForgeType.BodyMedium, color = c.text)
                }
            }
        }
    }
    if (picking != 0) {
        PickerDialog(
            if (picking == 1) "Convert from" else "Convert to", cat.units, { it.name to it.symbol },
            onPick = { u: UnitDef -> if (picking == 1) setUnits(u.id, toId) else setUnits(fromId, u.id); picking = 0 },
            onDismiss = { picking = 0 }, search = cat.units.size > 8, selected = if (picking == 1) from else to,
        )
    }
}
