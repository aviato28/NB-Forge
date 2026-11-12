package com.namanbansal.forge.tools.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.core.device.ScanActions
import com.namanbansal.forge.core.device.rememberToolPrefs
import com.namanbansal.forge.core.util.Calc
import com.namanbansal.forge.core.util.CalcError
import com.namanbansal.forge.core.util.CalcKeys
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics
import com.namanbansal.forge.ui.toolkit.rememberSnack
import androidx.compose.ui.platform.LocalContext

private enum class KeyStyle { Digit, Op, Accent, Func }

/** U3: scientific calculator with a proper parser, deg/rad, live preview, history and copy. */
@Composable
fun CalculatorScreen(host: ToolHost) {
    val c = ForgeTheme.colors
    val prefs = rememberToolPrefs()
    val context = LocalContext.current
    val snack = rememberSnack()
    val haptics = rememberHaptics()
    var expr by rememberSaveable { mutableStateOf("") }
    var degrees by rememberSaveable { mutableStateOf(prefs.bool("calc.deg", true)) }
    var sci by rememberSaveable { mutableStateOf(prefs.bool("calc.sci", false)) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(Calc.History.decode(prefs.string("calc.history", ""))) }
    var justEvaluated by rememberSaveable { mutableStateOf(false) }

    val preview = Calc.tryEvaluate(expr, degrees)?.let { Calc.format(it) }
    val showPreview = preview != null && preview != expr && expr.any { !it.isDigit() && it != '.' }

    fun edit(f: (String) -> String) {
        // Typing a digit right after "=" starts a new calculation; an operator continues from the result.
        error = null; expr = f(expr); justEvaluated = false
    }
    fun equals() {
        if (expr.isBlank()) return
        try {
            val v = Calc.evaluate(expr, degrees)
            val shown = Calc.format(v)
            history = Calc.History.add(history, expr, shown); prefs.putString("calc.history", Calc.History.encode(history))
            expr = CalcKeys.toExpression(v); justEvaluated = true; error = null
            haptics.tick()
        } catch (e: CalcError) { error = e.message }
    }
    fun digit(d: Char) { if (justEvaluated) { expr = ""; justEvaluated = false }; edit { CalcKeys.digit(it, d) } }

    ToolFrame(host, scroll = false, padding = false, actions = {
        RoundIconButton(ForgeIcons.Clock, if (showHistory) "Hide history" else "Show history", { showHistory = !showHistory }, active = showHistory)
        Box(Modifier.padding(end = 8.dp))
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
            if (showHistory) {
                if (history.isEmpty()) Text("Your calculations will appear here.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(vertical = 12.dp))
                else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextAction("Clear history", { history = emptyList(); prefs.putString("calc.history", "") }, danger = true) }
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), reverseLayout = false) {
                        items(history) { (e, r) ->
                            Column(Modifier.fillMaxWidth().clickable { expr = e; showHistory = false; justEvaluated = false }.padding(vertical = 6.dp), horizontalAlignment = Alignment.End) {
                                Text(e, style = ForgeType.Small, color = c.text2)
                                Text("= $r", style = ForgeType.Item, color = c.text)
                            }
                        }
                    }
                }
            }
            Text(
                if (expr.isEmpty()) "0" else expr, style = ForgeType.Display.copy(fontSize = if (expr.length > 14) 28.sp else 40.sp, lineHeight = 46.sp), color = if (expr.isEmpty()) c.text2 else c.text,
                textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Expression ${expr.ifEmpty { "empty" }}" },
            )
            Text(
                error ?: if (showPreview) "= $preview" else " ", style = ForgeType.Title, color = if (error != null) Color(0xFFE5484D) else c.accent, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(enabled = showPreview) {
                    ScanActions.copy(context, "Result", preview.orEmpty()); snack("Result copied")
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.namanbansal.forge.ui.components.OptionChip(if (degrees) "Deg" else "Rad", true, { degrees = !degrees; prefs.putBool("calc.deg", degrees) })
            com.namanbansal.forge.ui.components.OptionChip("Scientific", sci, { sci = !sci; prefs.putBool("calc.sci", sci) })
            Box(Modifier.weight(1f))
            TextAction("Copy", { if (preview != null || expr.isNotEmpty()) { ScanActions.copy(context, "Calculator", preview ?: expr); snack("Copied") } }, icon = ForgeIcons.Copy, enabled = expr.isNotEmpty())
        }
        if (sci) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rows = listOf(
                    listOf("sin" to { edit { CalcKeys.function(it, "sin(") } }, "cos" to { edit { CalcKeys.function(it, "cos(") } }, "tan" to { edit { CalcKeys.function(it, "tan(") } }, "ln" to { edit { CalcKeys.function(it, "ln(") } }, "log" to { edit { CalcKeys.function(it, "log(") } }),
                    listOf("asin" to { edit { CalcKeys.function(it, "asin(") } }, "acos" to { edit { CalcKeys.function(it, "acos(") } }, "atan" to { edit { CalcKeys.function(it, "atan(") } }, "√" to { edit { CalcKeys.function(it, "√(") } }, "x²" to { edit { it + "^2" } }),
                    listOf("π" to { edit { it + "π" } }, "e" to { edit { it + "e" } }, "xʸ" to { edit { CalcKeys.operator(it, '^') } }, "n!" to { edit { CalcKeys.percentOrFactorial(it, '!') } }, "abs" to { edit { CalcKeys.function(it, "abs(") } }),
                )
                rows.forEach { r -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.forEach { (label, act) -> Key(label, KeyStyle.Func, Modifier.weight(1f), 40) { act() } } } }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val h = if (sci) 52 else 60
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("C", KeyStyle.Op, Modifier.weight(1f), h, "Clear") { expr = ""; error = null; justEvaluated = false }
                Key("( )", KeyStyle.Op, Modifier.weight(1f), h, "Brackets") { edit { CalcKeys.bracket(it) } }
                Key("%", KeyStyle.Op, Modifier.weight(1f), h, "Percent") { edit { CalcKeys.percentOrFactorial(it, '%') } }
                Key("÷", KeyStyle.Accent, Modifier.weight(1f), h, "Divide") { edit { CalcKeys.operator(it, '÷') } }
            }
            listOf(listOf('7', '8', '9') to ('×' to "Multiply"), listOf('4', '5', '6') to ('−' to "Minus"), listOf('1', '2', '3') to ('+' to "Plus")).forEach { (digits, op) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    digits.forEach { d -> Key(d.toString(), KeyStyle.Digit, Modifier.weight(1f), h) { digit(d) } }
                    Key(op.first.toString(), KeyStyle.Accent, Modifier.weight(1f), h, op.second) { edit { CalcKeys.operator(it, op.first) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("0", KeyStyle.Digit, Modifier.weight(1f), h) { digit('0') }
                Key(".", KeyStyle.Digit, Modifier.weight(1f), h, "Decimal point") { edit { CalcKeys.decimal(it) } }
                Key("⌫", KeyStyle.Op, Modifier.weight(1f), h, "Delete") { edit { CalcKeys.backspace(it) } }
                Key("=", KeyStyle.Accent, Modifier.weight(1f), h, "Equals", filled = true) { equals() }
            }
        }
    }
}

@Composable
private fun Key(label: String, style: KeyStyle, modifier: Modifier, height: Int, description: String? = null, filled: Boolean = false, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    val bg = when { filled -> c.accent; style == KeyStyle.Digit -> c.surface; style == KeyStyle.Func -> c.surface2; style == KeyStyle.Accent -> c.tint; else -> c.surface2 }
    val fg = when { filled -> Color.White; style == KeyStyle.Accent -> c.accent; else -> c.text }
    Box(
        modifier.height(height.dp).background(bg, if (style == KeyStyle.Func) RoundedCornerShape(12.dp) else ForgeShapes.Button).clickable(onClick = onClick)
            .semantics { contentDescription = description ?: label },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = ForgeType.Title.copy(fontSize = if (style == KeyStyle.Func) 15.sp else 22.sp), color = fg) }
}
