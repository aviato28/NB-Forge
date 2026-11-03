package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.fill.FillPalette
import com.namanbansal.forge.core.pdf.fill.FillSaveOptions
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.form.FormField
import com.namanbansal.forge.core.pdf.form.KeyHint
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

// ---- toolbar ------------------------------------------------------------------------------------------------------------

@Composable
private fun Chip(label: String, icon: ImageVector?, on: Boolean, enabled: Boolean = true, description: String = label, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) c.tint else Color.Transparent)
            .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(22.dp))
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp).semantics { contentDescription = description; selected = on },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (!enabled) c.text2.copy(alpha = 0.4f) else if (on) c.accent else c.text
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, style = ForgeType.Label, color = tint, maxLines = 1)
    }
}

@Composable
private fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    val c = ForgeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        FillPalette.colors.forEachIndexed { i, col ->
            val on = col == selected
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(col) }.semantics { contentDescription = "${FillPalette.colorNames[i]} text"; this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(if (on) 28.dp else 24.dp).clip(CircleShape).background(Color(col)).border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape)) }
        }
    }
}

@Composable
private fun SizeChips(selected: Int, onPick: (Int) -> Unit) {
    val c = ForgeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FillPalette.sizeNames.forEachIndexed { i, n ->
            val on = i == selected
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (on) c.tint else Color.Transparent).clickable(role = Role.RadioButton) { onPick(i) }
                    .semantics { contentDescription = "Text size $n"; this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(n, style = ForgeType.Item.copy(fontSize = (12 + i * 3).let { s -> androidx.compose.ui.unit.TextUnit(s.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp) }), color = if (on) c.accent else c.text) }
        }
    }
}

/** Fill & Sign's strip above the mode bar: hint, field navigation, Add text, Sign, and the bar for the selected item. */
@Composable
fun FillSignToolbar(controller: FillSignController, notice: String?) {
    val c = ForgeTheme.colors
    val form by controller.form.collectAsState()
    val tool by controller.tool.collectAsState()
    val size by controller.textSize.collectAsState()
    val color by controller.textColor.collectAsState()
    val selectedId by controller.selectedId.collectAsState()
    val state by controller.session.state.collectAsState()
    val sel = state.items.firstOrNull { it.id == selectedId }
    val fillable = form.fillable
    val hint = notice ?: when {
        form.xfaOnly -> "This form uses XFA, a type Forge can't fill. You can still add text and sign."
        form.hasFields -> {
            val req = fillable.count { it.required }
            "${fillable.map { it.name }.distinct().size} fields to fill" + if (req > 0) " · $req required (*)" else " · tap a highlighted field"
        }
        else -> "No form fields in this PDF. Tap Add text, then tap anywhere to type."
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = "Fill and sign tools" }) {
        if (sel != null) SelectedItemBar(controller, sel.kind, sel.size, sel.color)
        else {
            Text(hint, style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), maxLines = 2)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (fillable.isNotEmpty()) {
                    Chip("Previous", ForgeIcons.ChevronUp, false, description = "Previous field") { controller.step(-1) }
                    Chip("Next", ForgeIcons.ChevronDown, false, description = "Next field") { controller.step(1) }
                }
                Chip("Add text", ForgeIcons.Type, tool == FillTool.TEXT) { controller.setTool(if (tool == FillTool.TEXT) FillTool.FIELDS else FillTool.TEXT) }
                Chip("Sign", ForgeIcons.Pen, false) { controller.openSign() }
            }
            if (tool == FillTool.TEXT) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SizeChips(size, controller::setTextSize)
                    ColorDots(color, controller::setTextColor)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Date", ForgeIcons.Calendar, false, description = "Add today's date") { controller.addQuick(ItemKind.TEXT, todayText()) }
                    Chip("✓", null, false, description = "Add a check mark") { controller.addQuick(ItemKind.CHECK) }
                    Chip("✗", null, false, description = "Add a cross") { controller.addQuick(ItemKind.CROSS) }
                }
            }
        }
    }
}

internal fun todayText(): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())

@Composable
private fun SelectedItemBar(controller: FillSignController, kind: ItemKind, size: Int, color: Int) {
    val c = ForgeTheme.colors
    if (kind != ItemKind.SIGNATURE) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kind == ItemKind.TEXT) SizeChips(size, controller::setTextSize)
        ColorDots(color, controller::setTextColor)
        if (kind == ItemKind.TEXT) IconAction(ForgeIcons.Type, "Edit text") { controller.editSelectedText() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        IconAction(ForgeIcons.Minus, "Make smaller") { controller.scaleSelected(0.88f) }
        IconAction(ForgeIcons.Plus, "Make bigger") { controller.scaleSelected(1.14f) }
        if (kind == ItemKind.SIGNATURE) IconAction(ForgeIcons.Rotate, "Rotate a quarter turn") { controller.rotateSelected() }
        IconAction(ForgeIcons.Trash, "Delete") { controller.deleteSelected() }
        IconAction(ForgeIcons.Check, "Done") { controller.select(null) }
    }
    Text("Drag to move, drag the dot to resize.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp))
}

// ---- field sheets ----------------------------------------------------------------------------------------------------------

private fun keyboardFor(h: KeyHint) = when (h) {
    KeyHint.NUMBER, KeyHint.DATE -> KeyboardType.Number
    KeyHint.DECIMAL -> KeyboardType.Decimal
    KeyHint.PHONE -> KeyboardType.Phone
    KeyHint.EMAIL -> KeyboardType.Email
    KeyHint.TEXT -> KeyboardType.Text
}

/** The text of a form field, typed in a sheet with the keyboard that suits it. */
@Composable
fun FieldTextContent(field: FormField, initial: String, hasNext: Boolean, onDone: (String) -> Unit, onNext: (String) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    var value by rememberSaveable(field.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(field.id) { runCatching { focus.requestFocus() } }
    val multi = field.kind == FieldKind.MULTILINE
    val limit = if (field.maxLen > 0) field.maxLen else 5000
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding().semantics { contentDescription = "Field editor" }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(field.title, style = ForgeType.Title, color = c.text, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
            if (field.required) Text("  Required", style = ForgeType.Label, color = c.accent)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = if (multi) 120.dp else 52.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.5.dp, c.accent, RoundedCornerShape(14.dp)).padding(12.dp)) {
            BasicTextField(
                value = value, onValueChange = { value = if (it.text.length <= limit) it else TextFieldValue(it.text.take(limit), TextRange(limit)) },
                singleLine = !multi, textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardFor(field.hint), imeAction = if (multi) ImeAction.Default else if (hasNext) ImeAction.Next else ImeAction.Done,
                    capitalization = if (field.hint == KeyHint.TEXT) KeyboardCapitalization.Sentences else KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onNext = { onNext(value.text) }, onDone = { onDone(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "${field.title} value" },
                decorationBox = { inner -> Box { if (value.text.isEmpty()) Text("Type here", style = ForgeType.Body, color = c.text2); inner() } },
            )
        }
        if (field.maxLen > 0) Text("${value.text.length} / ${field.maxLen} characters", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { value = TextFieldValue("") }) { Text("Clear", color = c.text2) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            if (hasNext) TextButton(onClick = { onNext(value.text) }) { Text("Next", color = c.accent) }
            TextButton(onClick = { onDone(value.text) }) { Text("Done", color = c.accent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTextSheet(field: FormField, initial: String, hasNext: Boolean, onDone: (String) -> Unit, onNext: (String) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        FieldTextContent(field, initial, hasNext, onDone, onNext, onCancel)
    }
}

/** A dropdown: the options of a combo box or list box; a multi-select list gets tick marks and a Done button. */
@Composable
fun ChoiceContent(field: FormField, current: String, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    var chosen by remember(field.id) { mutableStateOf(current.split('\n').filter { it.isNotEmpty() }.toSet()) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Choose an option" }) {
        Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(field.title, style = ForgeType.Title, color = c.text, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
            if (field.required) Text("  Required", style = ForgeType.Label, color = c.accent)
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(field.options) { opt ->
                val on = opt in chosen
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        if (field.multiSelect) chosen = if (on) chosen - opt else chosen + opt else onPick(opt)
                    }.padding(horizontal = 16.dp).semantics { contentDescription = opt; selected = on },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(opt, style = ForgeType.Body, color = if (on) c.accent else c.text, modifier = Modifier.weight(1f))
                    if (on) Icon(ForgeIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!field.required) TextButton(onClick = { onPick("") }) { Text("Clear", color = c.text2) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            if (field.multiSelect) TextButton(onClick = { onPick(chosen.joinToString("\n")) }) { Text("Done", color = c.accent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(field: FormField, current: String, onPick: (String) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) { ChoiceContent(field, current, onPick, onCancel) }
}

/** Text typed into a text box placed on the page. */
@Composable
fun TextBoxContent(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding().semantics { contentDescription = "Text box editor" }) {
        Text("Text", style = ForgeType.Title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.5.dp, c.accent, RoundedCornerShape(14.dp)).padding(12.dp)) {
            BasicTextField(
                value = value, onValueChange = { if (it.text.length <= 1000) value = it }, textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Text box value" },
                decorationBox = { inner -> Box { if (value.text.isEmpty()) Text("Type your text", style = ForgeType.Body, color = c.text2); inner() } },
            )
        }
        Text("Letters, numbers and common symbols are saved into the PDF; other characters become ?.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            TextButton(onClick = { onSave(value.text) }) { Text("Done", color = c.accent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextBoxSheet(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) { TextBoxContent(initial, onSave, onCancel) }
}

// ---- save options ----------------------------------------------------------------------------------------------------------

/** Shown on Save only when it matters: flatten the form, flatten the signatures. Both default to off. */
@Composable
fun SaveOptionsContent(showForm: Boolean, showSignatures: Boolean, copyOnlyReason: String?, onSave: (FillSaveOptions) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    var form by rememberSaveable { mutableStateOf(false) }
    var sig by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Save options" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Save options", style = ForgeType.Title, color = c.text)
        if (copyOnlyReason != null) Text("$copyOnlyReason Forge will save a copy and leave the original as it is.", style = ForgeType.Small, color = c.accent)
        if (showForm) ToggleRow("Flatten form fields", form, { form = it }, subtitle = "Makes the typed answers part of the page. The form can't be edited afterwards.")
        if (showSignatures) ToggleRow("Flatten signatures into page", sig, { sig = it }, subtitle = "Stamps the signature into the page so it can't be moved or removed easily. Off keeps it as a movable stamp.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            TextButton(onClick = { onSave(FillSaveOptions(form, sig)) }) { Text(if (copyOnlyReason != null) "Save a copy" else "Save", color = c.accent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveOptionsSheet(showForm: Boolean, showSignatures: Boolean, copyOnlyReason: String?, onSave: (FillSaveOptions) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        SaveOptionsContent(showForm, showSignatures, copyOnlyReason, onSave, onCancel)
    }
}
