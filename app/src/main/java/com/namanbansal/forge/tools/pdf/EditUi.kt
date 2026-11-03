package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditObject
import com.namanbansal.forge.core.pdf.edit.EditStyle
import com.namanbansal.forge.core.pdf.edit.EditFont
import com.namanbansal.forge.core.pdf.edit.RedactHit
import com.namanbansal.forge.core.pdf.edit.RedactPreset
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

private val SIZE_STEPS = listOf(0.016f to "S", 0.024f to "M", 0.036f to "L", 0.052f to "XL")
private val WHITEOUT_COLORS = listOf(EditStyle.WHITE, 0xFFF5F0E1.toInt(), 0xFFE0E0E0.toInt(), 0xFFFFF59D.toInt(), EditStyle.BLACK)
private val WHITEOUT_NAMES = listOf("White", "Cream", "Grey", "Yellow", "Black")
private val FILL_OPACITIES = listOf(0.25f, 0.5f, 0.75f, 1f)

@Composable
private fun Row1(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content,
)

@Composable
private fun Dots(colors: List<Int>, names: List<String>, selected: Int, what: String, onPick: (Int) -> Unit) {
    val c = ForgeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { i, col ->
            val on = col == selected
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(col) }.semantics { contentDescription = "${names[i]} $what"; this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(if (on) 28.dp else 24.dp).clip(CircleShape).background(Color(col)).border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape)) }
        }
    }
}

/** A small drawn symbol for the shape chips. */
@Composable
private fun ShapeGlyph(kind: EditKind, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
        when (kind) {
            EditKind.RECT -> drawRect(tint, Offset(2.dp.toPx(), 4.dp.toPx()), Size(size.width - 4.dp.toPx(), size.height - 8.dp.toPx()), style = s)
            EditKind.ELLIPSE -> drawOval(tint, Offset(2.dp.toPx(), 4.dp.toPx()), Size(size.width - 4.dp.toPx(), size.height - 8.dp.toPx()), style = s)
            EditKind.LINE -> drawLine(tint, Offset(3.dp.toPx(), size.height - 4.dp.toPx()), Offset(size.width - 3.dp.toPx(), 4.dp.toPx()), 1.8.dp.toPx(), cap = StrokeCap.Round)
            else -> {
                val a = Offset(3.dp.toPx(), size.height - 4.dp.toPx()); val b = Offset(size.width - 3.dp.toPx(), 4.dp.toPx())
                drawLine(tint, a, b, 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, b, Offset(b.x - 7.dp.toPx(), b.y + 1.dp.toPx()), 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, b, Offset(b.x - 1.dp.toPx(), b.y + 7.dp.toPx()), 1.8.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ShapeChip(kind: EditKind, on: Boolean, label: String, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    val tint = if (on) c.accent else c.text
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) c.tint else Color.Transparent).border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(22.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp).semantics { contentDescription = label; selected = on },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { ShapeGlyph(kind, tint); Text(label, style = ForgeType.Label, color = tint) }
}

private fun hintFor(tool: EditTool, sel: EditObject?, how: RedactHow, marks: Int, eyedropper: Boolean): String = when {
    eyedropper -> "Tap the page where you want to copy the colour from."
    sel != null -> when (sel.kind) {
        EditKind.IMAGE -> "Drag to move, drag the dot to resize."
        EditKind.WHITEOUT -> "Whiteout covers the page. To really remove content use Redact."
        else -> "Drag to move, drag the dot to resize. Tap a text box again to edit it."
    }
    tool == EditTool.TEXT -> "Tap the page where the text should go."
    tool == EditTool.IMAGE -> "Add a picture from your gallery or take a photo."
    tool == EditTool.SHAPE -> "Drag on the page to draw, or tap to drop one."
    tool == EditTool.WHITEOUT -> "Drag a box over what you want to cover. It's drawn on top of the page."
    tool == EditTool.REDACT -> if (how == RedactHow.DRAW) "Drag a box over what must go. $marks marked." else "Press and hold a word, extend the selection, then tap Mark selection. $marks marked."
    else -> "Pick a tool, or tap something you added to change it."
}

/** Edit's strip above the mode bar: the tool chips, the options of the current tool or selected object, and its actions. */
@Composable
fun EditToolbar(controller: EditController, hasTextSelection: Boolean, onMarkSelection: () -> Unit, onPickImage: () -> Unit, onTakePhoto: () -> Unit) {
    val c = ForgeTheme.colors
    val tool by controller.tool.collectAsState()
    val state by controller.session.state.collectAsState()
    val selectedId by controller.selectedId.collectAsState()
    val selectedMark by controller.selectedMark.collectAsState()
    val template by controller.template.collectAsState()
    val whiteColor by controller.whiteColor.collectAsState()
    val shapeKind by controller.shapeKind.collectAsState()
    val lock by controller.lockAspect.collectAsState()
    val how by controller.redactHow.collectAsState()
    val eyedropper by controller.eyedropper.collectAsState()
    val sel = state.items.firstOrNull { it.id == selectedId }
    val kind = sel?.kind ?: when (tool) { EditTool.TEXT -> EditKind.TEXT; EditTool.SHAPE -> shapeKind; EditTool.WHITEOUT -> EditKind.WHITEOUT; EditTool.IMAGE -> EditKind.IMAGE; else -> null }
    // the look shown: the selected object's, else the template's (new objects)
    val look = sel ?: template
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = "Edit tools" }) {
        Text(hintFor(tool, sel, how, state.marks.size, eyedropper), style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), maxLines = 2)
        Row1 {
            Chip("Text", ForgeIcons.Type, tool == EditTool.TEXT) { controller.setTool(if (tool == EditTool.TEXT) EditTool.SELECT else EditTool.TEXT) }
            Chip("Image", ForgeIcons.Image, tool == EditTool.IMAGE) { controller.setTool(if (tool == EditTool.IMAGE) EditTool.SELECT else EditTool.IMAGE) }
            Chip("Shape", ForgeIcons.Layers, tool == EditTool.SHAPE) { controller.setTool(if (tool == EditTool.SHAPE) EditTool.SELECT else EditTool.SHAPE) }
            Chip("Whiteout", ForgeIcons.Eraser, tool == EditTool.WHITEOUT) { controller.setTool(if (tool == EditTool.WHITEOUT) EditTool.SELECT else EditTool.WHITEOUT) }
            Chip("Redact", ForgeIcons.Shield, tool == EditTool.REDACT) { controller.setTool(if (tool == EditTool.REDACT) EditTool.SELECT else EditTool.REDACT) }
        }
        when (kind) {
            EditKind.TEXT -> TextOptions(controller, look)
            EditKind.IMAGE -> Row1 {
                Chip("Gallery", ForgeIcons.Image, false, description = "Pick a picture from the gallery", onClick = onPickImage)
                Chip("Camera", ForgeIcons.Scan, false, description = "Take a photo", onClick = onTakePhoto)
                if (sel != null) Chip("Keep shape", ForgeIcons.Lock, lock, description = "Lock the aspect ratio") { controller.setLockAspect(!lock) }
            }
            EditKind.WHITEOUT -> WhiteoutOptions(controller, sel, whiteColor, eyedropper)
            EditKind.RECT, EditKind.ELLIPSE, EditKind.LINE, EditKind.ARROW -> ShapeOptions(controller, look, shapeKind, sel)
            null -> Unit
        }
        if (tool == EditTool.REDACT && sel == null) RedactOptions(controller, how, state.marks.size, selectedMark != null, hasTextSelection, onMarkSelection)
        if (sel != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconAction(ForgeIcons.Minus, "Make smaller") { controller.scaleSelected(0.88f) }
            IconAction(ForgeIcons.Plus, "Make bigger") { controller.scaleSelected(1.14f) }
            if (sel.kind == EditKind.IMAGE) IconAction(ForgeIcons.Rotate, "Rotate a quarter turn") { controller.rotateSelected() }
            if (sel.kind.isLine) IconAction(ForgeIcons.Swap, "Flip direction") { controller.flipSelected() }
            if (sel.kind == EditKind.TEXT || sel.kind == EditKind.WHITEOUT) IconAction(ForgeIcons.Type, "Edit text") { controller.editSelectedText() }
            IconAction(ForgeIcons.Copy, "Duplicate") { controller.duplicateSelected() }
            IconAction(ForgeIcons.Layers, "Bring to front") { controller.bringToFront() }
            IconAction(ForgeIcons.Trash, "Delete") { controller.deleteSelected() }
            IconAction(ForgeIcons.Check, "Done") { controller.select(null) }
        }
    }
}

@Composable
private fun TextOptions(controller: EditController, look: EditObject) {
    Row1 {
        EditFont.entries.forEach { f -> Chip(f.label, null, look.font == f, description = "${f.label} font") { controller.style { it.copy(font = f) } } }
        Chip("Bold", null, look.bold, description = "Bold") { controller.style { it.copy(bold = !it.bold) } }
        EditStyle.alignNames.forEachIndexed { i, n -> Chip(n, null, look.align == i, description = "Align $n") { controller.style { it.copy(align = i) } } }
    }
    Row1 {
        IconAction(ForgeIcons.Minus, "Smaller text") { controller.style { it.copy(sizeFrac = (it.sizeFrac * 0.88f).coerceAtLeast(EditStyle.MIN_SIZE)) } }
        SIZE_STEPS.forEach { (s, n) -> Chip(n, null, kotlin.math.abs(look.sizeFrac - s) < 0.002f, description = "Text size $n") { controller.style { it.copy(sizeFrac = s) } } }
        IconAction(ForgeIcons.Plus, "Bigger text") { controller.style { it.copy(sizeFrac = (it.sizeFrac * 1.14f).coerceAtMost(EditStyle.MAX_SIZE)) } }
        Dots(EditStyle.palette, EditStyle.paletteNames, look.color, "text") { col -> controller.style { it.copy(color = col) } }
    }
}

@Composable
private fun ShapeOptions(controller: EditController, look: EditObject, shapeKind: EditKind, sel: EditObject?) {
    val current = sel?.kind ?: shapeKind
    Row1 {
        listOf(EditKind.RECT to "Rectangle", EditKind.ELLIPSE to "Ellipse", EditKind.LINE to "Line", EditKind.ARROW to "Arrow").forEach { (k, n) ->
            ShapeChip(k, current == k, n) { if (sel == null) controller.setShapeKind(k) else controller.style { it.copy(kind = k) } }
        }
    }
    Row1 {
        Dots(EditStyle.palette, EditStyle.paletteNames, look.color, "stroke") { col -> controller.style { it.copy(color = col) } }
        EditStyle.strokeWidths.forEach { w -> Chip("${w.toInt()}", null, look.strokeWidth == w, description = "Stroke width ${w.toInt()}") { controller.style { it.copy(strokeWidth = w) } } }
    }
    if (current == EditKind.RECT || current == EditKind.ELLIPSE) Row1 {
        Chip("Fill", null, look.filled, description = "Fill the shape") { controller.style { it.copy(filled = !it.filled) } }
        if (look.filled) {
            Dots(EditStyle.palette, EditStyle.paletteNames, look.fillColor, "fill") { col -> controller.style { it.copy(fillColor = col) } }
            FILL_OPACITIES.forEach { a -> Chip("${(a * 100).toInt()}%", null, kotlin.math.abs(look.fillOpacity - a) < 0.01f, description = "Fill opacity ${(a * 100).toInt()} percent") { controller.style { it.copy(fillOpacity = a) } } }
        }
    }
}

@Composable
private fun WhiteoutOptions(controller: EditController, sel: EditObject?, whiteColor: Int, eyedropper: Boolean) {
    Row1 {
        Dots(WHITEOUT_COLORS, WHITEOUT_NAMES, whiteColor, "whiteout") { controller.setWhiteColor(it) }
        Chip("Pick colour", ForgeIcons.Pin, eyedropper, description = "Pick a colour from the page") { controller.toggleEyedropper() }
        if (sel != null) {
            Chip("Replace with text", ForgeIcons.Type, sel.text.isNotBlank(), description = "Replace with text") { controller.editSelectedText() }
        }
    }
    if (sel != null && sel.text.isNotBlank()) Row1 {
        Chip("Fit", null, false, description = "Fit the text to the box") { controller.fitSelectedText() }
        SIZE_STEPS.forEach { (s, n) -> Chip(n, null, kotlin.math.abs(sel.sizeFrac - s) < 0.002f, description = "Text size $n") { controller.style(pinSize = true) { it.copy(sizeFrac = s) } } }
        Dots(EditStyle.palette, EditStyle.paletteNames, sel.color, "text") { col -> controller.style { it.copy(color = col) } }
    }
}

@Composable
private fun RedactOptions(controller: EditController, how: RedactHow, marks: Int, markSelected: Boolean, hasTextSelection: Boolean, onMarkSelection: () -> Unit) {
    Row1 {
        Chip("Draw box", null, how == RedactHow.DRAW, description = "Mark by drawing a box") { controller.setRedactHow(RedactHow.DRAW) }
        Chip("Select text", null, how == RedactHow.TEXT, description = "Mark by selecting text") { controller.setRedactHow(RedactHow.TEXT) }
        Chip("Search", ForgeIcons.Search, false, description = "Search and redact") { controller.openSearch() }
        if (hasTextSelection) Chip("Mark selection", ForgeIcons.Check, true, description = "Mark the selected text", onClick = onMarkSelection)
    }
    Row1 {
        if (markSelected) Chip("Remove mark", ForgeIcons.Trash, false, description = "Remove this mark") { controller.deleteSelected() }
        if (marks > 0) Chip("Clear all", null, false, description = "Clear all marks") { controller.session.clearMarks() }
        Chip("Apply redactions", ForgeIcons.Shield, marks > 0, enabled = marks > 0, description = "Apply redactions") { controller.askApply() }
    }
}

// ---- search & redact -------------------------------------------------------------------------------------------------------

/** The review list of "Search & redact": pick a term or a preset, then switch single hits on or off before marking them. */
@Composable
fun RedactSearchContent(ui: RedactSearchUi, onPreset: (RedactPreset) -> Unit, onTerm: (String) -> Unit, onToggle: (String) -> Unit, onAll: (Boolean) -> Unit, onMark: () -> Unit, onClose: () -> Unit) {
    val c = ForgeTheme.colors
    var term by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Search and redact" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search & redact", style = ForgeType.Title, color = c.text)
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = term, onValueChange = { term = it }, singleLine = true, textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onTerm(term) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Word or phrase to redact" },
                decorationBox = { inner -> Box { if (term.isEmpty()) Text("A word or phrase", style = ForgeType.Body, color = c.text2); inner() } },
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Find", ForgeIcons.Search, false, enabled = term.isNotBlank(), description = "Find this text") { onTerm(term) }
            RedactPreset.entries.forEach { p -> Chip(p.label, null, ui.label == p.label, description = "Find ${p.label}") { onPreset(p) } }
        }
        if (ui.running) {
            LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
            Text("Searching for ${ui.label}…", style = ForgeType.Small, color = c.text2)
        } else if (ui.done) {
            Text(
                if (ui.hits.isEmpty()) "Nothing found for ${ui.label}." else "${ui.hits.size} found for ${ui.label}. ${ui.on.size} will be marked.",
                style = ForgeType.Small, color = c.text2, modifier = Modifier.semantics { contentDescription = "Search summary" },
            )
        }
        if (ui.hits.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).semantics { contentDescription = "Matches" }) {
                items(ui.hits, key = { it.id }) { h -> HitRow(h, h.id in ui.on) { onToggle(h.id) } }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (ui.hits.isNotEmpty()) {
                TextButton(onClick = { onAll(true) }) { Text("All", color = c.text2) }
                TextButton(onClick = { onAll(false) }) { Text("None", color = c.text2) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = c.text2) }
            TextButton(onClick = onMark, enabled = ui.on.isNotEmpty()) { Text(if (ui.on.isEmpty()) "Mark" else "Mark ${ui.on.size}", color = if (ui.on.isEmpty()) c.text2 else c.accent) }
        }
    }
}

@Composable
private fun HitRow(h: RedactHit, on: Boolean, onToggle: () -> Unit) {
    val c = ForgeTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 4.dp)
            .semantics { contentDescription = "Page ${h.page + 1}: ${h.text}"; selected = on },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.accent else Color.Transparent).border(1.5.dp, if (on) c.accent else c.border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (on) Icon(ForgeIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(buildAnnotatedString {
                append(h.before.takeLast(18)); if (h.before.isNotEmpty()) append(' ')
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFE53935))) { append(h.text) }
                if (h.after.isNotEmpty()) append(' '); append(h.after.take(18))
            }, style = ForgeType.Body, color = c.text, maxLines = 1)
            Text("Page ${h.page + 1}", style = ForgeType.Small, color = c.text2)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactSearchSheet(ui: RedactSearchUi, onPreset: (RedactPreset) -> Unit, onTerm: (String) -> Unit, onToggle: (String) -> Unit, onAll: (Boolean) -> Unit, onMark: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        RedactSearchContent(ui, onPreset, onTerm, onToggle, onAll, onMark, onClose)
    }
}

// ---- apply ---------------------------------------------------------------------------------------------------------------

/** The strong confirmation before redactions are applied. */
@Composable
fun ApplyRedactionsDialog(marks: Int, pages: Int, removeInfo: Boolean, onRemoveInfo: (Boolean) -> Unit, busy: Boolean, onApply: () -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() }, containerColor = c.surface,
        title = { Text("Apply redactions?", style = ForgeType.Section, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This permanently removes the content. A backup is kept.", style = ForgeType.BodyMedium, color = c.text)
                Text("$marks area${if (marks == 1) "" else "s"} on $pages page${if (pages == 1) "" else "s"} will be blacked out, and the text, picture parts and drawings under them taken out of the file. Other edits you made are saved too. Forge checks the result and keeps your original if anything is left.", style = ForgeType.Small, color = c.text2)
                ToggleRow("Also remove document info", removeInfo, onRemoveInfo, subtitle = "Author, title, dates and metadata.")
            }
        },
        confirmButton = { TextButton(onClick = onApply, enabled = !busy) { Text(if (busy) "Applying…" else "Apply redactions", color = Color(0xFFE53935)) } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = c.text2) } },
    )
}
