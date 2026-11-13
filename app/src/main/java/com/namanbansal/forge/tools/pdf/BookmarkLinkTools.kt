package com.namanbansal.forge.tools.pdf

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.BookmarkEntry
import com.namanbansal.forge.core.pdf.HeadingDetector
import com.namanbansal.forge.core.pdf.LinkEdits
import com.namanbansal.forge.core.pdf.LinkInfo
import com.namanbansal.forge.core.pdf.NewLink
import com.namanbansal.forge.core.pdf.PdfBookmarks
import com.namanbansal.forge.core.pdf.PdfLinks
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PageThumb
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.namanbansal.forge.ui.toolkit.PreviewPageRow
import com.namanbansal.forge.ui.toolkit.ReorderableColumn
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** P22 Bookmarks: the outline as an editable flat list with levels. */
class BookmarksViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P22"
    override val action = "bookmarked"
    override val headline get() = "Bookmarks saved"

    private val itemsS = persisted<List<BookmarkEntry>>("bm.items", emptyList(), encode = { PdfBookmarks.encode(it) }, decode = { (it as? String)?.let(PdfBookmarks::decode) })
    private val loadedS = persistedBool("bm.loaded", false)
    val bookmarks: StateFlow<List<BookmarkEntry>> = itemsS.flow
    val loaded: StateFlow<Boolean> = loadedS.flow
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val undo = ArrayDeque<List<BookmarkEntry>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private var pages = 0

    private fun set(list: List<BookmarkEntry>) {
        undo.addLast(itemsS.value); if (undo.size > 30) undo.removeFirst()
        itemsS.value = PdfBookmarks.normalize(list, pages); _canUndo.value = true
    }

    fun undoLast() { undo.removeLastOrNull()?.let { itemsS.value = it }; _canUndo.value = undo.isNotEmpty() }
    fun add(title: String, page: Int, after: Int?) = set(PdfBookmarks.add(itemsS.value, title.trim(), page, after))
    fun update(index: Int, title: String, page: Int) = set(PdfBookmarks.update(itemsS.value, index, title.trim(), page))
    fun delete(index: Int) = set(PdfBookmarks.delete(itemsS.value, index))
    fun indent(index: Int) = set(PdfBookmarks.indent(itemsS.value, index))
    fun outdent(index: Int) = set(PdfBookmarks.outdent(itemsS.value, index))
    fun moveSubtree(index: Int, up: Boolean) = set(PdfBookmarks.moveSubtree(itemsS.value, index, up))
    fun move(from: Int, to: Int) { itemsS.value = PdfBookmarks.move(itemsS.value, from, to, pages) }
    fun dragStarted() { undo.addLast(itemsS.value); _canUndo.value = true }
    fun clearAll() = set(emptyList())

    override fun onPdfReady(source: PdfSource) {
        super.onPdfReady(source)
        pages = source.pageCount
        if (stateRestored && loadedS.value) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { PdfOps.load(source.file).use { PdfBookmarks.read(it) } }.getOrDefault(emptyList()) }
            itemsS.value = r; loadedS.value = true
        }
    }

    /** Replaces the list with headings found by font size; reports if none. */
    fun detectHeadings() {
        val src = source.value ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                val found = withContext(Dispatchers.IO) { runCatching { PdfOps.load(src.file).use { HeadingDetector.detect(it) } }.getOrDefault(emptyList()) }
                if (found.isEmpty()) notify("No headings stood out. Headings are lines in clearly bigger type than the body text.")
                else { set(found); notify("Found ${found.size} headings") }
            } finally { _busy.value = false }
        }
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        PdfBookmarks.write(doc, itemsS.value)
        return if (itemsS.value.isEmpty()) "Bookmarks removed" else "${itemsS.value.size} ${if (itemsS.value.size == 1) "bookmark" else "bookmarks"}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(host: ToolHost, vm: BookmarksViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val list by vm.bookmarks.collectAsStateWithLifecycle(); val loaded by vm.loaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle(); val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) } // index, -1 = new
    PdfToolScreen(host, vm, "Choose a PDF to edit bookmarks", "Save bookmarks", scroll = false, actionEnabled = loaded) { s, thumbs ->
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (list.isEmpty()) "No bookmarks yet" else "${list.size} ${if (list.size == 1) "bookmark" else "bookmarks"}", style = ForgeType.Section, color = c.text, modifier = Modifier.weight(1f))
            if (canUndo) TextAction("Undo", { vm.undoLast() }, icon = ForgeIcons.Undo)
            TextAction("Add", { editing = -1 }, icon = ForgeIcons.Plus)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(if (busy) "Finding headings…" else "Detect headings", { if (!busy) vm.detectHeadings() }, icon = ForgeIcons.Wand, enabled = !busy)
            if (list.isNotEmpty()) TextAction("Clear all", { vm.clearAll() }, icon = ForgeIcons.Trash, danger = true)
        }
        if (list.isEmpty()) {
            InfoNote("Bookmarks make a table of contents in the side panel of most viewers. Tap Add, or let Forge find headings by their size.")
        } else ReorderableColumn(
            items = list.mapIndexed { i, b -> i to b }, key = { it.first }, modifier = Modifier.weight(1f).fillMaxWidth(),
            onMove = vm::move, onDragStart = vm::dragStarted, onDragEnd = {},
        ) { (index, b), _, dragging, handle ->
            ForgeCard(Modifier.fillMaxWidth().padding(start = (b.level * 18).dp).let { if (dragging) it.border(1.5.dp, c.accent, ForgeShapes.Card) else it }) {
                Row(Modifier.clickable { editing = index }.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(handle.size(48.dp).semantics { contentDescription = "Drag to reorder ${b.title}" }, contentAlignment = Alignment.Center) { Icon(ForgeIcons.Grip, null, tint = c.text2, modifier = Modifier.size(22.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Page ${b.page + 1}", style = ForgeType.Small, color = c.text2)
                    }
                    Box(Modifier.size(48.dp).semantics { contentDescription = "Edit ${b.title}" }, contentAlignment = Alignment.Center) { Icon(ForgeIcons.Pencil, null, tint = c.text2, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
    editing?.let { idx ->
        val existing = list.getOrNull(idx)
        val t = vm.thumbnails.collectAsStateWithLifecycle().value
        val count = vm.source.collectAsStateWithLifecycle().value?.pageCount ?: 1
        BookmarkSheet(
            initialTitle = existing?.title ?: "", initialPage = existing?.page ?: 0, pageCount = count, thumbs = t, isNew = existing == null,
            onDismiss = { editing = null },
            onSave = { title, page -> if (existing == null) vm.add(title, page, null) else vm.update(idx, title, page); editing = null },
            onDelete = { vm.delete(idx); editing = null },
            onIndent = if (existing != null && PdfBookmarks.canIndent(list, idx)) ({ vm.indent(idx); editing = null }) else null,
            onOutdent = if (existing != null && existing.level > 0) ({ vm.outdent(idx); editing = null }) else null,
            onUp = if (existing != null) ({ vm.moveSubtree(idx, true); editing = null }) else null,
            onDown = if (existing != null) ({ vm.moveSubtree(idx, false); editing = null }) else null,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(
    initialTitle: String, initialPage: Int, pageCount: Int, thumbs: PdfThumbnails?, isNew: Boolean,
    onDismiss: () -> Unit, onSave: (String, Int) -> Unit, onDelete: () -> Unit,
    onIndent: (() -> Unit)?, onOutdent: (() -> Unit)?, onUp: (() -> Unit)?, onDown: (() -> Unit)?,
) {
    val c = ForgeTheme.colors
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var page by rememberSaveable { mutableStateOf(initialPage.coerceIn(0, pageCount - 1)) }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { state.scrollToItem((page - 1).coerceAtLeast(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(if (isNew) "New bookmark" else "Edit bookmark", style = ForgeType.Section, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
            ForgeTextField(title, { title = it }, label = "Title")
            GroupLabel("Goes to page ${page + 1}")
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pageCount, key = { it }) { p -> PageThumb(p, "${p + 1}", thumbs, Modifier.width(64.dp), selected = p == page, onClick = { page = p }, description = "Page ${p + 1}") }
            }
            if (!isNew) {
                GroupLabel("Position")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAction("Move up", { onUp?.invoke() }, enabled = onUp != null); TextAction("Move down", { onDown?.invoke() }, enabled = onDown != null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAction("Indent", { onIndent?.invoke() }, enabled = onIndent != null); TextAction("Outdent", { onOutdent?.invoke() }, enabled = onOutdent != null)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew) ForgeButton("Delete", onDelete, Modifier.weight(1f), secondary = true)
                ForgeButton("Save", { onSave(title.ifBlank { "Untitled" }, page) }, Modifier.weight(1f), enabled = title.isNotBlank())
            }
        }
    }
}

/** P24 Hyperlinks: list, edit, delete, draw new links, and make plain-text addresses clickable. Edits are queued and saved together. */
class LinksViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P24"
    override val action = "links"
    override val headline get() = "Links updated"

    private val editsS = persisted<LinkEdits>("links.edits", LinkEdits(), encode = { PdfLinks.encodeEdits(it) }, decode = { (it as? String)?.let(PdfLinks::decodeEdits) })
    val edits: StateFlow<LinkEdits> = editsS.flow
    private val _found = MutableStateFlow<List<LinkInfo>?>(null)
    val found: StateFlow<List<LinkInfo>?> = _found.asStateFlow()
    private val _detected = MutableStateFlow<Int?>(null)
    /** How many plain-text addresses are not links yet (null = still looking). */
    val detected: StateFlow<Int?> = _detected.asStateFlow()

    override fun onPdfReady(source: PdfSource) {
        super.onPdfReady(source)
        viewModelScope.launch {
            val (links, auto) = withContext(Dispatchers.IO) {
                runCatching { PdfOps.load(source.file).use { d ->
                    val l = PdfLinks.list(d)
                    val n = PdfLinks.detect(d).count { f -> l.none { it.page == f.page && it.left < f.right && it.right > f.left && it.top < f.bottom && it.bottom > f.top } }
                    l to n
                } }.getOrDefault(emptyList<LinkInfo>() to 0)
            }
            _found.value = links; _detected.value = auto
        }
    }

    override fun onInputsReset() { _found.value = null; _detected.value = null; super.onInputsReset() }

    private fun edit(block: (LinkEdits) -> LinkEdits) { editsS.value = block(editsS.value) }
    fun changeUrl(l: LinkInfo, url: String) = edit { e -> LinkEdits(e.urlChanges + ((l.page to l.index) to url), e.deletes, e.additions, e.autoDetect) }
    fun delete(l: LinkInfo) = edit { e -> LinkEdits(e.urlChanges - (l.page to l.index), e.deletes + (l.page to l.index), e.additions, e.autoDetect) }
    fun restore(l: LinkInfo) = edit { e -> LinkEdits(e.urlChanges, e.deletes - (l.page to l.index), e.additions, e.autoDetect) }
    fun addLink(n: NewLink) = edit { e -> LinkEdits(e.urlChanges, e.deletes, e.additions + n, e.autoDetect) }
    fun removeAddition(i: Int) = edit { e -> LinkEdits(e.urlChanges, e.deletes, e.additions.filterIndexed { k, _ -> k != i }, e.autoDetect) }
    fun setAuto(v: Boolean) = edit { e -> LinkEdits(e.urlChanges, e.deletes, e.additions, v) }

    fun hasChanges(e: LinkEdits = editsS.value) = e.urlChanges.isNotEmpty() || e.deletes.isNotEmpty() || e.additions.isNotEmpty() || e.autoDetect

    override fun validate(source: PdfSource) { if (!hasChanges()) throw ToolException("Make a change first: edit, delete or add a link.") }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val (changed, removed, added) = PdfLinks.apply(doc, editsS.value, ctx)
        return listOfNotNull(changed.takeIf { it > 0 }?.let { "$it edited" }, removed.takeIf { it > 0 }?.let { "$it deleted" }, added.takeIf { it > 0 }?.let { "$it added" }).joinToString(" · ").ifBlank { "No changes" }
    }
}

private fun shortUrl(u: String) = u.removePrefix("https://").removePrefix("http://").let { if (it.length > 48) it.take(47) + "…" else it }

@Composable
fun LinksScreen(host: ToolHost, vm: LinksViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val found by vm.found.collectAsStateWithLifecycle(); val e by vm.edits.collectAsStateWithLifecycle(); val detected by vm.detected.collectAsStateWithLifecycle()
    var editLink by remember { mutableStateOf<LinkInfo?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val changed = vm.hasChanges(e)
    PdfToolScreen(host, vm, "Choose a PDF to edit links", "Save links", actionEnabled = changed, actionHint = if (!changed) "Edit, delete or add a link to save" else null) { s, thumbs ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(found?.let { "${it.size} ${if (it.size == 1) "link" else "links"} in this PDF" } ?: "Looking for links…", style = ForgeType.Section, color = c.text, modifier = Modifier.weight(1f))
            TextAction("Add link", { adding = true }, icon = ForgeIcons.Plus)
        }
        ToggleRow(
            "Make web addresses clickable", e.autoDetect, vm::setAuto, Modifier.padding(vertical = 8.dp),
            subtitle = when { detected == null -> "Looking for addresses in the text…"; detected == 0 -> "No unlinked web or email addresses found."; else -> "$detected unlinked ${if (detected == 1) "address" else "addresses"} found in the text." },
        )
        found?.let { links ->
            if (links.isEmpty() && e.additions.isEmpty()) Text("No links yet. Draw one with Add link.", style = ForgeType.Small, color = c.text2)
            for (l in links) {
                val gone = (l.page to l.index) in e.deletes
                val url = e.urlChanges[l.page to l.index] ?: l.uri
                LinkRow("Page ${l.page + 1}", url?.let(::shortUrl) ?: (l.destPage?.let { "Jumps to page ${it + 1}" } ?: "Other action"), gone, edited = (l.page to l.index) in e.urlChanges,
                    onEdit = { editLink = l }, onDelete = { if (gone) vm.restore(l) else vm.delete(l) }, deleteLabel = if (gone) "Undo" else "Delete")
            }
            e.additions.forEachIndexed { i, n -> LinkRow("Page ${n.page + 1} · new", shortUrl(n.uri), false, edited = false, onEdit = null, onDelete = { vm.removeAddition(i) }, deleteLabel = "Remove") }
        }
        InfoNote("Edits are queued and applied when you save. Only web, email, phone and FTP addresses are allowed.", Modifier.padding(top = 12.dp))
    }
    editLink?.let { l ->
        var text by remember(l) { mutableStateOf(vm.edits.value.urlChanges[l.page to l.index] ?: l.uri.orEmpty()) }
        val bad = text.isNotBlank() && PdfLinks.normalizeUrl(text) == null
        AlertDialog(
            onDismissRequest = { editLink = null }, title = { Text("Edit link on page ${l.page + 1}") },
            text = { ForgeTextField(text, { text = it }, label = "Address", error = if (bad) "Not a usable web, email or phone address" else null, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri) },
            confirmButton = { TextButton(onClick = { vm.changeUrl(l, text); editLink = null }, enabled = text.isNotBlank() && !bad) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editLink = null }) { Text("Cancel") } },
        )
    }
    if (adding) {
        val t = vm.thumbnails.collectAsStateWithLifecycle().value
        val count = vm.source.collectAsStateWithLifecycle().value?.pageCount ?: 1
        AddLinkSheet(count, t, onDismiss = { adding = false }, onAdd = { vm.addLink(it); adding = false })
    }
}

@Composable
private fun LinkRow(where: String, what: String, strike: Boolean, edited: Boolean, onEdit: (() -> Unit)?, onDelete: () -> Unit, deleteLabel: String) {
    val c = ForgeTheme.colors
    ForgeCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(where + if (edited) " · edited" else "", style = ForgeType.Small, color = c.text2)
                Text(what, style = ForgeType.Body, color = if (strike) c.text2 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            }
            if (onEdit != null && !strike) TextAction("Edit", onEdit)
            TextAction(deleteLabel, onDelete, danger = deleteLabel != "Undo")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLinkSheet(pageCount: Int, thumbs: PdfThumbnails?, onDismiss: () -> Unit, onAdd: (NewLink) -> Unit) {
    val c = ForgeTheme.colors
    var page by rememberSaveable { mutableStateOf(0) }
    var url by rememberSaveable { mutableStateOf("") }
    var box by rememberSaveable { mutableStateOf(arrayListOf(200, 300, 700, 380)) }
    val bmp by produceState<Bitmap?>(null, page, thumbs) { value = thumbs?.render(page, 800) }
    val bad = url.isNotBlank() && PdfLinks.normalizeUrl(url) == null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Add a link", style = ForgeType.Section, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
            PreviewPageRow(page, pageCount, { page = it }, label = "Page")
            val b = bmp
            if (b == null) Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = c.accent) }
            else CropCanvas(b, box, { l, t, r, bt -> box = arrayListOf(l, t, r, bt) }, dim = false)
            Text("Drag the box over the text or picture that should open the link.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(vertical = 8.dp))
            ForgeTextField(url, { url = it }, label = "Address", placeholder = "https://example.com", error = if (bad) "Not a usable web, email or phone address" else null, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
            ForgeButton("Add link", { onAdd(NewLink(page, box[0] / 1000f, box[1] / 1000f, box[2] / 1000f, box[3] / 1000f, url.trim())) }, Modifier.fillMaxWidth().padding(vertical = 16.dp), enabled = url.isNotBlank() && !bad)
        }
    }
}
