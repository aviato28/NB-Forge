package com.namanbansal.forge.tools.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.EditFont
import com.namanbansal.forge.core.pdf.edit.EditIo
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditObject
import com.namanbansal.forge.core.pdf.edit.EditSession
import com.namanbansal.forge.core.pdf.edit.EditStyle
import com.namanbansal.forge.core.pdf.edit.EditState
import com.namanbansal.forge.core.pdf.edit.RedactHit
import com.namanbansal.forge.core.pdf.edit.RedactMark
import com.namanbansal.forge.core.pdf.edit.RedactPreset
import com.namanbansal.forge.core.pdf.edit.RedactSearch
import com.namanbansal.forge.core.pdf.edit.StoredImage
import com.namanbansal.forge.core.pdf.fill.Placement
import com.namanbansal.forge.core.pdf.fill.TextBoxMath
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.namanbansal.forge.core.pdf.viewer.TextSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The tools of the Edit strip. */
enum class EditTool(val label: String) { EXISTING("Existing"), SELECT("Select"), TEXT("Text"), IMAGE("Image"), SHAPE("Shape"), WHITEOUT("Whiteout"), REDACT("Redact") }

/** How Redact marks are made: by dragging a box, or by selecting text. */
enum class RedactHow { DRAW, TEXT }

/** State of "Search & redact". */
data class RedactSearchUi(
    val open: Boolean = false, val running: Boolean = false, val progress: Float = 0f, val label: String = "",
    val hits: List<RedactHit> = emptyList(), val on: Set<String> = emptySet(), val done: Boolean = false,
)

/**
 * All the logic of Edit mode without Compose: tools, the objects on the pages (select, move, resize, duplicate, bring to front,
 * delete, undo / redo through [session]), shape drawing by drag, redaction marks and "Search & redact". Positions are page
 * fractions, so zoom and rotation never matter here.
 */
class EditController(
    private val pageAspect: (Int) -> Float,
    private val currentPage: () -> Int,
    private val pageCount: () -> Int,
    private val scope: CoroutineScope,
    private val pageText: suspend (Int) -> PageText?,
    private val sampleColor: suspend (Int, Float, Float) -> Int?,
    private val message: (String) -> Unit,
    loadObjects: suspend (Int) -> com.namanbansal.forge.core.pdf.edit.content.PageObjects? = { null },
    previewOf: suspend (Int, com.namanbansal.forge.core.pdf.edit.content.ExBlock, com.namanbansal.forge.core.pdf.edit.ExistingEdit) -> com.namanbansal.forge.core.pdf.edit.content.TextPreview? = { _, _, _ -> null },
    loadDebug: suspend (Int) -> List<com.namanbansal.forge.core.pdf.edit.content.DebugBox> = { emptyList() },
) : PageInput {
    val session = EditSession()
    /** Edit existing: the PDF's own text, pictures and drawings. */
    val existing = ExistingController(session, scope, pageAspect, loadObjects, previewOf, message, loadDebug)
    init { scope.launch { session.state.collect { existing.onSessionChanged() } } }

    private val _tool = MutableStateFlow(EditTool.EXISTING)
    val tool: StateFlow<EditTool> = _tool.asStateFlow()
    fun setTool(t: EditTool) {
        _tool.value = t
        _eyedropper.value = false
        if (t != EditTool.SELECT) _selectedId.value = null
        if (t != EditTool.REDACT) _selectedMark.value = null
        if (t != EditTool.EXISTING) { existing.clearSelection(); existing.setLassoQuiet() }
    }

    private val _redactHow = MutableStateFlow(RedactHow.DRAW)
    val redactHow: StateFlow<RedactHow> = _redactHow.asStateFlow()
    fun setRedactHow(h: RedactHow) { _redactHow.value = h }

    // ---- style: the next object's look, and the selected object's look when there is one --------------------------------

    private val _template = MutableStateFlow(EditObject("", 0, EditKind.RECT, NormRect(0f, 0f, 0f, 0f), fillColor = EditStyle.BLUE, fillOpacity = 0.5f))
    /** The look new objects get (text font / size / colour, stroke, fill). */
    val template: StateFlow<EditObject> = _template.asStateFlow()
    private val _whiteColor = MutableStateFlow(EditStyle.WHITE)
    val whiteColor: StateFlow<Int> = _whiteColor.asStateFlow()
    private val _shapeKind = MutableStateFlow(EditKind.RECT)
    val shapeKind: StateFlow<EditKind> = _shapeKind.asStateFlow()
    fun setShapeKind(k: EditKind) { if (k.isShape) { _shapeKind.value = k; _tool.value = EditTool.SHAPE; _selectedId.value = null } }
    private val _lockAspect = MutableStateFlow(true)
    val lockAspect: StateFlow<Boolean> = _lockAspect.asStateFlow()
    fun setLockAspect(b: Boolean) { _lockAspect.value = b }

    private val sizePinned = HashSet<String>()

    /** Changes a style field of the template and of the selected object. */
    fun style(pinSize: Boolean = false, change: (EditObject) -> EditObject) {
        _template.value = change(_template.value)
        val s = selected() ?: return
        if (pinSize) sizePinned += s.id
        session.replace(refit(change(s)))
    }

    fun setWhiteColor(c: Int) {
        _whiteColor.value = c
        selected()?.takeIf { it.kind == EditKind.WHITEOUT }?.let { session.replace(it.copy(fillColor = c)) }
    }

    // ---- selection ----------------------------------------------------------------------------------------------------------

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()
    fun select(id: String?) { _selectedId.value = id; if (id != null) { _selectedMark.value = null; adoptStyle(session.find(id)) } }
    fun selected(): EditObject? = _selectedId.value?.let { id -> live?.takeIf { it.id == id } ?: session.find(id) }

    /** Selecting an object shows its look in the option rows (and new objects of the same kind follow it). */
    private fun adoptStyle(o: EditObject?) {
        o ?: return
        if (o.kind == EditKind.IMAGE) return
        _template.value = _template.value.copy(
            font = o.font, sizeFrac = o.sizeFrac, bold = o.bold, align = o.align, color = o.color, strokeWidth = o.strokeWidth,
            filled = if (o.kind.isShape) o.filled else _template.value.filled, fillColor = if (o.kind.isShape) o.fillColor else _template.value.fillColor,
            fillOpacity = if (o.kind.isShape) o.fillOpacity else _template.value.fillOpacity,
        )
        if (o.kind == EditKind.WHITEOUT) _whiteColor.value = o.fillColor
    }

    private val _selectedMark = MutableStateFlow<String?>(null)
    val selectedMark: StateFlow<String?> = _selectedMark.asStateFlow()

    fun deleteSelected() {
        selected()?.let { session.remove(it.id); _selectedId.value = null; return }
        _selectedMark.value?.let { session.removeMark(it); _selectedMark.value = null }
    }
    fun duplicateSelected() { _selectedId.value?.let { id -> session.duplicate(id)?.let { _selectedId.value = it } } }
    fun bringToFront() { _selectedId.value?.let { session.bringToFront(it) } }

    fun rotateSelected() {
        val s = selected()?.takeIf { it.kind == EditKind.IMAGE } ?: return
        session.replace(s.copy(rect = Placement.rotated(s.rect, pageAspect(s.page)), turns = (s.turns + 1) % 4))
    }

    /** Points an arrow the other way, or turns a line over. */
    fun flipSelected() {
        val s = selected()?.takeIf { it.kind.isLine } ?: return
        session.replace(s.copy(reverse = !s.reverse))
    }

    /** Resizes the selected object by [k] around its top-left corner (the +/- buttons). */
    fun scaleSelected(k: Float) {
        val s = selected() ?: return
        val w = s.rect.right - s.rect.left; val h = s.rect.bottom - s.rect.top
        val nk = k.coerceAtLeast(MIN_SIDE / max(w, 0.001f)).coerceAtMost(min((1f - s.rect.left) / w, (1f - s.rect.top) / h).coerceAtLeast(0.01f))
        session.replace(s.copy(rect = NormRect(s.rect.left, s.rect.top, s.rect.left + w * nk, s.rect.top + h * nk)))
    }

    // ---- text ---------------------------------------------------------------------------------------------------------------

    private val _editingId = MutableStateFlow<String?>(null)
    /** The text object (text box or whiteout replacement) whose text is being typed. */
    val editingId: StateFlow<String?> = _editingId.asStateFlow()
    private var freshId: String? = null

    fun addTextAt(page: Int, x: Float, y: Float) {
        val t = _template.value
        val rect = TextBoxMath.boxFor("", t.sizeFrac, pageAspect(page), x, y - 0.01f, 0.4f)
        val o = t.copy(id = session.newId(), page = page, kind = EditKind.TEXT, rect = rect, text = "")
        session.add(o); freshId = o.id
        _tool.value = EditTool.SELECT
        _selectedId.value = o.id; _editingId.value = o.id
    }

    fun editSelectedText() { selected()?.takeIf { it.kind == EditKind.TEXT || it.kind == EditKind.WHITEOUT }?.let { _editingId.value = it.id } }

    fun closeTextEdit(save: String?) {
        val id = _editingId.value ?: return
        _editingId.value = null
        val o = session.find(id)
        if (save != null && o != null) {
            if (save.isBlank() && o.kind == EditKind.TEXT) { if (freshId == id) session.undoLast() else session.remove(id); _selectedId.value = null }
            else session.replace(fitText(refit(o.copy(text = save))))
        } else if (freshId == id && o != null && o.kind == EditKind.TEXT) { session.undoLast(); _selectedId.value = null }
        freshId = null
    }

    /** A whiteout's replacement text is sized to its box unless the user picked a size. */
    private fun fitText(o: EditObject): EditObject {
        if (o.kind != EditKind.WHITEOUT || o.text.isBlank() || o.id in sizePinned) return o
        return o.copy(sizeFrac = fitSize(o))
    }

    private fun fitSize(o: EditObject): Float {
        val aspect = pageAspect(o.page).coerceAtLeast(0.2f)
        val w = (o.rect.right - o.rect.left) * 1000f; val h = (o.rect.bottom - o.rect.top) * 1000f / aspect
        return runCatching { EditIo.fitSizeFrac(o.text, o.font, o.bold, w, h, 1000f) }.getOrElse {
            // the PDF fonts aren't loaded (previews, tests): a rough estimate from line height and average glyph width
            val longest = o.text.lines().maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
            min(h / 1.7f, w / (0.52f * longest + 0.5f)) / 1000f
        }.coerceIn(EditStyle.MIN_SIZE, EditStyle.MAX_SIZE)
    }

    /** "Fit": size the selected whiteout's text to its box again. */
    fun fitSelectedText() {
        val s = selected()?.takeIf { it.kind == EditKind.WHITEOUT } ?: return
        sizePinned -= s.id
        session.replace(s.copy(sizeFrac = fitSize(s)))
    }

    /** Text boxes grow to hold their text; other kinds are left alone. */
    private fun refit(o: EditObject): EditObject {
        if (o.kind != EditKind.TEXT) return o
        val need = TextBoxMath.boxFor(o.text, o.sizeFrac, pageAspect(o.page), o.rect.left, o.rect.top, o.rect.right - o.rect.left)
        val h = max(o.rect.bottom - o.rect.top, need.bottom - need.top)
        val top = o.rect.top.coerceIn(0f, max(0f, 1f - h))
        return o.copy(rect = NormRect(o.rect.left, top, o.rect.right, min(1f, top + h)))
    }

    // ---- images --------------------------------------------------------------------------------------------------------------

    fun placeImage(img: StoredImage) {
        val page = currentPage()
        val rect = Placement.centered(img.aspect, pageAspect(page), widthFrac = 0.4f)
        val o = EditObject(session.newId(), page, EditKind.IMAGE, rect, imageId = img.id)
        session.add(o)
        _tool.value = EditTool.SELECT; _selectedId.value = o.id
    }

    // ---- eyedropper -----------------------------------------------------------------------------------------------------------

    private val _eyedropper = MutableStateFlow(false)
    val eyedropper: StateFlow<Boolean> = _eyedropper.asStateFlow()
    fun toggleEyedropper() { _eyedropper.value = !_eyedropper.value; if (_eyedropper.value) message("Tap the page to pick a colour.") }

    // ---- redaction marks and search ------------------------------------------------------------------------------------------

    fun markSelection(sel: TextSelection) {
        scope.launch {
            val t = pageText(sel.page) ?: return@launch
            val marks = t.lineRects(sel.start, sel.end).map { r -> RedactMark(session.newId(), sel.page, pad(r), RedactMark.ORIGIN_TEXT) }
            session.addMarks(marks)
            if (marks.isNotEmpty()) message("Marked ${marks.size} line${if (marks.size == 1) "" else "s"} for redaction")
        }
    }

    private fun pad(r: NormRect, p: Float = 0.001f) = NormRect((r.left - p).coerceAtLeast(0f), (r.top - p).coerceAtLeast(0f), (r.right + p).coerceAtMost(1f), (r.bottom + p).coerceAtMost(1f))

    private val _search = MutableStateFlow(RedactSearchUi())
    val search: StateFlow<RedactSearchUi> = _search.asStateFlow()
    private var searchJob: Job? = null

    fun openSearch() { _search.value = _search.value.copy(open = true) }
    fun closeSearch() { searchJob?.cancel(); _search.value = RedactSearchUi() }

    /** Finds [preset] or [term] on every page, page by page; hits arrive as they are found, all switched on. */
    fun runSearch(preset: RedactPreset?, term: String?) {
        searchJob?.cancel()
        val label = preset?.label ?: "“${term.orEmpty().trim()}”"
        if (preset == null && term.isNullOrBlank()) return
        _search.value = RedactSearchUi(open = true, running = true, label = label)
        searchJob = scope.launch {
            val n = pageCount()
            for (p in 0 until n) {
                val t = pageText(p)
                if (t != null) {
                    val found = RedactSearch.hitsOn(p, t, preset, term)
                    if (found.isNotEmpty()) _search.value = _search.value.let { it.copy(hits = it.hits + found, on = it.on + found.map { h -> h.id }) }
                }
                _search.value = _search.value.copy(progress = (p + 1f) / n)
            }
            _search.value = _search.value.copy(running = false, done = true)
        }
    }

    fun toggleHit(id: String) { _search.value = _search.value.let { it.copy(on = if (id in it.on) it.on - id else it.on + id) } }
    fun setAllHits(on: Boolean) { _search.value = _search.value.let { it.copy(on = if (on) it.hits.map { h -> h.id }.toSet() else emptySet()) } }

    /** Turns the switched-on hits into marks and closes the review. */
    fun markHits() {
        val s = _search.value
        val chosen = s.hits.filter { it.id in s.on }
        val marks = RedactSearch.marksFor(chosen) { session.newId() }
        session.addMarks(marks)
        closeSearch()
        if (chosen.isNotEmpty()) message("Marked ${chosen.size} match${if (chosen.size == 1) "" else "es"} for redaction")
    }

    private val _applyConfirm = MutableStateFlow(false)
    /** The "This permanently removes the content" dialog is showing. */
    val applyConfirm: StateFlow<Boolean> = _applyConfirm.asStateFlow()
    fun askApply() { if (session.current.marks.isNotEmpty()) _applyConfirm.value = true }
    fun cancelApply() { _applyConfirm.value = false }
    private val _removeInfo = MutableStateFlow(false)
    val removeInfo: StateFlow<Boolean> = _removeInfo.asStateFlow()
    fun setRemoveInfo(b: Boolean) { _removeInfo.value = b }

    // ---- touch --------------------------------------------------------------------------------------------------------------------

    var live by mutableStateOf<EditObject?>(null)
        private set
    var liveMark by mutableStateOf<RedactMark?>(null)
        private set
    private var mode = 0 // 1 move, 2 resize, 3 draw a shape / whiteout, 4 draw a mark
    private var startX = 0f; private var startY = 0f; private var startPage = 0
    private var lastX = 0f; private var lastY = 0f

    private val drawing: Boolean get() = _tool.value == EditTool.SHAPE || _tool.value == EditTool.WHITEOUT
    private val marking: Boolean get() = _tool.value == EditTool.REDACT && _redactHow.value == RedactHow.DRAW

    /** The page being viewed (for hints that depend on it). */
    fun currentPageNow(): Int = currentPage()

    private val onExisting: Boolean get() = _tool.value == EditTool.EXISTING

    override val claimsDrag: Boolean get() = if (onExisting) existing.claimsDrag() else drawing || marking || _selectedId.value != null

    override fun claimsAt(hit: PageHit): Boolean {
        if (onExisting) return existing.claimsAt(hit)
        if (drawing || marking) return true
        val s = selected() ?: return false
        return s.page == hit.page && (nearHandle(s, hit) || Placement.contains(s.rect, hit.x, hit.y, 0.01f))
    }

    private fun nearHandle(s: EditObject, hit: PageHit): Boolean {
        val dx = (hit.x - s.rect.right) * hit.aspect; val dy = hit.y - s.rect.bottom
        return hypot(dx, dy) < 0.05f
    }

    override fun onStart(hit: PageHit) {
        if (onExisting) { existing.onStart(hit); return }
        val s = selected()
        lastX = hit.x; lastY = hit.y; startX = hit.x; startY = hit.y; startPage = hit.page
        if (s != null && s.page == hit.page && (nearHandle(s, hit) || Placement.contains(s.rect, hit.x, hit.y, 0.01f))) {
            mode = if (nearHandle(s, hit)) 2 else 1; live = s; return
        }
        if (marking) { mode = 4; liveMark = RedactMark("live", hit.page, NormRect(hit.x, hit.y, hit.x, hit.y)); return }
        if (drawing) {
            mode = 3
            _selectedId.value = null
            live = newObject(hit.page, NormRect(hit.x, hit.y, hit.x, hit.y), hit.x, hit.y, hit.x, hit.y)
        }
    }

    private fun newObject(page: Int, rect: NormRect, x1: Float, y1: Float, x2: Float, y2: Float): EditObject {
        val t = _template.value
        return if (_tool.value == EditTool.WHITEOUT) EditObject(session.newId(), page, EditKind.WHITEOUT, rect, fillColor = _whiteColor.value, color = t.color, font = t.font, bold = t.bold, sizeFrac = t.sizeFrac)
        else t.copy(id = session.newId(), page = page, kind = _shapeKind.value, rect = rect, text = "",
            flip = (x2 - x1) * (y2 - y1) < 0f, reverse = _shapeKind.value.isLine && x2 < x1)
    }

    override fun onMove(x: Float, y: Float) {
        if (onExisting) { existing.onMove(x, y); return }
        when (mode) {
            1, 2 -> {
                val s = live ?: return
                live = if (mode == 2) s.copy(rect = resized(s, x, y)) else s.copy(rect = Placement.moved(s.rect, x - lastX, y - lastY))
            }
            3 -> live = newObject(startPage, NormRect(min(startX, x), min(startY, y), max(startX, x), max(startY, y)), startX, startY, x, y).copy(id = live?.id ?: session.newId())
            4 -> liveMark = RedactMark("live", startPage, NormRect(min(startX, x), min(startY, y), max(startX, x), max(startY, y)))
        }
        lastX = x; lastY = y
    }

    private fun resized(s: EditObject, x: Float, y: Float): NormRect {
        if (s.kind == EditKind.IMAGE && _lockAspect.value) return Placement.resizeTo(s.rect, x, y, pageAspect(s.page))
        return NormRect(s.rect.left, s.rect.top, x.coerceIn(s.rect.left + MIN_SIDE, 1f), y.coerceIn(s.rect.top + MIN_SIDE, 1f))
    }

    override fun onEnd() {
        if (onExisting) { existing.onEnd(); return }
        when (mode) {
            1, 2 -> live?.let { l -> if (session.find(l.id) != l) session.replace(refit(l)) }
            3 -> live?.let { l ->
                val w = l.rect.right - l.rect.left; val h = (l.rect.bottom - l.rect.top)
                val big = if (l.kind.isLine) hypot(w * 0.7f, h) > 0.02f else w > 0.01f && h > 0.005f
                if (big) { session.add(l); _selectedId.value = l.id; adoptStyle(l); _tool.value = EditTool.SELECT }
            }
            4 -> liveMark?.let { m ->
                if (m.rect.right - m.rect.left > 0.004f && m.rect.bottom - m.rect.top > 0.003f) session.addMark(m.copy(id = session.newId()))
            }
        }
        live = null; liveMark = null; mode = 0
    }

    override fun onCancel() { live = null; liveMark = null; mode = 0; existing.onCancel() }

    override fun onTap(hit: PageHit): Boolean {
        if (_eyedropper.value) {
            _eyedropper.value = false
            scope.launch {
                val c = sampleColor(hit.page, hit.x, hit.y)
                if (c == null) message("Couldn't read that colour.") else setWhiteColor(c or 0xFF000000.toInt())
            }
            return true
        }
        if (onExisting) {
            val own = session.current.items.lastOrNull { it.page == hit.page && Placement.contains(it.rect, hit.x, hit.y, 0.004f) }
            if (own != null && existing.editing.value == null) { existing.clearSelection(); select(own.id); _tool.value = EditTool.SELECT; return true }
            return existing.onTap(hit)
        }
        when (_tool.value) {
            EditTool.TEXT -> { addTextAt(hit.page, hit.x, hit.y); return true }
            EditTool.REDACT -> {
                val m = session.current.marks.lastOrNull { it.page == hit.page && Placement.contains(it.rect, hit.x, hit.y, 0.004f) }
                _selectedMark.value = if (m == null || m.id == _selectedMark.value) null else m.id
                return true
            }
            else -> Unit
        }
        val tapped = session.current.items.lastOrNull { it.page == hit.page && Placement.contains(it.rect, hit.x, hit.y, 0.004f) }
        if (tapped != null) {
            if (tapped.id == _selectedId.value && (tapped.kind == EditKind.TEXT)) _editingId.value = tapped.id
            select(tapped.id); _tool.value = EditTool.SELECT
            return true
        }
        if (drawing) { dropDefault(hit); return true }
        if (_selectedId.value != null) { _selectedId.value = null; return true }
        return false
    }

    /** A tap with a shape / whiteout tool puts a default-sized one there. */
    private fun dropDefault(hit: PageHit) {
        val aspect = pageAspect(hit.page)
        val w = 0.3f
        val h = when { _tool.value == EditTool.WHITEOUT -> 0.04f; _shapeKind.value.isLine -> 0.3f * w * aspect; else -> 0.6f * w * aspect }
        val l = (hit.x - w / 2).coerceIn(0f, 1f - w); val t = (hit.y - h / 2).coerceIn(0f, max(0f, 1f - h))
        val o = newObject(hit.page, NormRect(l, t, l + w, t + h), l, t, l + w, t + h)
        session.add(o); _selectedId.value = o.id; adoptStyle(o); _tool.value = EditTool.SELECT
    }

    /** Back to a clean slate (a document opened, saved or changes discarded). */
    fun reset(state: EditState = EditState()) {
        existing.reset()
        session.reset(state)
        existing.onSessionChanged()
        _selectedId.value = null; _selectedMark.value = null; _editingId.value = null; live = null; liveMark = null; freshId = null
        _applyConfirm.value = false; closeSearch(); sizePinned.clear()
    }

    fun usesImage(id: String) = session.current.items.any { it.imageId == id }

    companion object { const val MIN_SIDE = 0.02f }
}
