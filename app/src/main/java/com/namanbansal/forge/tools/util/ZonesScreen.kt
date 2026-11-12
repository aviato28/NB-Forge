package com.namanbansal.forge.tools.util

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.namanbansal.forge.core.device.rememberToolPrefs
import com.namanbansal.forge.core.util.City
import com.namanbansal.forge.core.util.HourKind
import com.namanbansal.forge.core.util.Zones
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

private fun deviceZone(): String = ZoneId.systemDefault().id

/** U6: world clock with saved cities, a time converter and a meeting-planner strip. */
@Composable
fun ZonesScreen(host: ToolHost, nowProvider: () -> Instant = { Instant.now() }) {
    val prefs = rememberToolPrefs()
    val owner = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var zones by remember {
        mutableStateOf(prefs.string("tz.zones", "").split(',').filter { it.isNotBlank() && runCatching { ZoneId.of(it) }.isSuccess }
            .ifEmpty { listOf(deviceZone(), "Europe/London", "America/New_York", "Asia/Tokyo").distinct() })
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(nowProvider()) }
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { now = nowProvider(); kotlinx.coroutines.delay(1000) } } }
    fun save(list: List<String>) { zones = list; prefs.putString("tz.zones", list.joinToString(",")) }
    val home = ZoneId.of(zones.firstOrNull() ?: deviceZone())

    ToolFrame(host, actions = {
        RoundIconButton(ForgeIcons.Plus, "Add a city", { adding = true })
        Box(Modifier.padding(end = 8.dp))
    }) {
        Segmented(listOf("World clock", "Convert", "Planner"), tab, { tab = it })
        when (tab) {
            0 -> Clock(zones, home, now, onRemove = { z -> if (zones.size > 1) save(zones - z) }, onHome = { z -> save(listOf(z) + (zones - z)) })
            1 -> Convert(zones, home)
            else -> Planner(zones, home)
        }
    }
    if (adding) {
        PickerDialog("Add a city", Zones.cities.filter { it.zone !in zones }, { it.name to it.country + " · " + it.zone }, { c: City -> save(zones + c.zone); adding = false }, { adding = false })
    }
}

private fun label(zone: String): Pair<String, String> {
    val c = Zones.find(zone)
    return (c?.name ?: zone.substringAfterLast('/').replace('_', ' ')) to (c?.country ?: zone)
}

@Composable
private fun Clock(zones: List<String>, home: ZoneId, now: Instant, onRemove: (String) -> Unit, onHome: (String) -> Unit) {
    val c = ForgeTheme.colors
    zones.forEachIndexed { i, z ->
        val zone = ZoneId.of(z)
        val t = now.atZone(zone)
        val (name, country) = label(z)
        val diff = Zones.hoursAhead(zone, home, now)
        ForgeCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = ForgeType.Section, color = c.text)
                    Text(
                        "$country · ${Zones.offsetLabel(zone, now)}" + if (i == 0) " · home" else " · " + (if (diff == 0.0) "same time" else "%s h %s".format(compactHours(kotlin.math.abs(diff)), if (diff > 0) "ahead" else "behind")),
                        style = ForgeType.Small, color = c.text2,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(HM.format(t), style = ForgeType.Title, color = c.text)
                    Text(DAY.format(t), style = ForgeType.Small, color = c.text2)
                }
                if (zones.size > 1) RoundIconButton(ForgeIcons.Close, "Remove $name", { onRemove(z) })
            }
        }
    }
    if (zones.size > 1) Text("Times are compared with ${label(zones.first()).first}, your first city. Remove a city with the x.", style = ForgeType.Small, color = c.text2)
}

private fun compactHours(h: Double) = if (h == Math.floor(h)) h.toInt().toString() else "%.1f".format(java.util.Locale.US, h)

@Composable
private fun Convert(zones: List<String>, home: ZoneId) {
    val c = ForgeTheme.colors
    var source by rememberSaveable { mutableStateOf(home.id) }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now(home).toEpochDay()) }
    var hour by rememberSaveable { mutableIntStateOf(LocalTime.now(home).hour) }
    var minute by rememberSaveable { mutableIntStateOf(0) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val all = (zones + source).distinct()
    ChoiceButton("Time is in", label(source).first, { picking = true }, Modifier.fillMaxWidth())
    DateField("Date", LocalDate.ofEpochDay(epochDay), { epochDay = it.toEpochDay() }, Modifier.fillMaxWidth())
    ForgeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Hour", style = ForgeType.Small, color = c.text2); Stepper(hour, 0..23, { hour = it }, "Hour") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Minute", style = ForgeType.Small, color = c.text2); Stepper(minute, 0..59, { minute = it }, "Minute") }
        }
    }
    val local = LocalDateTime.of(LocalDate.ofEpochDay(epochDay), LocalTime.of(hour, minute))
    val src = ZoneId.of(source)
    val srcZoned = Zones.convert(local, src, src)
    ForgeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            zones.forEach { z ->
                val t = Zones.convert(local, src, ZoneId.of(z))
                val shift = java.time.temporal.ChronoUnit.DAYS.between(srcZoned.toLocalDate(), t.toLocalDate())
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(label(z).first, style = ForgeType.BodyMedium, color = c.text); Text(Zones.offsetLabel(ZoneId.of(z), t.toInstant()), style = ForgeType.Small, color = c.text2) }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(HM.format(t), style = ForgeType.Section, color = if (z == source) c.accent else c.text)
                        Text(DAY.format(t) + if (shift > 0) " (+$shift)" else if (shift < 0) " ($shift)" else "", style = ForgeType.Small, color = c.text2)
                    }
                }
            }
        }
    }
    if (picking) PickerDialog("Time is in", all, { label(it) }, { z: String -> source = z; picking = false }, { picking = false }, search = false, selected = source)
}

@Composable
private fun Planner(zones: List<String>, home: ZoneId) {
    val c = ForgeTheme.colors
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now(home).toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    DateField("Day in ${label(home.id).first}", date, { epochDay = it.toEpochDay() }, Modifier.fillMaxWidth())
    val common = Zones.commonWorkingHours(date, home, zones.map { ZoneId.of(it) })
    InfoNote(
        if (common.isEmpty()) "No hour is inside working time (9 to 17) for every city. Try an early or late slot for one of them."
        else "Everyone is at work " + ranges(common) + " ${label(home.id).first} time.",
    )
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(92.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.height(20.dp))
            zones.forEach { z -> Box(Modifier.height(44.dp), contentAlignment = Alignment.CenterStart) { Text(label(z).first, style = ForgeType.Item, color = c.text, maxLines = 1) } }
        }
        Column(Modifier.weight(1f).horizontalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row { (0 until 24).forEach { h -> Box(Modifier.width(34.dp).height(20.dp), contentAlignment = Alignment.Center) { Text("%02d".format(h), style = ForgeType.Tiny, color = c.text2) } } }
            zones.forEach { z ->
                val cells = Zones.planner(date, home, ZoneId.of(z))
                Row {
                    cells.forEachIndexed { h, cell ->
                        val common1 = h in common
                        Box(
                            Modifier.width(34.dp).height(44.dp).padding(horizontal = 1.dp)
                                .background(when (cell.kind) { HourKind.WORK -> c.accent; HourKind.AWAKE -> c.tint; HourKind.NIGHT -> c.surface2 }, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("%02d".format(cell.localHour), style = ForgeType.Tiny, color = when (cell.kind) { HourKind.WORK -> androidx.compose.ui.graphics.Color.White; HourKind.AWAKE -> c.text; HourKind.NIGHT -> c.text2 })
                                if (cell.dayShift != 0) Text(if (cell.dayShift > 0) "+${cell.dayShift}d" else "${cell.dayShift}d", style = ForgeType.Tiny.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text2)
                                else if (common1) Box(Modifier.size(4.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
            }
        }
    }
    Text("Orange = working hours (9 to 17), tinted = awake, grey = night. Numbers are each city's local hour; the top row is ${label(home.id).first} time.", style = ForgeType.Small, color = c.text2)
}

private fun ranges(hours: List<Int>): String {
    val out = mutableListOf<String>(); var start = hours.first(); var prev = start
    for (h in hours.drop(1) + (hours.last() + 100)) { if (h != prev + 1) { out += "%02d:00 to %02d:00".format(start, prev + 1); start = h }; prev = h }
    return out.joinToString(", ")
}
