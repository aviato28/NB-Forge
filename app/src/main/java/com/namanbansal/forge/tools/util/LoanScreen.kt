package com.namanbansal.forge.tools.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.util.EmiMath
import com.namanbansal.forge.core.util.NumberText
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun money(v: Double) = "%,.2f".format(java.util.Locale.US, v)

/** U10: loan / EMI calculator with a full amortisation table and CSV export through the share sheet. */
@Composable
fun LoanScreen(host: ToolHost) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = rememberSnack()
    var amount by rememberSaveable { mutableStateOf("250000") }
    var rate by rememberSaveable { mutableStateOf("7.5") }
    var term by rememberSaveable { mutableStateOf("20") }
    var years by rememberSaveable { mutableStateOf(true) }
    val p = NumberText.parse(amount); val r = NumberText.parse(rate); val t = NumberText.parse(term)
    val months = t?.let { (if (years) it * 12 else it).toInt() }
    val valid = p != null && p > 0 && r != null && r in 0.0..100.0 && months != null && months in 1..1200
    val result = remember(p, r, months, valid) { if (valid) EmiMath.schedule(p!!, r!!, months!!) else null }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        TopBar(host.tool.name, onBack = host.onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Loan amount", amount, { amount = it }, error = if (amount.isNotBlank() && (p == null || p <= 0)) "Enter an amount above 0" else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Interest % per year", rate, { rate = it }, Modifier.weight(1f), error = if (rate.isNotBlank() && (r == null || r !in 0.0..100.0)) "0 to 100" else null)
                        NumberField(if (years) "Term (years)" else "Term (months)", term, { term = it }, Modifier.weight(1f), error = if (term.isNotBlank() && !valid && p != null && r != null) "1 to 100 years" else null)
                    }
                    Segmented(listOf("Years", "Months"), if (years) 0 else 1, { years = it == 0 })
                }
            }
            if (result != null) {
                item {
                    SummaryCard {
                        ValueRow("Monthly payment", money(result.emi), strong = true)
                        ValueRow("Total interest", money(result.totalInterest))
                        ValueRow("Total paid", money(result.totalPaid))
                        ValueRow("Number of payments", result.rows.size.toString())
                    }
                }
                item {
                    ForgeButton("Export schedule as CSV", onClick = {
                        scope.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) {
                                    val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
                                    val f = File(dir, "loan_schedule.csv"); f.writeText(EmiMath.toCsv(result))
                                    FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                                }
                                if (!FileActions.share(context, uri, "text/csv", "loan_schedule.csv")) snack("No app can receive the file")
                            } catch (e: Exception) { snack("Could not export: ${e.message ?: "unknown error"}") }
                        }
                    }, icon = ForgeIcons.Share, modifier = Modifier.fillMaxWidth())
                }
                item { Row(Modifier.fillMaxWidth().padding(top = 6.dp)) { listOf("#", "Interest", "Principal", "Balance").forEachIndexed { i, h -> Text(h, style = ForgeType.Label, color = c.text2, modifier = Modifier.weight(if (i == 0) 0.5f else 1f), textAlign = if (i == 0) TextAlign.Start else TextAlign.End) } } }
                itemsIndexed(result.rows, key = { _, row -> row.month }) { i, row ->
                    Row(Modifier.fillMaxWidth().background(if (i % 2 == 0) c.surface else c.bg).padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Text(row.month.toString(), style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(0.5f))
                        listOf(row.interest, row.principal, row.balance).forEach { Text(money(it), style = ForgeType.Small, color = c.text, modifier = Modifier.weight(1f), textAlign = TextAlign.End) }
                    }
                }
            } else item { Text("Enter the amount, rate and term to see the payments.", style = ForgeType.Body, color = c.text2) }
        }
    }
}
