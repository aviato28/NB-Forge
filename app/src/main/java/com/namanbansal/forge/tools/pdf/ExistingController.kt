package com.namanbansal.forge.tools.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.EditSession
import com.namanbansal.forge.core.pdf.edit.ExistingEdit
import com.namanbansal.forge.core.pdf.edit.ExistingKind
import com.namanbansal.forge.core.pdf.edit.StoredImage
import com.namanbansal.forge.core.pdf.edit.content.Align
import com.namanbansal.forge.core.pdf.edit.content.ExBlock
import com.namanbansal.forge.core.pdf.edit.content.ExImage
import com.namanbansal.forge.core.pdf.edit.content.ExPath
import com.namanbansal.forge.core.pdf.edit.content.PageObjects
import com.namanbansal.forge.core.pdf.edit.content.TextPreview
import com.namanbansal.forge.core.pdf.fill.Placement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Something of the PDF's own content that can be selected: a text block, a picture or a drawing, by page and order in the page. */
sealed class ExRef { abstract val page: Int; abstract val key: Int }
data class ExBlockRef(override val page: Int, override val key: Int) : ExRef()
data class ExImageRef(override val page: Int, override val key: Int) : ExRef()
data class ExPathRef(override val page: Int, override val key: Int) : ExRef()

/** Where tapping something that isn't page text takes the user. */
sealed interface ExRoute {
    data class FillSign(val field: com.namanbansal.forge.core.pdf.form.FormField) : ExRoute
    data object Annotate : ExRoute
}

/** The "Why can't I edit this?" sheet: [title] and [detail] in plain words, plus an optional [route] button. */
class WhyLocked(val title: String, val detail: String, val route: ExRoute? = null, val routeLabel: String = "")

/**
 * The "Edit existing" tool without Compose: finds what is on the page ([load]), selects it, opens the inline editor for a text
 * block, keeps the pending changes in the shared [session] (so undo, redo, the unsaved-changes guard and the pending store all
 * apply), moves / resizes / rotates / replaces / deletes pictures, deletes drawings and handles the "Select area" lasso.
 */
class ExistingController(
    private val session: EditSession,
    private val scope: CoroutineScope,
    private val pageAspect: (Int) -> Float,
    private val load: suspend (Int) -> PageObjects?,
    private val previewOf: suspend (Int, ExBlock, ExistingEdit) -> TextPreview?,
    private val message: (String) -> Unit,
    private val loadDebug: suspend (Int) -> List<com.namanbansal.forge.core.pdf.edit.content.DebugBox> = { emptyList() },
) {
    /** Form fields of a page (text, drop-down and list fields), set by the viewer; tapping one opens Fill & Sign. */
    var formFieldsOf: (Int) -> List<com.namanbansal.forge.core.pdf.form.FormField> = { emptyList() }
    /** Takes the user to another mode (Fill & Sign, Annotate). Set by the viewer. */
    var onRoute: (ExRoute) -> Unit = {}

    private val _why = MutableStateFlow<WhyLocked?>(null)
    val why: StateFlow<WhyLocked?> = _why.asStateFlow()
    fun dismissWhy() { _why.value = null }

    private val _debugOn = MutableStateFlow(false)
    /** Developer overlay (Settings > Developer): tint editable and locked characters. */
    val debugOn: StateFlow<Boolean> = _debugOn.asStateFlow()
    fun setDebug(on: Boolean) { _debugOn.value = on }
    private val _debug = MutableStateFlow<Map<Int, List<com.namanbansal.forge.core.pdf.edit.content.DebugBox>>>(emptyMap())
    val debug: StateFlow<Map<Int, List<com.namanbansal.forge.core.pdf.edit.content.DebugBox>>> = _debug.asStateFlow()
    private val debugLoading = HashSet<Int>()
    fun ensureDebug(p: Int) {
        if (p in _debug.value || !debugLoading.add(p)) return
        scope.launch { _debug.value = _debug.value + (p to loadDebug(p)); debugLoading.remove(p) }
    }

    /** The block being edited has no readable text: the editor starts empty and what is typed replaces it. */
    private val _replaceOnly = MutableStateFlow(false)
    val replaceOnly: StateFlow<Boolean> = _replaceOnly.asStateFlow()

    private val _objects = MutableStateFlow<Map<Int, PageObjects>>(emptyMap())
    val objects: StateFlow<Map<Int, PageObjects>> = _objects.asStateFlow()
    private val loading = HashSet<Int>()

    /** Starts reading page [p] (once); [objects] updates when it is known. */
    fun ensure(p: Int) {
        if (p in _objects.value || !loading.add(p)) return
        scope.launch {
            val o = load(p)
            if (o != null) _objects.value = _objects.value + (p to o)
            loading.remove(p)
        }
    }

    fun hintFor(p: Int): String? = _objects.value[p]?.hint

    // ---- selection --------------------------------------------------------------------------------------------------------

    private val _selection = MutableStateFlow<List<ExRef>>(emptyList())
    val selection: StateFlow<List<ExRef>> = _selection.asStateFlow()
    fun select(r: ExRef?) { _selection.value = listOfNotNull(r); if (r == null) closeEditor(false) else if (_editing.value != r) closeEditor(false) }
    fun clearSelection() { _selection.value = emptyList(); closeEditor(false) }
    val single: ExRef? get() = _selection.value.singleOrNull()

    fun blockOf(r: ExRef): ExBlock? = _objects.value[r.page]?.blocks?.firstOrNull { it.key == r.key }
    fun imageOf(r: ExRef): ExImage? = _objects.value[r.page]?.images?.firstOrNull { it.key == r.key }
    fun pathOf(r: ExRef): ExPath? = _objects.value[r.page]?.paths?.firstOrNull { it.key == r.key }
    fun editOf(r: ExRef): ExistingEdit? = session.findExisting(r.page, when (r) { is ExBlockRef -> ExistingKind.TEXT; is ExImageRef -> ExistingKind.IMAGE; is ExPathRef -> ExistingKind.PATH }, r.key)

    /** Where [r] is drawn now: a picture's new place when it was moved, else where it was. */
    fun rectOf(r: ExRef): NormRect? = when (r) {
        is ExBlockRef -> blockOf(r)?.rect
        is ExImageRef -> liveImageRect(r) ?: editOf(r)?.newRect ?: imageOf(r)?.rect
        is ExPathRef -> pathOf(r)?.rect
    }

    // ---- previews of pending text ------------------------------------------------------------------------------------------

    private val _previews = MutableStateFlow<Map<String, Pair<ExistingEdit, TextPreview>>>(emptyMap())
    /** Re-wrapped text for every pending text edit, by edit id. */
    val previews: StateFlow<Map<String, Pair<ExistingEdit, TextPreview>>> = _previews.asStateFlow()
    private val announce = HashSet<String>()
    private val toldNotice = HashSet<String>()

    private fun refreshPreview(e: ExistingEdit) {
        if (e.kind != ExistingKind.TEXT || e.delete || e.text.isBlank()) { _previews.value = _previews.value - e.id; return }
        if (_previews.value[e.id]?.first == e) return
        scope.launch {
            val block = _objects.value[e.page]?.blocks?.firstOrNull { it.key == e.key } ?: load(e.page)?.also { _objects.value = _objects.value + (e.page to it) }?.blocks?.firstOrNull { it.key == e.key } ?: return@launch
            val p = previewOf(e.page, block, e) ?: return@launch
            if (session.findExisting(e.page, e.kind, e.key) != e) return@launch // changed again meanwhile
            _previews.value = _previews.value + (e.id to (e to p))
            if (announce.remove(e.id)) {
                val tag = "${e.page}:${e.key}"
                if (p.notice != null && toldNotice.add(tag)) message(p.notice)
                if (p.overlapText != null) message("This text now runs into the next paragraph (“${p.overlapText.take(24).trim()}…”). Shorten it or it will overlap.")
            }
        }
    }

    /** Called when the session changes: keeps previews in step with the pending edits. */
    fun onSessionChanged() {
        val live = session.current.existing
        _previews.value = _previews.value.filterKeys { id -> live.any { it.id == id } }
        live.forEach { refreshPreview(it) }
    }

    // ---- the inline editor ---------------------------------------------------------------------------------------------------

    private val _editing = MutableStateFlow<ExBlockRef?>(null)
    /** The text block whose text is being typed. */
    val editing: StateFlow<ExBlockRef?> = _editing.asStateFlow()
    var draft by mutableStateOf("")

    fun openEditor(r: ExBlockRef) {
        val b = blockOf(r) ?: return
        val pending = editOf(r)?.takeIf { !it.delete }
        _replaceOnly.value = b.unreadable && pending == null
        if (_replaceOnly.value) message("Forge can't read this text (the PDF has no text map for its font). Type what it should say: the old characters are removed and your text takes their place.")
        draft = pending?.text ?: if (b.unreadable) "" else b.text
        _selection.value = listOf(r); _editing.value = r
    }

    fun editSelectedText() { (single as? ExBlockRef)?.let { openEditor(it) } }

    /** Closes the inline editor; with [save] the typed text becomes a pending change (shown re-wrapped on the page). */
    fun closeEditor(save: Boolean) {
        val r = _editing.value ?: return
        _editing.value = null
        if (!save) return
        val b = blockOf(r) ?: return
        if (_replaceOnly.value && draft.isBlank()) return // nothing typed: leave the old text alone
        val cur = editOf(r)
        val base = cur ?: newTextEdit(r, b)
        announce += base.id
        commit(r, base.copy(text = draft, delete = false))
    }

    private fun newTextEdit(r: ExBlockRef, b: ExBlock) = ExistingEdit(session.newId(), r.page, ExistingKind.TEXT, r.key, b.rect, text = b.text, origText = b.text)

    /** Stores [e], or forgets it when it changes nothing. */
    private fun commit(r: ExRef, e: ExistingEdit) {
        val noop = e.kind == ExistingKind.TEXT && !e.delete && e.text == e.origText && e.sizePt == 0f && !e.hasColor && e.bold < 0 && e.italic < 0 && e.align < 0
        if (noop) { editOf(r)?.let { session.removeExisting(it.id) }; return }
        session.upsertExisting(e)
    }

    // ---- text style (the toolbar) ----------------------------------------------------------------------------------------------

    /** The look of the selected block now: pending change or the original. */
    class TextLook(val bold: Boolean, val italic: Boolean, val sizePt: Float, val color: Int, val align: Int, val fontName: String)

    fun lookOf(r: ExBlockRef): TextLook? {
        val b = blockOf(r) ?: return null
        val e = editOf(r)
        val raw = b.raw
        return TextLook(
            bold = e?.bold?.takeIf { it >= 0 }?.let { it == 1 } ?: raw.traits.bold, italic = e?.italic?.takeIf { it >= 0 }?.let { it == 1 } ?: raw.traits.italic,
            sizePt = e?.sizePt?.takeIf { it > 0f } ?: raw.size, color = if (e?.hasColor == true) e.color else raw.color, align = e?.align?.takeIf { it >= 0 } ?: raw.align, fontName = raw.fontName,
        )
    }

    private fun styled(change: (ExistingEdit) -> ExistingEdit) {
        val r = single as? ExBlockRef ?: return
        val b = blockOf(r) ?: return
        val base = editOf(r)?.takeIf { !it.delete } ?: newTextEdit(r, b)
        announce += base.id
        commit(r, change(base))
    }

    fun toggleBold() { val l = (single as? ExBlockRef)?.let { lookOf(it) } ?: return; styled { it.copy(bold = if (l.bold) 0 else 1) } }
    fun toggleItalic() { val l = (single as? ExBlockRef)?.let { lookOf(it) } ?: return; styled { it.copy(italic = if (l.italic) 0 else 1) } }
    fun sizeBy(factor: Float) { val l = (single as? ExBlockRef)?.let { lookOf(it) } ?: return; styled { it.copy(sizePt = (l.sizePt * factor).coerceIn(4f, 200f)) } }
    fun setSize(pt: Float) = styled { it.copy(sizePt = pt.coerceIn(4f, 200f)) }
    fun setColor(c: Int) = styled { it.copy(hasColor = true, color = c) }
    fun setAlign(a: Int) = styled { it.copy(align = a) }

    // ---- delete, revert -------------------------------------------------------------------------------------------------------

    private fun deleteEdit(r: ExRef): ExistingEdit? = when (r) {
        is ExBlockRef -> blockOf(r)?.let { b -> (editOf(r) ?: newTextEdit(r, b)).copy(delete = true) }
        is ExImageRef -> imageOf(r)?.let { i -> (editOf(r) ?: ExistingEdit(session.newId(), r.page, ExistingKind.IMAGE, r.key, i.rect)).copy(delete = true) }
        is ExPathRef -> pathOf(r)?.let { p -> ExistingEdit(editOf(r)?.id ?: session.newId(), r.page, ExistingKind.PATH, r.key, p.rect, delete = true) }
    }

    /** Deletes everything selected (one undo step). */
    fun deleteSelected() {
        val list = _selection.value.mapNotNull { deleteEdit(it) }
        closeEditor(false)
        session.upsertExisting(list)
        _selection.value = emptyList()
        if (list.size > 1) message("Deleted ${list.size} objects")
    }

    /** Drops the pending change of the selected object (it goes back to how the file has it). */
    fun revertSelected() {
        _selection.value.forEach { r -> editOf(r)?.let { session.removeExisting(it.id) } }
        closeEditor(false)
    }

    fun hasPending(r: ExRef) = editOf(r) != null

    // ---- pictures ------------------------------------------------------------------------------------------------------------------

    private val _lock = MutableStateFlow(true)
    val lockAspect: StateFlow<Boolean> = _lock.asStateFlow()
    fun setLockAspect(b: Boolean) { _lock.value = b }

    private fun imageBase(r: ExImageRef): ExistingEdit? {
        val i = imageOf(r) ?: return null
        return editOf(r)?.takeIf { !it.delete } ?: ExistingEdit(session.newId(), r.page, ExistingKind.IMAGE, r.key, i.rect, newRect = i.rect)
    }

    private fun applyImage(r: ExImageRef, e: ExistingEdit) {
        val i = imageOf(r)
        val same = i != null && (e.newRect == null || e.newRect == i.rect) && e.turns % 4 == 0 && e.replaceImageId.isEmpty() && !e.delete
        if (same) editOf(r)?.let { session.removeExisting(it.id) } else session.upsertExisting(e)
    }

    fun rotateSelected() {
        val r = single as? ExImageRef ?: return
        val e = imageBase(r) ?: return
        val cur = e.newRect ?: imageOf(r)?.rect ?: return
        applyImage(r, e.copy(newRect = Placement.rotated(cur, pageAspect(r.page)), turns = (e.turns + 1) % 4))
    }

    fun scaleSelected(k: Float) {
        val r = single as? ExImageRef ?: return
        val e = imageBase(r) ?: return
        val cur = e.newRect ?: return
        val w = cur.right - cur.left; val h = cur.bottom - cur.top
        val nk = k.coerceAtLeast(MIN_SIDE / max(w, 0.001f)).coerceAtMost(min((1f - cur.left) / w, (1f - cur.top) / h).coerceAtLeast(0.01f))
        applyImage(r, e.copy(newRect = NormRect(cur.left, cur.top, cur.left + w * nk, cur.top + h * nk)))
    }

    fun replaceSelectedWith(img: StoredImage) {
        val r = single as? ExImageRef ?: return
        val e = imageBase(r) ?: return
        applyImage(r, e.copy(replaceImageId = img.id))
        message("Picture replaced · the placement is kept")
    }

    fun replacementIdOf(r: ExImageRef): String? = editOf(r)?.replaceImageId?.takeIf { it.isNotEmpty() }
    fun turnsOf(r: ExImageRef): Int = editOf(r)?.turns ?: 0

    // ---- lasso ------------------------------------------------------------------------------------------------------------------------

    private val _lasso = MutableStateFlow(false)
    /** "Select area" is on: a drag draws a box and selects what is inside. */
    val lasso: StateFlow<Boolean> = _lasso.asStateFlow()
    fun setLassoQuiet() { _lasso.value = false; liveLasso = null }
    fun setLasso(on: Boolean) { _lasso.value = on; if (on) { clearSelection(); message("Drag a box around what you want to select.") } }
    var liveLasso by mutableStateOf<Pair<Int, NormRect>?>(null)
        private set

    private fun inside(o: NormRect, box: NormRect): Boolean {
        val w = min(o.right, box.right) - max(o.left, box.left); val h = min(o.bottom, box.bottom) - max(o.top, box.top)
        if (w <= 0f || h <= 0f) return false
        val area = max((o.right - o.left) * (o.bottom - o.top), 1e-6f)
        return w * h >= area * 0.6f
    }

    private fun selectInside(page: Int, box: NormRect) {
        val o = _objects.value[page] ?: return
        val list = ArrayList<ExRef>()
        o.blocks.filter { inside(it.rect, box) }.forEach { list += ExBlockRef(page, it.key) }
        o.images.filter { inside(rectOf(ExImageRef(page, it.key)) ?: it.rect, box) }.forEach { list += ExImageRef(page, it.key) }
        o.paths.filter { inside(it.rect, box) }.forEach { list += ExPathRef(page, it.key) }
        _selection.value = list
        _lasso.value = false
        message(if (list.isEmpty()) "Nothing in that area." else "${list.size} selected")
    }

    // ---- touch ------------------------------------------------------------------------------------------------------------------------------

    /** The next picture the user picks replaces the selected one (instead of being added to the page). */
    var awaitingReplacement = false

    private var mode = 0 // 1 move picture, 2 resize picture, 3 lasso
    private var startX = 0f; private var startY = 0f; private var startPage = 0; private var lastX = 0f; private var lastY = 0f
    private var liveImage by mutableStateOf<Triple<Int, Int, NormRect>?>(null)
    private fun liveImageRect(r: ExImageRef): NormRect? = liveImage?.takeIf { it.first == r.page && it.second == r.key }?.third

    fun claimsDrag(): Boolean = _lasso.value || single is ExImageRef

    private fun nearHandle(r: NormRect, hit: PageHit) = hypot((hit.x - r.right) * hit.aspect, hit.y - r.bottom) < 0.05f

    fun claimsAt(hit: PageHit): Boolean {
        if (_lasso.value) return true
        val s = single as? ExImageRef ?: return false
        val rect = rectOf(s) ?: return false
        return s.page == hit.page && (nearHandle(rect, hit) || Placement.contains(rect, hit.x, hit.y, 0.01f))
    }

    fun onStart(hit: PageHit) {
        startX = hit.x; startY = hit.y; startPage = hit.page; lastX = hit.x; lastY = hit.y
        if (_lasso.value) { mode = 3; liveLasso = hit.page to NormRect(hit.x, hit.y, hit.x, hit.y); return }
        val s = single as? ExImageRef ?: return
        val rect = rectOf(s) ?: return
        mode = if (nearHandle(rect, hit)) 2 else 1
        liveImage = Triple(s.page, s.key, rect)
    }

    fun onMove(x: Float, y: Float) {
        when (mode) {
            3 -> liveLasso = startPage to NormRect(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
            1 -> liveImage = liveImage?.let { Triple(it.first, it.second, Placement.moved(it.third, x - lastX, y - lastY)) }
            2 -> liveImage = liveImage?.let { l ->
                val rect = l.third
                val nr = if (_lock.value) Placement.resizeTo(rect, x, y, pageAspect(l.first))
                else NormRect(rect.left, rect.top, x.coerceIn(rect.left + MIN_SIDE, 1f), y.coerceIn(rect.top + MIN_SIDE, 1f))
                Triple(l.first, l.second, nr)
            }
        }
        lastX = x; lastY = y
    }

    fun onEnd() {
        when (mode) {
            3 -> liveLasso?.let { (p, box) -> if (box.right - box.left > 0.01f && box.bottom - box.top > 0.006f) selectInside(p, box) }
            1, 2 -> liveImage?.let { l ->
                val r = ExImageRef(l.first, l.second)
                val e = imageBase(r)
                if (e != null && l.third != (e.newRect ?: imageOf(r)?.rect)) applyImage(r, e.copy(newRect = l.third))
            }
        }
        liveImage = null; liveLasso = null; mode = 0
    }

    fun onCancel() { liveImage = null; liveLasso = null; mode = 0 }

    /** The object under a tap: text first (smallest box), then pictures, then drawings. */
    fun hitTest(hit: PageHit): ExRef? {
        val o = _objects.value[hit.page] ?: return null
        val b = o.blocks.filter { Placement.contains(it.rect, hit.x, hit.y, 0.004f) }.minByOrNull { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }
        if (b != null) return ExBlockRef(hit.page, b.key)
        val i = o.images.lastOrNull { Placement.contains(rectOf(ExImageRef(hit.page, it.key)) ?: it.rect, hit.x, hit.y, 0.004f) }
        if (i != null) return ExImageRef(hit.page, i.key)
        val p = o.paths.filter { Placement.contains(it.rect, hit.x, hit.y, 0.01f) }.minByOrNull { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }
        return p?.let { ExPathRef(hit.page, it.key) }
    }

    /** A tap on something that is not editable page text: routes form fields and comment boxes, explains locked text. */
    private fun explain(hit: PageHit): Boolean {
        val o = _objects.value[hit.page] ?: return false
        val field = formFieldsOf(hit.page).filter { (it.isText || it.kind == com.namanbansal.forge.core.pdf.form.FieldKind.COMBO || it.kind == com.namanbansal.forge.core.pdf.form.FieldKind.LIST) && Placement.contains(it.rect, hit.x, hit.y, 0.004f) }.minByOrNull { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }
        if (field != null) { message("This is a form field: opened in Fill & Sign."); onRoute(ExRoute.FillSign(field)); return true }
        val ft = o.freeText.firstOrNull { Placement.contains(it.rect, hit.x, hit.y, 0.004f) }
        if (ft != null) {
            _why.value = WhyLocked("This is a comment box", "This text belongs to a comment box (a FreeText annotation) that sits on top of the page, not to the page's own text. Annotate mode shows the page's notes, highlights and drawings; changing the words of a text box is not supported yet. You can cover it with Whiteout and add your own text.", ExRoute.Annotate, "Open Annotate")
            return true
        }
        val spot = o.locked.filter { it.rect.right - it.rect.left < 0.999f && Placement.contains(it.rect, hit.x, hit.y, 0.006f) }.minByOrNull { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }
            ?: o.locked.firstOrNull { it.rect.right - it.rect.left >= 0.999f }
        if (spot != null) { _why.value = WhyLocked(spot.reason.title, spot.reason.detail); return true }
        return false
    }

    /** A tap. Returns true when used. */
    fun onTap(hit: PageHit): Boolean {
        ensure(hit.page)
        if (_editing.value != null) { closeEditor(true); return true }
        val t = hitTest(hit)
        if (t == null) {
            if (_selection.value.isNotEmpty()) { clearSelection(); return true }
            return explain(hit)
        }
        if (t is ExBlockRef && single == t) { openEditor(t); return true }
        _selection.value = listOf(t)
        return true
    }

    fun reset() {
        _objects.value = emptyMap(); _debug.value = emptyMap(); debugLoading.clear(); _why.value = null; loading.clear(); _previews.value = emptyMap(); _selection.value = emptyList(); _editing.value = null
        liveImage = null; liveLasso = null; mode = 0; _lasso.value = false; announce.clear(); toldNotice.clear()
    }

    companion object { const val MIN_SIDE = 0.02f }
}
