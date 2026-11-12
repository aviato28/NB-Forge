package com.namanbansal.forge.tools.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.util.DateMath
import com.namanbansal.forge.core.util.NumberText
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val FULL = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private fun plural(n: Number, one: String, many: String = one + "s") = "$n " + if (n.toLong() == 1L) one else many

/** A date that survives rotation (stored as epoch days). */
private class DateState(private val epoch: androidx.compose.runtime.MutableState<Long>) {
    var value: LocalDate
        get() = LocalDate.ofEpochDay(epoch.value)
        set(v) { epoch.value = v.toEpochDay() }
}

@Composable
private fun rememberDate(initial: LocalDate): DateState {
    val epoch = rememberSaveable { mutableStateOf(initial.toEpochDay()) }
    return androidx.compose.runtime.remember(epoch) { DateState(epoch) }
}

/** U5: difference between dates, add / subtract and age. */
@Composable
fun DateScreen(host: ToolHost) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    ToolFrame(host) {
        Segmented(listOf("Between", "Add / subtract", "Age"), mode, { mode = it })
        when (mode) { 0 -> Between(); 1 -> AddSub(); else -> Age() }
    }
}

@Composable
private fun Between() {
    val today = LocalDate.now()
    val a = rememberDate(today); val b = rememberDate(today.plusDays(30))
    var includeEnd by rememberSaveable { mutableStateOf(false) }
    DateField("Start date", a.value, { a.value = it }, Modifier.fillMaxWidth())
    DateField("End date", b.value, { b.value = it }, Modifier.fillMaxWidth())
    ToggleRow("Count the end date too", includeEnd, { includeEnd = it })
    val d = DateMath.diff(a.value, b.value, includeEnd)
    SummaryCard {
        ValueRow("Difference", buildList {
            if (d.years > 0) add(plural(d.years, "year")); if (d.months > 0) add(plural(d.months, "month")); if (d.days > 0 || (d.years == 0 && d.months == 0)) add(plural(d.days, "day"))
        }.joinToString(", "), strong = true)
        ValueRow("In days", NumberText.format(d.totalDays.toDouble()) + if (d.reversed) " (start is after end)" else "")
        ValueRow("In weeks", "${d.weeks} w ${d.extraDays} d")
        ValueRow("Working days (Mon to Fri)", NumberText.format(d.workingDays.toDouble()))
    }
}

@Composable
private fun AddSub() {
    val start = rememberDate(LocalDate.now())
    var sub by rememberSaveable { mutableStateOf(false) }
    var years by rememberSaveable { mutableStateOf("0") }; var months by rememberSaveable { mutableStateOf("0") }; var days by rememberSaveable { mutableStateOf("30") }
    var working by rememberSaveable { mutableStateOf(false) }
    DateField("Start date", start.value, { start.value = it }, Modifier.fillMaxWidth())
    Segmented(listOf("Add", "Subtract"), if (sub) 1 else 0, { sub = it == 1 })
    ToggleRow("Working days only", working, { working = it }, subtitle = "Skips Saturdays and Sundays; only the days field is used")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!working) { NumberField("Years", years, { years = it }, Modifier.weight(1f)); NumberField("Months", months, { months = it }, Modifier.weight(1f)) }
        NumberField(if (working) "Working days" else "Days", days, { days = it }, Modifier.weight(1f))
    }
    val y = years.toIntOrNull(); val m = months.toIntOrNull(); val dd = days.toIntOrNull()
    if ((working || (y != null && m != null)) && dd != null && kotlin.math.abs(dd) <= 100_000 && (working || (y!! in 0..10_000 && m!! in 0..120_000))) {
        val out = if (working) DateMath.addWorkingDays(start.value, if (sub) -dd else dd) else DateMath.add(start.value, y!!, m!!, dd, sub)
        SummaryCard { ValueRow("Result", FULL.format(out), strong = true) }
    } else Text("Enter whole numbers.", style = ForgeType.Small, color = ForgeTheme.colors.text2)
}

@Composable
private fun Age() {
    val birth = rememberDate(LocalDate.now().minusYears(30))
    val on = rememberDate(LocalDate.now())
    DateField("Date of birth", birth.value, { birth.value = it }, Modifier.fillMaxWidth())
    DateField("Age on", on.value, { on.value = it }, Modifier.fillMaxWidth())
    if (birth.value.isAfter(on.value)) { Text("The birth date is after that day.", style = ForgeType.Body, color = ForgeTheme.colors.text2); return }
    val a = DateMath.age(birth.value, on.value)
    SummaryCard {
        ValueRow("Age", "${plural(a.years, "year")}, ${plural(a.months, "month")}, ${plural(a.days, "day")}", strong = true)
        ValueRow("Total days lived", NumberText.format(a.totalDays.toDouble()))
        ValueRow("Next birthday", FULL.format(a.nextBirthday))
        ValueRow("Days to go", if (a.daysToBirthday == 0L) "Today!" else NumberText.format(a.daysToBirthday.toDouble()))
    }
}
