package com.namanbansal.forge.tools.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.util.NumberText
import com.namanbansal.forge.core.util.PercentMath
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame

private fun money(v: Double): String = "%,.2f".format(java.util.Locale.US, v)

/** U4: percentages, tips and bill splitting. */
@Composable
fun PercentScreen(host: ToolHost) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    ToolFrame(host) {
        Segmented(listOf("Percentages", "Tip & split"), mode, { mode = it })
        if (mode == 0) PercentCards() else TipCard()
    }
}

@Composable
private fun ResultText(text: String) {
    Text(text, style = ForgeType.Title, color = ForgeTheme.colors.accent, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun PercentCards() {
    var a1 by rememberSaveable { mutableStateOf("15") }; var b1 by rememberSaveable { mutableStateOf("240") }
    var a2 by rememberSaveable { mutableStateOf("30") }; var b2 by rememberSaveable { mutableStateOf("120") }
    var a3 by rememberSaveable { mutableStateOf("80") }; var b3 by rememberSaveable { mutableStateOf("100") }
    val c = ForgeTheme.colors
    ForgeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What is X% of Y?", style = ForgeType.Section, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("X %", a1, { a1 = it }, Modifier.weight(1f)); NumberField("Y", b1, { b1 = it }, Modifier.weight(1f)) }
            val x = NumberText.parse(a1); val y = NumberText.parse(b1)
            ResultText(if (x != null && y != null) "$a1% of $b1 = ${NumberText.format(PercentMath.percentOf(x, y))}" else "Enter both numbers")
        }
    }
    ForgeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("X is what % of Y?", style = ForgeType.Section, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("X", a2, { a2 = it }, Modifier.weight(1f)); NumberField("Y", b2, { b2 = it }, Modifier.weight(1f)) }
            val x = NumberText.parse(a2); val y = NumberText.parse(b2)
            val r = if (x != null && y != null) PercentMath.whatPercent(x, y) else null
            ResultText(if (r != null) "${NumberText.format(r, 8)}%" else if (x != null && y == 0.0) "Y can't be zero" else "Enter both numbers")
        }
    }
    ForgeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Percent change", style = ForgeType.Section, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("From", a3, { a3 = it }, Modifier.weight(1f)); NumberField("To", b3, { b3 = it }, Modifier.weight(1f)) }
            val x = NumberText.parse(a3); val y = NumberText.parse(b3)
            val r = if (x != null && y != null) PercentMath.change(x, y) else null
            ResultText(if (r != null) (if (r >= 0) "+" else "") + NumberText.format(r, 8) + "%" else if (x == 0.0) "Starting value can't be zero" else "Enter both numbers")
            if (x != null && y != null) Text("Adding 10% to $a3 gives ${NumberText.format(PercentMath.plusPercent(x, 10.0))}; taking 10% off gives ${NumberText.format(PercentMath.minusPercent(x, 10.0))}.", style = ForgeType.Small, color = c.text2)
        }
    }
}

@Composable
private fun TipCard() {
    val c = ForgeTheme.colors
    var bill by rememberSaveable { mutableStateOf("85.50") }
    var tipPct by rememberSaveable { mutableStateOf("15") }
    var people by rememberSaveable { mutableIntStateOf(2) }
    var roundUp by rememberSaveable { mutableStateOf(false) }
    val b = NumberText.parse(bill); val t = NumberText.parse(tipPct)
    ForgeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Bill", bill, { bill = it })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("0", "10", "15", "18", "20", "25").forEach { p -> OptionChip("$p%", tipPct == p, { tipPct = p }) }
            }
            NumberField("Tip %", tipPct, { tipPct = it })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("People", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Stepper(people, 1..50, { people = it }, "People")
            }
        }
    }
    ToggleRow("Round each share up", roundUp, { roundUp = it }, subtitle = "The extra goes to the tip")
    if (b != null && t != null) {
        val r = PercentMath.tip(b, t, people, roundUp)
        SummaryCard {
            ValueRow("Tip", money(r.tip)); ValueRow("Total", money(r.total))
            ValueRow("Each person pays", money(r.perPerson), strong = true)
            if (people > 1) ValueRow("Tip per person", money(r.tipPerPerson))
        }
    } else Text("Enter the bill and tip to see the split.", style = ForgeType.Small, color = c.text2)
}
