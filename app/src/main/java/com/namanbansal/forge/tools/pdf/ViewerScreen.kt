package com.namanbansal.forge.tools.pdf

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.share.PdfPrintAdapter
import com.namanbansal.forge.core.pdf.TextHit
import com.namanbansal.forge.data.ViewerPrefs
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.rememberFilePicker
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PICK = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to read")

/**
 * P40 Viewer: home with recent PDFs, then continuous or page-by-page reading with sharp tiled zoom, text selection, links,
 * contents panel, search, dark pages and immersive bars. Layout choices persist globally; the position persists per document.
 */
@Composable
fun ViewerScreen(host: ToolHost, vm: ViewerViewModel = viewModel(), startMode: ViewerModeId = ViewerModeId.READ, startTool: EditTool? = null) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val error by vm.openError.collectAsStateWithLifecycle()
    val snack = rememberSnack()
    val pick = rememberFilePicker(PICK.mimes, multiple = false) { vm.open(it) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { snack(it) } }
    LaunchedEffect(vm, startMode, startTool) { if (startTool != null) vm.startInEdit(startTool) else vm.startIn(startMode) }

    val t = thumbs
    if (phase == ToolPhase.Options && source != null && t != null && restored) {
        ViewerContent(host, vm, t, title = inputs.firstOrNull()?.name ?: host.tool.name, pageCount = t.pageCount, snack = snack)
    } else {
        ViewerHome(
            title = host.tool.name, recents = recents,
            loading = loading || (phase == ToolPhase.Options && prompt == null && error == null),
            error = error, coverFile = { app -> com.namanbansal.forge.ForgeApp.from(context).viewerPositions.coverFile(app.key) },
            onOpenPdf = pick, onOpenRecent = vm::openRecent, onRemoveRecent = vm::removeRecent,
            onReport = { text -> clipboard.setText(AnnotatedString(text)); snack("Details copied. Paste them into a message to report the problem.") },
            onDismissError = vm::dismissError, onBack = host.onBack,
        )
    }
    prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) }
}

@Composable
private fun ViewerContent(host: ToolHost, vm: ViewerViewModel, thumbs: PdfThumbnails, title: String, pageCount: Int, snack: (String) -> Unit) {
    val c = ForgeTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val invert by vm.invert.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val searchJump by vm.jump.collectAsStateWithLifecycle()
    val aspects by vm.aspects.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val flash by vm.flash.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val revision by vm.revision.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val dirty by vm.anyDirty.collectAsStateWithLifecycle()
    val fillDirty by vm.fill.session.dirty.collectAsStateWithLifecycle()
    val fillState by vm.fill.session.state.collectAsStateWithLifecycle()
    val fillForm by vm.fill.form.collectAsStateWithLifecycle()
    val fillSelected by vm.fill.selectedId.collectAsStateWithLifecycle()
    val fillJump by vm.fill.jump.collectAsStateWithLifecycle()
    val permissionProblem by vm.permissionProblem.collectAsStateWithLifecycle()
    val origin by vm.origin.collectAsStateWithLifecycle()
    val savedTo by vm.savedTo.collectAsStateWithLifecycle()
    val bannerDismissed by vm.bannerDismissed.collectAsStateWithLifecycle()
    val pdfInfo by vm.info.collectAsStateWithLifecycle()
    val editCanUndo by vm.edit.session.canUndo.collectAsStateWithLifecycle()
    val editCanRedo by vm.edit.session.canRedo.collectAsStateWithLifecycle()
    val editState by vm.edit.session.state.collectAsStateWithLifecycle()
    val editSelected by vm.edit.selectedId.collectAsStateWithLifecycle()
    val editTool by vm.edit.tool.collectAsStateWithLifecycle()
    val exSel by vm.edit.existing.selection.collectAsStateWithLifecycle()
    val editingId by vm.edit.editingId.collectAsStateWithLifecycle()
    val applyConfirm by vm.edit.applyConfirm.collectAsStateWithLifecycle()
    val removeInfo by vm.edit.removeInfo.collectAsStateWithLifecycle()
    val redactSearch by vm.edit.search.collectAsStateWithLifecycle()
    val imageRev by vm.imageRev.collectAsStateWithLifecycle()
    val annotCanUndo by vm.annotate.session.canUndo.collectAsStateWithLifecycle()
    val annotCanRedo by vm.annotate.session.canRedo.collectAsStateWithLifecycle()
    val fillCanUndo by vm.fill.session.canUndo.collectAsStateWithLifecycle()
    val fillCanRedo by vm.fill.session.canRedo.collectAsStateWithLifecycle()
    val selectedId by vm.annotate.selectedId.collectAsStateWithLifecycle()
    val editingNote by vm.annotate.editingNote.collectAsStateWithLifecycle()
    val annots by vm.annotate.session.annots.collectAsStateWithLifecycle()
    val annotating = mode == ViewerModeId.ANNOTATE
    val annotMode = remember(vm, annotating) { AnnotateMode(vm.annotate, interactive = annotating) }
    val filling = mode == ViewerModeId.FILL_SIGN
    val fillMode = remember(vm, filling, permissionProblem) { FillSignMode(vm.fill, interactive = filling, notice = permissionProblem?.let { "$it Forge can save a copy instead." }, sigBitmap = vm::sigBitmap) }
    val editing = mode == ViewerModeId.EDIT
    val pickImage = rememberFilePicker(arrayOf("image/*"), false) { uris -> uris.firstOrNull()?.let(vm::addImage) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri; cameraUri = null
        if (ok && u != null) vm.addImage(android.net.Uri.parse(u))
    }
    val launchCamera: () -> Unit = {
        val f = java.io.File(context.cacheDir, "camera/photo-${System.nanoTime()}.jpg").also { it.parentFile?.mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        cameraUri = uri.toString()
        try { takePhoto.launch(uri) } catch (_: Exception) { cameraUri = null; snack("No camera app is available on this phone.") }
    }
    val editMode = remember(vm, editing, imageRev, selection != null) {
        EditMode(vm.edit, interactive = editing, pageWidthPt = vm::pageWidthPt, imageOf = vm::editImage, existingImage = vm::existingImage, hasTextSelection = editing && selection != null,
            onMarkSelection = { vm.selection.value?.let { s -> vm.edit.markSelection(s); vm.clearSelection() } }, onPickImage = pickImage, onTakePhoto = launchCamera)
    }
    val canUndo = if (editing) editCanUndo else if (filling) fillCanUndo else annotCanUndo
    val canRedo = if (editing) editCanRedo else if (filling) fillCanRedo else annotCanRedo
    var applyDone by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var saveOptions by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var saveOptionsOpen by rememberSaveable { mutableStateOf(false) }
    /** Save, asking first where to put it when the original can't be replaced, then about flattening (a filled form or a signature). */
    fun proceedSave(done: (Boolean) -> Unit) {
        if (editState.marks.isNotEmpty()) { applyDone = done; vm.edit.askApply(); return }
        val formEdits = fillState.overrides.isNotEmpty() && fillForm.hasAcroForm
        val needsAsk = fillDirty && (formEdits || fillState.hasSignatures || permissionProblem != null)
        if (needsAsk) { saveOptions = done; saveOptionsOpen = true } else vm.saveAnnotations(onDone = done)
    }
    var whereDone by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var whereOpen by rememberSaveable { mutableStateOf(false) }
    var pickPurposeEdits by remember { mutableStateOf(false) }
    val savePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        val edits = pickPurposeEdits; pickPurposeEdits = false
        val done = whereDone; whereDone = null
        if (dest == null) { if (edits) done?.invoke(false); return@rememberLauncherForActivityResult }
        if (edits) {
            vm.saveTarget = com.namanbansal.forge.core.pdf.annot.SaveTarget.Chosen(dest, dest.lastPathSegment?.substringAfterLast('/') ?: "your file")
            proceedSave(done ?: {})
        } else vm.saveAs(dest)
    }
    fun requestSave(done: (Boolean) -> Unit = {}) {
        vm.saveTarget = com.namanbansal.forge.core.pdf.annot.SaveTarget.Original
        scope.launch { if (vm.needsSaveLocation()) { whereDone = done; whereOpen = true } else proceedSave(done) }
    }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var sharePagesOpen by remember { mutableStateOf(false) }
    fun runFileAction(action: FileAction, withChanges: Boolean) {
        scope.launch {
            val st = vm.stage(withChanges) ?: return@launch
            val ok = when (action) {
                FileAction.SHARE -> FileActions.share(context, st.uri, "application/pdf", st.name)
                FileAction.PRINT -> PdfPrintAdapter.print(context, st.file, st.name, pageCount)
                FileAction.OPEN_WITH -> FileActions.openWith(context, st.uri, "application/pdf")
            }
            if (!ok) snack(when (action) { FileAction.SHARE -> "No app available to share this file."; FileAction.PRINT -> "Printing isn't available on this phone."; FileAction.OPEN_WITH -> "No other app on this phone can open PDFs." })
        }
    }
    fun startFileAction(action: FileAction) { if (dirty) fileAction = action else runFileAction(action, false) }
    fun shareStaged(st: ViewerViewModel.Staged?, mime: String) { if (st != null && !FileActions.share(context, st.uri, mime, st.name)) snack("No app available to share this.") }
    val fileMenu = remember(vm, pageCount) {
        FileMenuActions(
            onShare = { startFileAction(FileAction.SHARE) },
            onSaveToPhone = { vm.saveToPhone() },
            onSaveAs = { pickPurposeEdits = false; savePicker.launch(vm.fileName) },
            onPrint = { startFileAction(FileAction.PRINT) },
            onOpenWith = { startFileAction(FileAction.OPEN_WITH) },
            onSharePages = { sharePagesOpen = true },
            onInfo = { vm.loadInfo(); infoOpen = true },
        )
    }
    var moreSheet by remember { mutableStateOf(false) }
    // What to do once the unsaved-changes question is answered (leave, open another tool...).
    var guarded by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guard(action: () -> Unit) { if (dirty) guarded = action else action() }

    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var searchOpen by remember { mutableStateOf(query.isNotEmpty()) }
    var searchNonce by remember { mutableIntStateOf(0) }
    var showHits by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableIntStateOf(vm.startPage().coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    var jumpReq by remember { mutableStateOf<JumpRequest?>(null) }
    var scrollTick by remember { mutableIntStateOf(0) }
    var recentlyScrolled by remember { mutableStateOf(false) }

    fun jumpTo(p: Int) { jumpReq = JumpRequest(p, (jumpReq?.nonce ?: 0) + 1) }
    LaunchedEffect(fillJump.second) { if (fillJump.second > 0) jumpTo(fillJump.first) }
    LaunchedEffect(scrollTick) { if (scrollTick > 0) { recentlyScrolled = true; delay(1500); recentlyScrolled = false } }
    LaunchedEffect(searchJump) { if (searchJump > 0) hits.getOrNull(current)?.let { jumpTo(it.page) } }
    LaunchedEffect(panelOpen) { if (panelOpen) vm.loadOutline() }
    DisposableEffect(prefs.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(Unit) { onDispose { vm.flushPosition() } }

    // System back: peel off one layer at a time (selection, contents, menu, search) before leaving the document.
    BackHandler(enabled = selection != null || panelOpen || menu || searchOpen || selectedId != null || (filling && fillSelected != null) || (editing && (editSelected != null || exSel.isNotEmpty() || (editTool != EditTool.SELECT && editTool != EditTool.EXISTING))) || dirty) {
        when {
            selection != null -> vm.clearSelection()
            menu -> menu = false
            panelOpen -> panelOpen = false
            searchOpen -> { searchOpen = false; showHits = false; vm.clearSearch() }
            selectedId != null -> vm.annotate.select(null)
            filling && fillSelected != null -> vm.fill.select(null)
            editing && editSelected != null -> vm.edit.select(null)
            editing && exSel.isNotEmpty() -> vm.edit.existing.clearSelection()
            editing && editTool != EditTool.SELECT && editTool != EditTool.EXISTING -> vm.edit.setTool(EditTool.EXISTING)
            else -> guard(host.onBack)
        }
    }

    val actions = remember(vm) {
        SelectionActions(
            copy = { scope.launch { clipboard.setText(AnnotatedString(vm.selectedText())); vm.clearSelection(); snack("Copied") } },
            selectAll = { scope.launch { vm.selection.value?.let { vm.selectAll(it.page) } } },
            search = { scope.launch { val t = vm.selectedText(); vm.clearSelection(); if (t.isNotBlank()) { vm.search(t); searchNonce++; searchOpen = true } } },
            share = {
                scope.launch {
                    val t = vm.selectedText()
                    if (t.isNotBlank()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                androidx.compose.runtime.key(revision) {
                    ViewerPages(
                        vm = vm, thumbs = thumbs, tiles = tiles, aspects = aspects, pageCount = pageCount, hits = hits, currentHit = current,
                        invert = invert, prefs = prefs, jump = jumpReq, flash = flash, selection = selection, actions = actions,
                        onPage = { currentPage = it; vm.rememberPage(it) },
                        onTap = { barsVisible = !barsVisible },
                        onUri = { pendingUri = it },
                        onScrolling = { scrollTick++ },
                        modifier = Modifier.fillMaxSize(),
                        input = if (editing) editMode.input else if (filling) fillMode.input else annotMode.input,
                        extraLayer = { page, w, h -> annotMode.PageOverlay(page, w, h, invert); fillMode.PageOverlay(page, w, h, invert); editMode.PageOverlay(page, w, h, invert) },
                    )
                }
                val barsOn = barsVisible || annotating || filling || editing
                val topVisible = barsOn || searchOpen
                Fade(topVisible, Modifier.align(Alignment.TopCenter)) {
                    Column(Modifier.fillMaxWidth().background(c.bg.copy(alpha = 0.96f)).border(1.dp, c.border.copy(alpha = 0.6f))) {
                        TopBar(title, onBack = { guard(host.onBack) }) {
                            if (annotating || filling || editing) {
                                UndoRedoButtons(canUndo, canRedo, if (editing) vm.edit.session else if (filling) vm.fill.session else vm.annotate.session)
                            } else {
                                RoundIconButton(ForgeIcons.Search, "Search in document", { searchOpen = !searchOpen; if (!searchOpen) vm.clearSearch() }, active = searchOpen)
                                Spacer(Modifier.width(8.dp))
                                RoundIconButton(ForgeIcons.Share, "Share", { fileMenu.onShare() })
                                Spacer(Modifier.width(8.dp))
                                if (!dirty) { RoundIconButton(ForgeIcons.Download, "Save to phone", { fileMenu.onSaveToPhone() }); Spacer(Modifier.width(8.dp)) }
                            }
                            if (dirty || saving) { SaveButton(dirty, saving) { requestSave() }; Spacer(Modifier.width(8.dp)) }
                            Box {
                                RoundIconButton(ForgeIcons.More, "More actions", { menu = true })
                                ViewerMenu(menu, prefs, onDismiss = { menu = false }, onPrefs = vm::updatePrefs, files = fileMenu, invert = invert, onToggleInvert = vm::toggleInvert) { code -> menu = false; host.onOpenTool(code, inputs) }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        val o = origin
                        if (o != null && o.temporary && savedTo == null && !bannerDismissed) TemporaryBanner(o.from, onSave = { vm.saveToPhone() }, onDismiss = vm::dismissBanner)
                        Fade(searchOpen) {
                            androidx.compose.runtime.key(searchNonce) {
                                SearchBar(
                                    query = query, searching = searching, count = hits.size, current = current,
                                    onSubmit = vm::search, onPrev = vm::previousHit, onNext = vm::nextHit,
                                    onClose = { searchOpen = false; showHits = false; vm.clearSearch() },
                                    listOpen = showHits, onToggleList = { showHits = !showHits },
                                )
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Fade(searchOpen && showHits && hits.isNotEmpty()) {
                        HitList(hits, current, Modifier.heightIn(max = 240.dp)) { i -> vm.openHit(i) }
                    }
                    Fade(barsOn) {
                      Column(Modifier.fillMaxWidth().background(c.bg).border(1.dp, c.border.copy(alpha = 0.6f)).navigationBarsPadding()) {
                        if (filling) fillMode.Toolbar()
                        if (editing) editMode.Toolbar()
                        if (annotating) {
                            val sel = annots.firstOrNull { it.id == selectedId }
                            if (sel != null) SelectionBar(sel, vm.annotate::recolorSelected, vm.annotate::deleteSelected, vm.annotate::editSelectedNote)
                            else annotMode.Toolbar()
                        }
                        ViewerModeBar(mode, { m ->
                            when (m) {
                                ViewerModeId.READ, ViewerModeId.ANNOTATE, ViewerModeId.EDIT, ViewerModeId.FILL_SIGN -> vm.setMode(m)
                                ViewerModeId.PAGES -> guard { host.onOpenTool("P3", inputs) }
                                ViewerModeId.MORE -> moreSheet = true
                            }
                        })
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable { panelOpen = !panelOpen }.padding(horizontal = 12.dp)
                                    .semantics { contentDescription = "Contents and pages" },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(ForgeIcons.Bookmark, null, tint = c.text, modifier = Modifier.size(20.dp))
                                Text("Contents", style = ForgeType.Label, color = c.text)
                            }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PagePill(currentPage + 1, pageCount, Modifier) { jumpDialog = true } }
                            Spacer(Modifier.width(112.dp))
                        }
                      }
                    }
                    Fade(!barsOn && recentlyScrolled) {
                        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                            PagePill(currentPage + 1, pageCount, Modifier) { jumpDialog = true }
                        }
                    }
                }
            }
            if (landscape && panelOpen) OutlineSidePanel(onClose = { panelOpen = false }) {
                OutlinePanel(outline, thumbs, pageCount, currentPage, onJump = { jumpTo(it) })
            }
        }
        if (!landscape && panelOpen) OutlineSheet(onDismiss = { panelOpen = false }) {
            OutlinePanel(outline, thumbs, pageCount, currentPage, onJump = { jumpTo(it); panelOpen = false })
        }
    }
    guarded?.let { action ->
        UnsavedChangesDialog(
            busy = saving,
            onSave = { requestSave { ok -> if (ok) { guarded = null; action() } } },
            onDiscard = { vm.discardAnnotations(); guarded = null; action() },
            onCancel = { guarded = null },
        )
    }
    if (saveOptionsOpen) {
        val done = saveOptions
        val copyOnly = permissionProblem != null && fillDirty
        SaveOptionsSheet(
            showForm = fillState.overrides.isNotEmpty() && fillForm.hasAcroForm, showSignatures = fillState.hasSignatures, copyOnlyReason = if (copyOnly) permissionProblem else null,
            onSave = { opts -> saveOptionsOpen = false; saveOptions = null; vm.saveAnnotations(opts) { ok -> done?.invoke(ok) } },
            onCancel = { saveOptionsOpen = false; saveOptions = null },
        )
    }
    if (whereOpen) SaveWhereSheet(
        fileName = vm.fileName, temporary = origin?.temporary == true,
        onDownloads = {
            whereOpen = false; val d = whereDone; whereDone = null
            vm.saveTarget = com.namanbansal.forge.core.pdf.annot.SaveTarget.Downloads("Saved to Downloads/Forge; the original was left as it was.")
            proceedSave(d ?: {})
        },
        onSaveAs = { whereOpen = false; pickPurposeEdits = true; savePicker.launch(vm.fileName.substringBeforeLast('.') + "_edited.pdf") },
        onCancel = { whereOpen = false; whereDone?.invoke(false); whereDone = null },
    )
    fileAction?.let { a -> ChangesChoiceDialog(a, onWithChanges = { fileAction = null; runFileAction(a, true) }, onOriginal = { fileAction = null; runFileAction(a, false) }, onCancel = { fileAction = null }) }
    if (infoOpen) {
        val f = inputs.firstOrNull()
        val org = origin ?: com.namanbansal.forge.core.share.FileOrigin(false, "This phone", "On this phone")
        val rows = com.namanbansal.forge.core.share.FileInfoRows.build(
            f?.name ?: title, f?.size ?: -1L, org, savedTo?.let { "In ${com.namanbansal.forge.core.OutputSaver.FOLDER_LABEL} as $it" } ?: com.namanbansal.forge.core.share.FileInfoRows.savedWhere(org), pdfInfo,
        )
        FileInfoSheet(rows, onDismiss = { infoOpen = false })
    }
    if (sharePagesOpen) SharePagesSheet(
        thumbs, pageCount, currentPage,
        onSharePdf = { pages -> sharePagesOpen = false; scope.launch { shareStaged(vm.stagePages(pages, false), "application/pdf") } },
        onShareImage = { page -> sharePagesOpen = false; scope.launch { shareStaged(vm.stagePages(listOf(page), true), "image/png") } },
        onDismiss = { sharePagesOpen = false },
    )
    FillSignHost(vm)
    editingId?.let { id ->
        val item = editState.items.firstOrNull { it.id == id }
        if (item == null) vm.edit.closeTextEdit(null)
        else TextBoxSheet(item.text, onSave = { vm.edit.closeTextEdit(it) }, onCancel = { vm.edit.closeTextEdit(null) })
    }
    if (redactSearch.open) RedactSearchSheet(
        redactSearch, onPreset = { vm.edit.runSearch(it, null) }, onTerm = { vm.edit.runSearch(null, it) }, onToggle = vm.edit::toggleHit,
        onAll = vm.edit::setAllHits, onMark = vm.edit::markHits, onClose = vm.edit::closeSearch,
    )
    if (applyConfirm) ApplyRedactionsDialog(
        marks = editState.marks.size, pages = editState.marks.map { it.page }.distinct().size, removeInfo = removeInfo, onRemoveInfo = vm.edit::setRemoveInfo, busy = saving,
        onApply = { val cb = applyDone; vm.saveAnnotations(redact = com.namanbansal.forge.core.pdf.edit.RedactOptions(removeInfo)) { ok -> vm.edit.cancelApply(); applyDone = null; cb?.invoke(ok) } },
        onCancel = { vm.edit.cancelApply(); applyDone = null },
    )
    if (moreSheet) MoreToolsSheet(onDismiss = { moreSheet = false }) { code -> moreSheet = false; guard { host.onOpenTool(code, inputs) } }
    editingNote?.let { id ->
        val n = annots.firstOrNull { it.id == id }
        if (n != null) NoteEditorSheet(
            initial = n.text, isNew = n.text.isEmpty(),
            onSave = { vm.annotate.saveNote(id, it) }, onCancel = vm.annotate::cancelNote, onDelete = { vm.annotate.deleteNote(id) },
        )
    }
    if (jumpDialog) JumpDialog(pageCount, currentPage + 1, onDismiss = { jumpDialog = false }) { p -> jumpDialog = false; jumpTo(p - 1) }
    pendingUri?.let { url ->
        LinkConfirmDialog(url, onDismiss = { pendingUri = null }) {
            pendingUri = null
            val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (!ok) snack("No app on this phone can open that link.")
        }
    }
}

/** Fade in / out (200 ms) without a layout receiver, so it can be used inside any scope. */
@Composable
private fun Fade(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) { content() }

@Composable
private fun LinkConfirmDialog(url: String, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val c = ForgeTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Open this link?", style = ForgeType.Section, color = c.text) },
        text = { Text(url, style = ForgeType.Body, color = c.text, maxLines = 6, overflow = TextOverflow.Ellipsis) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onOpen) { Text("Open", color = c.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}

/** Overflow menu: layout settings first (they persist for every document), then the tools this PDF can be sent to. */
@Composable
private fun ViewerMenu(
    expanded: Boolean, prefs: ViewerPrefs, onDismiss: () -> Unit, onPrefs: ((ViewerPrefs) -> ViewerPrefs) -> Unit,
    files: FileMenuActions, invert: Boolean, onToggleInvert: () -> Unit, onPickTool: (String) -> Unit,
) {
    val c = ForgeTheme.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = c.surface) { ViewerMenuItems(prefs, onPrefs, onPickTool, files, onDismiss, invert, onToggleInvert) }
}

/** The menu rows, separate from the popup so they can be shown (and screenshotted) on their own. */
@Composable
fun ViewerMenuItems(
    prefs: ViewerPrefs, onPrefs: ((ViewerPrefs) -> ViewerPrefs) -> Unit, onPickTool: (String) -> Unit,
    files: FileMenuActions? = null, onDone: () -> Unit = {}, invert: Boolean? = null, onToggleInvert: () -> Unit = {},
) {
    val c = ForgeTheme.colors
    val tools = remember { ToolRegistry.availableFor(setOf(FileKind.PDF)).filter { it.code != "P40" } }
    @Composable
    fun Choice(label: String, on: Boolean, change: (ViewerPrefs) -> ViewerPrefs) = DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (on) Icon(ForgeIcons.Check, null, tint = c.accent, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
        modifier = Modifier.semantics { stateDescription = if (on) "On" else "Off" },
        onClick = { onPrefs(change) },
    )
    if (files != null) FileMenuItems(files, onDone)
    Text("Scrolling", style = ForgeType.Tiny, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    Choice("Continuous scroll", !prefs.pageByPage) { it.copy(pageByPage = false) }
    Choice("Page by page", prefs.pageByPage) { it.copy(pageByPage = true) }
    Text("Fit", style = ForgeType.Tiny, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    Choice("Fit width", !prefs.fitPage && !prefs.pageByPage) { it.copy(fitPage = false) }
    Choice("Fit page", prefs.fitPage || prefs.pageByPage) { it.copy(fitPage = true) }
    Text("Reading", style = ForgeType.Tiny, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    Choice("Two pages side by side (landscape)", prefs.twoPageLandscape) { it.copy(twoPageLandscape = !it.twoPageLandscape) }
    Choice("Keep screen on", prefs.keepScreenOn) { it.copy(keepScreenOn = !it.keepScreenOn) }
    if (invert != null) DropdownMenuItem(
        text = { Text("Dark pages") },
        leadingIcon = { if (invert) Icon(ForgeIcons.Check, null, tint = c.accent, modifier = Modifier.size(18.dp)) else Icon(ForgeIcons.Moon, null, Modifier.size(18.dp)) },
        modifier = Modifier.semantics { stateDescription = if (invert) "On" else "Off" },
        onClick = onToggleInvert,
    )
    HorizontalDivider(color = c.border)
    Text("Open this PDF in…", style = ForgeType.Tiny, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    tools.forEach { t ->
        DropdownMenuItem(text = { Text(t.shortLabel) }, leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }, onClick = { onPickTool(t.code) })
    }
}

// ---- chrome ------------------------------------------------------------------------------------------------------------

@Composable
private fun PagePill(page: Int, total: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Row(
        modifier.heightIn(min = 48.dp).clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp)
            .semantics { contentDescription = "Page $page of $total. Tap to jump to a page" },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text("$page / $total", style = ForgeType.Label, color = c.text) }
}

@Composable
private fun SearchBar(
    query: String, searching: Boolean, count: Int, current: Int,
    onSubmit: (String) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
    listOpen: Boolean, onToggleList: () -> Unit,
) {
    val c = ForgeTheme.colors
    var text by remember { mutableStateOf(query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(text) { // search while typing, after a short pause
        if (text != query) { delay(350); onSubmit(text) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp))
            .background(c.surface).border(1.5.dp, c.accent, RoundedCornerShape(18.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(ForgeIcons.Search, null, tint = c.text2, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            textStyle = ForgeType.Body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
            modifier = Modifier.weight(1f).focusRequester(focus).padding(horizontal = 6.dp),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (text.isEmpty()) Text("Find in document", style = ForgeType.Body, color = c.text2); inner() } },
        )
        when {
            searching -> CircularProgressIndicator(color = c.accent, trackColor = c.surface2, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            query.isNotBlank() -> Text(
                if (count == 0) "No results" else "${current + 1} of $count", style = ForgeType.Small, color = c.text2,
                modifier = Modifier.clip(CircleShape).clickable(enabled = count > 0, onClick = onToggleList).padding(horizontal = 8.dp, vertical = 12.dp)
                    .semantics { stateDescription = if (listOpen) "List shown" else "List hidden" },
            )
        }
        BarIcon(ForgeIcons.ChevronUp, "Previous result", count > 0, onPrev)
        BarIcon(ForgeIcons.ChevronDown, "Next result", count > 0, onNext)
        BarIcon(ForgeIcons.Close, "Close search", true, onClose)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
}

@Composable
private fun HitList(hits: List<TextHit>, current: Int, modifier: Modifier, onOpen: (Int) -> Unit) {
    val c = ForgeTheme.colors
    LazyColumn(modifier.fillMaxWidth().background(c.surface).border(1.dp, c.border)) {
        itemsIndexed(hits, key = { i, _ -> i }) { i, h ->
            Column(
                Modifier.fillMaxWidth().clickable { onOpen(i) }.background(if (i == current) c.tint else Color.Transparent).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("Page ${h.page + 1}", style = ForgeType.Tiny, color = c.accent)
                Text(snippet(h, c.accent), style = ForgeType.Small, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = c.border)
        }
    }
}

private fun snippet(h: TextHit, accent: Color): AnnotatedString = buildAnnotatedString {
    append("…" + h.before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(h.match) }
    append(h.after + "…")
}

@Composable
private fun JumpDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val c = ForgeTheme.colors
    var text by remember { mutableStateOf(current.toString()) }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..pageCount
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Go to page", style = ForgeType.Section, color = c.text) },
        text = {
            ForgeTextField(
                text, { text = it.filter(Char::isDigit).take(6) }, label = "Page (1-$pageCount)", keyboardType = KeyboardType.Number,
                error = if (!valid && text.isNotEmpty()) "Enter a page from 1 to ${pagesLabel(pageCount).removeSuffix(" pages").removeSuffix(" page")}" else null,
                onDone = { if (valid) onGo(n!!) },
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { if (valid) onGo(n!!) }, enabled = valid) { Text("Go", color = c.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}
