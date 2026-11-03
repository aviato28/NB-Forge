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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotTool
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

private fun toolIcon(t: AnnotTool): ImageVector = when (t) {
    AnnotTool.SELECT -> ForgeIcons.Pointer
    AnnotTool.HIGHLIGHT -> ForgeIcons.Highlight
    AnnotTool.UNDERLINE -> ForgeIcons.Underline
    AnnotTool.STRIKEOUT -> ForgeIcons.Strike
    AnnotTool.PEN -> ForgeIcons.Pen
    AnnotTool.HIGHLIGHTER -> ForgeIcons.Wand
    AnnotTool.ERASER -> ForgeIcons.Eraser
    AnnotTool.NOTE -> ForgeIcons.Note
}

private val SHORT = mapOf(AnnotTool.STRIKEOUT to "Strike", AnnotTool.HIGHLIGHTER to "Marker")

// ---- mode bar -------------------------------------------------------------------------------------------------------------

private fun modeIcon(m: ViewerModeId): ImageVector = when (m) {
    ViewerModeId.READ -> ForgeIcons.Eye
    ViewerModeId.ANNOTATE -> ForgeIcons.Highlight
    ViewerModeId.EDIT -> ForgeIcons.Type
    ViewerModeId.FILL_SIGN -> ForgeIcons.Pen
    ViewerModeId.PAGES -> ForgeIcons.Grid
    ViewerModeId.MORE -> ForgeIcons.More
}

/** Read · Annotate · Edit · Fill & Sign · Pages · More. */
@Composable
fun ViewerModeBar(current: ViewerModeId, onPick: (ViewerModeId) -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (m in ViewerModeId.entries) {
            val on = m == current
            Column(
                Modifier.weight(1f).heightIn(min = 52.dp).padding(horizontal = 2.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.tint else Color.Transparent)
                    .clickable(role = Role.Tab) { onPick(m) }
                    .semantics { contentDescription = m.label; selected = on },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(modeIcon(m), null, tint = if (on) c.accent else c.text2, modifier = Modifier.size(20.dp))
                Text(m.label, style = ForgeType.Tiny, color = if (on) c.accent else c.text2, maxLines = 1)
            }
        }
    }
}

// ---- tools, colours -------------------------------------------------------------------------------------------------------

/** The 6-colour palette; the chosen one carries a ring. */
@Composable
fun ColorPicker(selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (col in AnnotPalette.colors) {
            val on = col == selected
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(col) }
                    .semantics { contentDescription = "${AnnotPalette.names[col]} colour"; this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (on) 30.dp else 26.dp).clip(CircleShape).background(Color(col)).border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape))
            }
        }
    }
}

/** Tool strip with the colour palette (and thickness for the pens) of the current tool underneath. */
@Composable
fun AnnotateToolbar(
    tool: AnnotTool, colors: Map<AnnotTool, Int>, thickness: Map<AnnotTool, Int>,
    onTool: (AnnotTool) -> Unit, onColor: (AnnotTool, Int) -> Unit, onThickness: (AnnotTool, Int) -> Unit,
) {
    val c = ForgeTheme.colors
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Annotate tools" }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            for (t in AnnotTool.entries) {
                val on = t == tool
                Column(
                    Modifier.widthIn(min = 48.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.tint else Color.Transparent)
                        .clickable(role = Role.RadioButton) { onTool(t) }.padding(horizontal = 3.dp, vertical = 4.dp)
                        .semantics { contentDescription = t.label; selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(toolIcon(t), null, tint = if (on) c.accent else c.text, modifier = Modifier.size(22.dp))
                    Text(SHORT[t] ?: t.label, style = ForgeType.Tiny, color = if (on) c.accent else c.text2, maxLines = 1)
                }
            }
        }
        if (tool.hasColor) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorPicker(colors[tool] ?: AnnotPalette.defaultColor(tool), { onColor(tool, it) })
                if (tool.hasThickness) {
                    Spacer(Modifier.weight(1f))
                    ThicknessPicker(thickness[tool] ?: 1, tool == AnnotTool.HIGHLIGHTER, { onThickness(tool, it) })
                }
            }
        }
    }
}

@Composable
private fun ThicknessPicker(selected: Int, marker: Boolean, onPick: (Int) -> Unit) {
    val c = ForgeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        AnnotPalette.thicknesses.forEachIndexed { i, _ ->
            val on = i == selected
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (on) c.tint else Color.Transparent).clickable(role = Role.RadioButton) { onPick(i) }
                    .semantics { contentDescription = "${AnnotPalette.thicknessNames[i]} ${if (marker) "marker" else "pen"}"; this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(22.dp).height((2 + i * 3 + (if (marker) 3 else 0)).dp).clip(CircleShape).background(if (on) c.accent else c.text2))
            }
        }
    }
}

/** Shown when an annotation is selected: change its colour, delete it, or edit a note's text. */
@Composable
fun SelectionBar(annot: Annot, onColor: (Int) -> Unit, onDelete: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = "Selected annotation" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorPicker(annot.color, onColor)
        Spacer(Modifier.weight(1f))
        if (annot.type == AnnotType.NOTE) IconAction(ForgeIcons.Pen, "Edit note", onEdit)
        IconAction(ForgeIcons.Trash, "Delete annotation", onDelete)
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp))
    }
}

// ---- note editor, dialogs, coming soon ------------------------------------------------------------------------------------

/** Text box and buttons of the note sheet. */
@Composable
fun NoteEditorContent(initial: String, isNew: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val c = ForgeTheme.colors
    var field by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding().semantics { contentDescription = "Note editor" }) {
        Text(if (isNew) "New note" else "Edit note", style = ForgeType.Title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.5.dp, c.accent, RoundedCornerShape(14.dp)).padding(12.dp)) {
            BasicTextField(
                value = field, onValueChange = { field = if (it.text.length > 2000) field else it },
                textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Note text" },
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("Type your note", style = ForgeType.Body, color = c.text2); inner() } },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) TextButton(onClick = onDelete) { Icon(ForgeIcons.Trash, null, Modifier.size(18.dp), tint = c.text2); Spacer(Modifier.width(6.dp)); Text("Delete", color = c.text2) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            TextButton(onClick = { onSave(text) }) { Text("Save note", color = c.accent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(initial: String, isNew: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val c = ForgeTheme.colors
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        NoteEditorContent(initial, isNew, onSave, onCancel, onDelete)
    }
}

/** Back or close with unsaved annotations. [busy] disables the buttons while saving. */
@Composable
fun UnsavedChangesDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit, busy: Boolean = false) {
    val c = ForgeTheme.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() }, containerColor = c.surface,
        title = { Text("Save your changes?", style = ForgeType.Section, color = c.text) },
        text = { Text("You have unsaved annotations. Saving writes them into the PDF; a backup of the original is kept.", style = ForgeType.Body, color = c.text2) },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text(if (busy) "Saving…" else "Save", color = c.accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = c.text2) }
                TextButton(onClick = onDiscard, enabled = !busy) { Text("Discard", color = c.text2) }
            }
        },
    )
}

/** Content of the "Coming soon" sheet for Edit and Fill & Sign. */
@Composable
fun ComingSoonContent(mode: ViewerModeId, onClose: () -> Unit) {
    val c = ForgeTheme.colors
    val (headline, detail) = when (mode) {
        ViewerModeId.EDIT -> "Edit is coming soon" to "Add text, images and shapes, whiteout and real redaction straight on the page."
        else -> "Fill & Sign is coming soon" to "Fill in forms and sign with a saved signature, without leaving the document."
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().semantics { contentDescription = "Coming soon" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) { Icon(modeIcon(mode), null, tint = c.accent, modifier = Modifier.size(28.dp)) }
        Text(headline, style = ForgeType.Title, color = c.text, modifier = Modifier.padding(top = 14.dp))
        Text(detail, style = ForgeType.Body, color = c.text2, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("In the meantime, Annotate is ready: highlight, underline, draw and add notes.", style = ForgeType.Small, color = c.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("Got it", color = c.accent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComingSoonSheet(mode: ViewerModeId, onDismiss: () -> Unit) {
    val c = ForgeTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) { ComingSoonContent(mode, onDismiss) }
}

// ---- top bar pieces, More ---------------------------------------------------------------------------------------------------

@Composable
fun UndoRedoButtons(canUndo: Boolean, canRedo: Boolean, history: com.namanbansal.forge.core.pdf.annot.UndoRedo) {
    val c = ForgeTheme.colors
    @Composable
    fun Btn(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
    }
    Btn(ForgeIcons.Undo, "Undo", canUndo) { history.undo() }
    Btn(ForgeIcons.Redo, "Redo", canRedo) { history.redo() }
}

/** Save with a dot while there are unsaved changes. */
@Composable
fun SaveButton(dirty: Boolean, saving: Boolean, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (dirty) c.tint else c.surface).border(1.dp, if (dirty) c.accent else c.border, CircleShape)
            .clickable(enabled = !saving, onClick = onClick)
            .semantics { contentDescription = if (saving) "Saving" else if (dirty) "Save, unsaved changes" else "Save" },
        contentAlignment = Alignment.Center,
    ) {
        if (saving) androidx.compose.material3.CircularProgressIndicator(color = c.accent, trackColor = c.surface2, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Icon(ForgeIcons.Save, null, tint = if (dirty) c.accent else c.text, modifier = Modifier.size(20.dp))
        if (dirty && !saving) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(9.dp).clip(CircleShape).background(c.accent).border(1.5.dp, c.surface, CircleShape))
    }
}

/** The tools that take a PDF, to send the open file to. */
@Composable
fun MoreToolsContent(tools: List<com.namanbansal.forge.tools.ToolDef>, onPick: (String) -> Unit) {
    val c = ForgeTheme.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp).semantics { contentDescription = "More tools" }) {
        Text("Open this PDF in…", style = ForgeType.Title, color = c.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        tools.forEach { t ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(t.code) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(t.icon, null, tint = c.text, modifier = Modifier.size(22.dp))
                Column { Text(t.shortLabel, style = ForgeType.Item, color = c.text); Text(t.description, style = ForgeType.Small, color = c.text2, maxLines = 1) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreToolsSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = ForgeTheme.colors
    val tools = remember { com.namanbansal.forge.tools.ToolRegistry.availableFor(setOf(com.namanbansal.forge.core.FileKind.PDF)).filter { it.code != "P40" && it.code != "P17" && it.code != "P3" } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) { MoreToolsContent(tools, onPick) }
}
