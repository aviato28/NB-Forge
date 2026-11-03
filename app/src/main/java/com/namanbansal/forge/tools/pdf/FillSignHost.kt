package com.namanbansal.forge.tools.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.sign.SigKind
import com.namanbansal.forge.ui.toolkit.rememberFilePicker

/** Every sheet and full-screen step of Fill & Sign, driven by the controller's state (so it survives rotation). */
@Composable
fun FillSignHost(vm: ViewerViewModel) {
    val controller = vm.fill
    val edit by controller.fieldEdit.collectAsState()
    val editingItem by controller.editingItem.collectAsState()
    val target by controller.signTarget.collectAsState()
    val form by controller.form.collectAsState()
    val state by controller.session.state.collectAsState()
    val entries by vm.sigEntries.collectAsState()

    // text / dropdown fields
    edit?.let { e ->
        val f = form.fields.firstOrNull { it.id == e.fieldId }
        if (f == null) controller.closeFieldEdit() else {
            val current = com.namanbansal.forge.core.pdf.form.FormValues.valueOf(f, state.overrides)
            val last = form.fillable.lastOrNull()?.id == f.id
            when (f.kind) {
                FieldKind.COMBO, FieldKind.LIST -> ChoiceSheet(f, current, onPick = { controller.setValue(f, it); controller.closeFieldEdit() }, onCancel = controller::closeFieldEdit)
                else -> FieldTextSheet(f, current, hasNext = !last,
                    onDone = { controller.setValue(f, it); controller.closeFieldEdit() },
                    onNext = { controller.setValue(f, it); controller.editNext(f) },
                    onCancel = controller::closeFieldEdit)
            }
        }
    }

    // a text box placed on the page
    editingItem?.let { id ->
        val item = state.items.firstOrNull { it.id == id }
        if (item == null || item.kind != ItemKind.TEXT) controller.closeItemEdit(null)
        else TextBoxSheet(item.text, onSave = { controller.closeItemEdit(it) }, onCancel = { controller.closeItemEdit(null) })
    }

    // signing
    var stage by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf(SigKind.SIGNATURE.name) }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val kind = SigKind.valueOf(kindName)
    val pick = rememberFilePicker(arrayOf("image/*"), false) { uris -> uris.firstOrNull()?.let { photoUri = it.toString() } }
    LaunchedEffect(photoUri) { photo = photoUri?.let { vm.loadPhoto(android.net.Uri.parse(it)) } }
    LaunchedEffect(target) { if (target != null) vm.refreshSignatures() else { stage = ""; photoUri = null } }
    fun back() { stage = ""; photoUri = null }

    if (target != null) when (stage) {
        "method" -> AddMethodSheet(kind, onDraw = { stage = "draw" }, onType = { stage = "type" }, onImport = { stage = "import"; photoUri = null; pick() }, onCancel = ::back)
        "draw" -> FullScreenDialog(::back) { SignaturePadContent(kind, onSave = { vm.saveSignature(kind, it); back() }, onCancel = ::back) }
        "type" -> TypeSignatureSheet(kind, onSave = { vm.saveSignature(kind, it); back() }, onCancel = ::back)
        "import" -> FullScreenDialog(::back) { ImportSignatureContent(photo, onChoosePhoto = pick, onSave = { vm.saveSignature(kind, it); back() }, onCancel = ::back) }
        else -> SignatureLibrarySheet(
            entries = entries, bitmapOf = vm::sigBitmap, canAddSignature = vm.signatures.canAdd(SigKind.SIGNATURE),
            onPick = controller::placeSignature, onAdd = { kindName = it.name; stage = "method" }, onDelete = vm::deleteSignature, onClose = controller::closeSign,
            hint = if (form.signatureFields.isNotEmpty() && target == "") "Adds your handwritten signature. Tip: tap a signature field on the page to place it there." else null,
        )
    }
    @Suppress("UNUSED_VARIABLE") val ctx = LocalContext.current
}
