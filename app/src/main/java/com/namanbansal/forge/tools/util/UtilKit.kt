package com.namanbansal.forge.tools.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Decimal number input in a labelled field. */
@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "0", error: String? = null, helper: String? = null) {
    ForgeTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(24)) }, modifier, placeholder, label, error, helper, KeyboardType.Decimal)
}

/** A card-like button showing a label above the current choice; tapping it opens a picker. */
@Composable
fun ChoiceButton(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = ForgeIcons.ChevronDown) {
    val c = ForgeTheme.colors
    Row(
        modifier.heightIn(min = 56.dp).background(c.surface, ForgeShapes.Button).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = ForgeType.Small, color = c.text2)
            Text(value, style = ForgeType.BodyMedium, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Icon(icon, null, tint = c.text2, modifier = Modifier.padding(2.dp))
    }
}

/** Full-screen-ish list dialog with an optional search box. [label] gives each row's (title, subtitle). */
@Composable
fun <T> PickerDialog(title: String, items: List<T>, label: (T) -> Pair<String, String>, onPick: (T) -> Unit, onDismiss: () -> Unit, search: Boolean = true, selected: T? = null) {
    val c = ForgeTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) items else items.filter { val (a, b) = label(it); a.contains(query.trim(), true) || b.contains(query.trim(), true) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = ForgeType.Section, color = c.text)
            if (search) ForgeTextField(query, { query = it }, placeholder = "Search")
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown) { item ->
                    val (a, b) = label(item)
                    Row(Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a, style = ForgeType.BodyMedium, color = if (item == selected) c.accent else c.text)
                            if (b.isNotEmpty()) Text(b, style = ForgeType.Small, color = c.text2)
                        }
                        if (item == selected) Icon(ForgeIcons.Check, null, tint = c.accent)
                    }
                }
                if (shown.isEmpty()) item { Text("Nothing matches \"$query\"", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(12.dp)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextAction("Cancel", onDismiss) }
        }
    }
}

/** A date shown on a button; tapping it opens the Material date picker. */
@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    ChoiceButton(label, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date), { open = true }, modifier, ForgeIcons.Calendar)
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** "label ........ value" row inside a card. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ForgeType.Body, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) ForgeType.Section else ForgeType.BodyMedium, color = if (strong) c.accent else c.text)
    }
}

@Composable
fun SummaryCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ForgeCard(modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content) }
}

/** Multi-line text input in the Forge style (list of items, long text, message body). */
@Composable
fun MultiLineField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, minLines: Int = 3, placeholder: String = "") {
    val c = ForgeTheme.colors
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(), minLines = minLines, maxLines = 10,
        label = { Text(label) }, placeholder = { Text(placeholder, color = c.text2.copy(alpha = 0.7f)) },
        shape = ForgeShapes.Button, textStyle = ForgeType.Body,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.border, cursorColor = c.accent, focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedLabelColor = c.accent, unfocusedContainerColor = c.surface, focusedContainerColor = c.surface,
        ),
    )
}

/** Plain-text field (names, notes) in the Forge style. */
@Composable
fun TextField1(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "") {
    ForgeTextField(value, onChange, modifier, placeholder, label, keyboardType = keyboard)
}
