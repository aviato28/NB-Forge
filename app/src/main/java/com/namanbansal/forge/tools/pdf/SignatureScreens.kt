package com.namanbansal.forge.tools.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.res.ResourcesCompat
import com.namanbansal.forge.R
import com.namanbansal.forge.core.pdf.annot.NormPt
import com.namanbansal.forge.core.pdf.sign.SigEntry
import com.namanbansal.forge.core.pdf.sign.SigKind
import com.namanbansal.forge.core.pdf.sign.SignatureImage
import com.namanbansal.forge.core.pdf.sign.SignatureLibrary
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Ink colours for signatures: black, blue and a dark blue. */
val SIGNATURE_COLORS = listOf(0xFF111111.toInt(), 0xFF1F4FD8.toInt(), 0xFF0B2A6F.toInt())
val SIGNATURE_COLOR_NAMES = listOf("Black", "Blue", "Dark blue")

private fun kindName(k: SigKind) = if (k == SigKind.INITIALS) "initials" else "signature"

@Composable
private fun SheetTitle(text: String, sub: String? = null) {
    val c = ForgeTheme.colors
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        Text(text, style = ForgeType.Title, color = c.text)
        if (sub != null) Text(sub, style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun CheckerPreview(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, c.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
    }
}

// ---- library ----------------------------------------------------------------------------------------------------------------

/** Your signatures and initials: tap one to use it, or add / delete. [bitmapOf] gives the picture of an entry. */
@Composable
fun SignatureLibraryContent(
    entries: List<SigEntry>, bitmapOf: (String) -> ImageBitmap?, canAddSignature: Boolean,
    onPick: (SigEntry) -> Unit, onAdd: (SigKind) -> Unit, onDelete: (SigEntry) -> Unit, onClose: () -> Unit,
    hint: String? = null,
) {
    val c = ForgeTheme.colors
    val sigs = entries.filter { it.kind == SigKind.SIGNATURE }
    val initials = entries.firstOrNull { it.kind == SigKind.INITIALS }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Signature library" }) {
        SheetTitle("Sign", hint ?: "Adds your handwritten signature to the page. It is not a certificate-based digital signature.")
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(entries, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onPick(e) }.padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = "Use ${e.label}" }, verticalAlignment = Alignment.CenterVertically) {
                    CheckerPreview(bitmapOf(e.id), Modifier.width(120.dp).height(60.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.label, style = ForgeType.Item, color = c.text, maxLines = 1)
                        Text(if (e.kind == SigKind.INITIALS) "Initials" else "Signature", style = ForgeType.Small, color = c.text2)
                    }
                    IconAction(ForgeIcons.Trash, "Delete ${e.label}") { onDelete(e) }
                }
            }
        }
        if (entries.isEmpty()) Text("No signatures yet. Create one by drawing, typing your name or importing a photo of your signature.", style = ForgeType.Body, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddButton("New signature", canAddSignature, if (canAddSignature) "${sigs.size} of ${SignatureLibrary.MAX_SIGNATURES}" else "Limit reached") { onAdd(SigKind.SIGNATURE) }
            AddButton(if (initials == null) "Add initials" else "Replace initials", true, null) { onAdd(SigKind.INITIALS) }
        }
        if (!canAddSignature) Text("You can keep up to ${SignatureLibrary.MAX_SIGNATURES} signatures. Delete one to add another.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp))
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text("Close", color = c.text2) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AddButton(label: String, enabled: Boolean, sub: String?, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Column(
        Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) c.tint else c.surface2)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = label },
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ForgeIcons.Plus, null, tint = if (enabled) c.accent else c.text2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(label, style = ForgeType.Label, color = if (enabled) c.accent else c.text2, maxLines = 1)
        }
        if (sub != null) Text(sub, style = ForgeType.Tiny, color = c.text2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureLibrarySheet(
    entries: List<SigEntry>, bitmapOf: (String) -> ImageBitmap?, canAddSignature: Boolean,
    onPick: (SigEntry) -> Unit, onAdd: (SigKind) -> Unit, onDelete: (SigEntry) -> Unit, onClose: () -> Unit, hint: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        SignatureLibraryContent(entries, bitmapOf, canAddSignature, onPick, onAdd, onDelete, onClose, hint)
    }
}

/** How to make a new signature: draw, type or import. */
@Composable
fun AddMethodContent(kind: SigKind, onDraw: () -> Unit, onType: () -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    @Composable fun Row3(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp)) }
            Column(Modifier.padding(start = 12.dp)) { Text(title, style = ForgeType.Item, color = c.text); Text(sub, style = ForgeType.Small, color = c.text2) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Create ${kindName(kind)}" }) {
        SheetTitle(if (kind == SigKind.INITIALS) "Create initials" else "Create a signature")
        Row3(ForgeIcons.Pen, "Draw", "Sign with your finger or a stylus", onDraw)
        Row3(ForgeIcons.Type, "Type", "Your name in a handwriting font", onType)
        Row3(ForgeIcons.Image, "Import a photo", "Pick a picture of your signature on paper", onImport)
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text("Cancel", color = c.text2) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMethodSheet(kind: SigKind, onDraw: () -> Unit, onType: () -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        AddMethodContent(kind, onDraw, onType, onImport, onCancel)
    }
}

@Composable
fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(ForgeTheme.colors.bg)) { content() }
    }
}

// ---- draw ---------------------------------------------------------------------------------------------------------------------

private val StrokesSaver = listSaver<List<List<NormPt>>, List<Float>>(
    save = { all -> all.map { s -> s.flatMap { listOf(it.x, it.y) } } },
    restore = { saved -> saved.map { s -> s.chunked(2).map { NormPt(it[0], it[1]) } } },
)

val PEN_WIDTHS_DP = listOf(2.5f, 4.5f, 7f)

/** A full-screen pad to draw on: smooth strokes, pen thickness, three inks, undo and clear. Points are in pad pixels. */
@Composable
fun SignaturePadContent(kind: SigKind, onSave: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    val density = LocalDensity.current.density
    var strokes by rememberSaveable(stateSaver = StrokesSaver) { mutableStateOf(emptyList<List<NormPt>>()) }
    var live by remember { mutableStateOf(emptyList<NormPt>()) }
    var colorIdx by rememberSaveable { mutableIntStateOf(0) }
    var widthIdx by rememberSaveable { mutableIntStateOf(1) }
    val penPx = PEN_WIDTHS_DP[widthIdx] * density
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp).semantics { contentDescription = "Signature pad" }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(if (kind == SigKind.INITIALS) "Draw your initials" else "Draw your signature", style = ForgeType.Section, color = c.text, modifier = Modifier.weight(1f))
            IconAction(ForgeIcons.Undo, "Undo stroke") { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) }
            TextButton(onClick = { strokes = emptyList(); live = emptyList() }, enabled = strokes.isNotEmpty()) { Text("Clear", color = if (strokes.isNotEmpty()) c.text else c.text2.copy(alpha = 0.5f)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, c.border, RoundedCornerShape(16.dp))) {
            Canvas(
                Modifier.fillMaxSize().semantics { contentDescription = "Drawing area" }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        var pts = listOf(NormPt(down.position.x, down.position.y)); live = pts
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUp()) { ch.consume(); break }
                            if (ch.positionChanged()) { ch.consume(); pts = pts + NormPt(ch.position.x, ch.position.y); live = pts }
                        }
                        strokes = strokes + listOf(pts); live = emptyList()
                    }
                },
            ) {
                val col = Color(SIGNATURE_COLORS[colorIdx])
                val st = Stroke(penPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (s in strokes + listOf(live)) {
                    if (s.isEmpty()) continue
                    if (s.size == 1) drawCircle(col, penPx / 2, Offset(s[0].x, s[0].y)) else drawPath(SignatureImage.smoothPath(s).asComposePath(), col, style = st)
                }
                if (strokes.isEmpty() && live.isEmpty()) drawLine(Color(0x22000000), Offset(24.dp.toPx(), size.height * 0.72f), Offset(size.width - 24.dp.toPx(), size.height * 0.72f), 1.dp.toPx())
            }
            if (strokes.isEmpty() && live.isEmpty()) Text("Sign here", style = ForgeType.Body, color = Color(0x55000000), modifier = Modifier.align(Alignment.Center))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SIGNATURE_COLORS.forEachIndexed { i, col ->
                val on = i == colorIdx
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton) { colorIdx = i }.semantics { contentDescription = "${SIGNATURE_COLOR_NAMES[i]} ink"; selected = on }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (on) 28.dp else 24.dp).clip(CircleShape).background(Color(col)).border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
            PEN_WIDTHS_DP.forEachIndexed { i, w ->
                val on = i == widthIdx
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) c.tint else Color.Transparent).clickable(role = Role.RadioButton) { widthIdx = i }.semantics { contentDescription = "Pen thickness ${i + 1}"; selected = on }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(w.dp + 4.dp).clip(CircleShape).background(if (on) c.accent else c.text2))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            TextButton(onClick = { SignatureImage.renderStrokes(strokes, penPx, SIGNATURE_COLORS[colorIdx])?.let(onSave) }, enabled = strokes.isNotEmpty()) { Text("Save", color = if (strokes.isNotEmpty()) c.accent else c.text2.copy(alpha = 0.5f)) }
        }
    }
}

// ---- type ---------------------------------------------------------------------------------------------------------------------

private data class SigFont(val name: String, val res: Int)
private val SIG_FONTS = listOf(SigFont("Dancing Script", R.font.sig_dancing_script), SigFont("Caveat", R.font.sig_caveat), SigFont("Sacramento", R.font.sig_sacramento))

/** Type your name and pick one of three handwriting-style fonts. */
@Composable
fun TypeSignatureContent(kind: SigKind, onSave: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var fontIdx by rememberSaveable { mutableIntStateOf(0) }
    var colorIdx by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding().semantics { contentDescription = "Type signature" }) {
        Text(if (kind == SigKind.INITIALS) "Type your initials" else "Type your name", style = ForgeType.Title, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.5.dp, c.accent, RoundedCornerShape(14.dp)).padding(12.dp)) {
            BasicTextField(
                value = text, onValueChange = { if (it.length <= 40) text = it }, singleLine = true, textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name to sign with" },
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("Your name", style = ForgeType.Body, color = c.text2); inner() } },
            )
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SIG_FONTS.forEachIndexed { i, f ->
                val on = i == fontIdx
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).border(if (on) 2.dp else 1.dp, if (on) c.accent else c.border, RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { fontIdx = i }.padding(horizontal = 14.dp).semantics { contentDescription = "${f.name} style"; selected = on },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text.ifBlank { "Your name" }, fontFamily = FontFamily(Font(f.res)), fontSize = 30.sp, color = if (text.isBlank()) Color(0x55000000) else Color(SIGNATURE_COLORS[colorIdx]), maxLines = 1, modifier = Modifier.weight(1f))
                    Text(f.name, style = ForgeType.Tiny, color = Color(0xFF6B6B73))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            SIGNATURE_COLORS.forEachIndexed { i, col ->
                val on = i == colorIdx
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton) { colorIdx = i }.semantics { contentDescription = "${SIGNATURE_COLOR_NAMES[i]} ink"; selected = on }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (on) 28.dp else 24.dp).clip(CircleShape).background(Color(col)).border(if (on) 3.dp else 1.dp, if (on) c.text else c.border, CircleShape))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            TextButton(onClick = {
                val tf = runCatching { ResourcesCompat.getFont(context, SIG_FONTS[fontIdx].res) }.getOrNull()
                SignatureImage.renderTyped(text, tf, SIGNATURE_COLORS[colorIdx])?.let(onSave)
            }, enabled = text.isNotBlank()) { Text("Save", color = if (text.isNotBlank()) c.accent else c.text2.copy(alpha = 0.5f)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeSignatureSheet(kind: SigKind, onSave: (Bitmap) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) { TypeSignatureContent(kind, onSave, onCancel) }
}

// ---- import -------------------------------------------------------------------------------------------------------------------

/**
 * Turns a photo of a signature into a transparent one: [source] is the decoded picture (null until one is chosen). The slider is the
 * luminance threshold: raise it to keep fainter ink, lower it to drop a grey background.
 */
@Composable
fun ImportSignatureContent(source: Bitmap?, onChoosePhoto: () -> Unit, onSave: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    var threshold by rememberSaveable(source) { mutableFloatStateOf(-1f) }
    val auto = remember(source) { source?.let { b -> val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); SignatureImage.autoThreshold(px) } ?: SignatureImage.DEFAULT_THRESHOLD }
    val t = if (threshold < 0) auto.toFloat() else threshold
    val processed by produceState<Bitmap?>(null, source, t.toInt()) {
        value = if (source == null) null else { delay(60); withContext(Dispatchers.Default) { SignatureImage.process(source, t.toInt()) } }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).semantics { contentDescription = "Import signature" }) {
        Text("Import a photo of your signature", style = ForgeType.Section, color = c.text)
        Text("Sign on white paper in good light. Forge crops to the ink and removes the paper.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        CheckerPreview(processed?.asImageBitmap(), Modifier.weight(1f).fillMaxWidth())
        if (source != null && processed == null) Text("No ink found. Raise the slider.", style = ForgeType.Small, color = c.accent, modifier = Modifier.padding(top = 6.dp))
        if (source != null) {
            Text("Ink sensitivity", style = ForgeType.Label, color = c.text, modifier = Modifier.padding(top = 10.dp))
            Slider(
                value = t, onValueChange = { threshold = it }, valueRange = 40f..240f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                modifier = Modifier.semantics { contentDescription = "Ink sensitivity" },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onChoosePhoto) { Text(if (source == null) "Choose photo" else "Another photo", color = c.accent) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
            TextButton(onClick = { processed?.let(onSave) }, enabled = processed != null) { Text("Save", color = if (processed != null) c.accent else c.text2.copy(alpha = 0.5f)) }
        }
    }
}
