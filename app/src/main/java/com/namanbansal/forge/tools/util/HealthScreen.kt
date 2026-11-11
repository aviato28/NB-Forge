package com.namanbansal.forge.tools.util

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.util.Activity
import com.namanbansal.forge.core.util.Health
import com.namanbansal.forge.core.util.NumberText
import com.namanbansal.forge.core.util.Sex
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame

private fun n0(v: Double) = NumberText.format(Math.round(v).toDouble())

/** U11: BMI, BMR and daily calories in metric or imperial units. */
@Composable
fun HealthScreen(host: ToolHost) {
    val c = ForgeTheme.colors
    var imperial by rememberSaveable { mutableStateOf(false) }
    var female by rememberSaveable { mutableStateOf(false) }
    var age by rememberSaveable { mutableStateOf("30") }
    var cm by rememberSaveable { mutableStateOf("175") }
    var ft by rememberSaveable { mutableStateOf("5") }
    var inch by rememberSaveable { mutableStateOf("9") }
    var weight by rememberSaveable { mutableStateOf("70") }
    var activity by rememberSaveable { mutableIntStateOf(1) }
    val heightCm = if (imperial) { val f = NumberText.parse(ft); val i = NumberText.parse(inch) ?: 0.0; if (f != null) Health.feetInchesToCm(f, i) else null } else NumberText.parse(cm)
    val w = NumberText.parse(weight)?.let { if (imperial) Health.lbToKg(it) else it }
    val a = age.toIntOrNull()
    val ok = heightCm != null && heightCm in 50.0..272.0 && w != null && w in 2.0..650.0 && a != null && a in 5..120

    ToolFrame(host) {
        Segmented(listOf("Metric", "Imperial"), if (imperial) 1 else 0, { imperial = it == 1 })
        Segmented(listOf("Male", "Female"), if (female) 1 else 0, { female = it == 1 })
        NumberField("Age (years)", age, { age = it.filter { ch -> ch.isDigit() } }, error = if (age.isNotBlank() && a !in 5..120) "5 to 120" else null)
        if (imperial) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumberField("Height (ft)", ft, { ft = it }, Modifier.weight(1f)); NumberField("(in)", inch, { inch = it }, Modifier.weight(1f)) }
        else NumberField("Height (cm)", cm, { cm = it })
        NumberField(if (imperial) "Weight (lb)" else "Weight (kg)", weight, { weight = it })
        Segmented(Activity.entries.map { it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }.take(5) }, activity, { activity = it })
        Text(Activity.entries[activity].label, style = ForgeType.Small, color = c.text2)
        if (!ok) { Text("Enter a valid age, height and weight.", style = ForgeType.Body, color = c.text2); return@ToolFrame }
        val bmi = Health.bmi(w!!, heightCm!!)
        val bmr = Health.bmr(if (female) Sex.FEMALE else Sex.MALE, w, heightCm, a!!)
        val tdee = Health.tdee(bmr, Activity.entries[activity])
        val (lo, hi) = Health.healthyRangeKg(heightCm)
        SummaryCard {
            ValueRow("BMI", "%.1f".format(java.util.Locale.US, bmi), strong = true)
            ValueRow("Category", Health.bmiCategory(bmi))
            BmiBar(bmi, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            ValueRow("Healthy weight range", if (imperial) "${n0(Health.kgToLb(lo))} to ${n0(Health.kgToLb(hi))} lb" else "${n0(lo)} to ${n0(hi)} kg")
        }
        SummaryCard {
            ValueRow("BMR (resting)", "${n0(bmr)} kcal/day", strong = true)
            ValueRow("Maintain weight", "${n0(tdee)} kcal/day")
            ValueRow("Lose about 0.5 kg / 1 lb a week", "${n0((tdee - 500).coerceAtLeast(bmr * 0.8))} kcal/day")
            ValueRow("Gain about 0.5 kg / 1 lb a week", "${n0(tdee + 500)} kcal/day")
        }
        InfoNote("These are general estimates (Mifflin-St Jeor). BMI ignores muscle and age. This is not medical advice.")
    }
}

@Composable
private fun BmiBar(bmi: Double, modifier: Modifier) {
    val c = ForgeTheme.colors
    Canvas(modifier.height(16.dp)) {
        val stops = listOf(15.0 to 18.5 to Color(0xFF4F9DDE), 18.5 to 25.0 to Color(0xFF2FB36A), 25.0 to 30.0 to Color(0xFFF2A93B), 30.0 to 40.0 to Color(0xFFE5484D))
        fun x(v: Double) = ((v.coerceIn(15.0, 40.0) - 15.0) / 25.0 * size.width).toFloat()
        stops.forEach { (range, col) -> drawRect(col, Offset(x(range.first), 4f), androidx.compose.ui.geometry.Size(x(range.second) - x(range.first), size.height - 8f)) }
        drawLine(c.text, Offset(x(bmi), 0f), Offset(x(bmi), size.height), 5f)
    }
}
