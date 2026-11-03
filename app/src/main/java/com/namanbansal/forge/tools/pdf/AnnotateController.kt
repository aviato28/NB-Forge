package com.namanbansal.forge.tools.pdf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotHit
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotSession
import com.namanbansal.forge.core.pdf.annot.AnnotTool
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.core.pdf.annot.NormPt
import com.namanbansal.forge.core.pdf.annot.StrokeMath
import com.namanbansal.forge.core.pdf.annot.TextSnap
import com.namanbansal.forge.core.pdf.viewer.PageText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** Tool colours and pen thickness, remembered between sessions. */
class AnnotPrefs(context: Context?) {
    private val sp = context?.getSharedPreferences("forge_annotate", Context.MODE_PRIVATE)
    fun color(tool: AnnotTool): Int = try { sp?.getInt("color_${tool.name}", AnnotPalette.defaultColor(tool)) } catch (_: Exception) { null } ?: AnnotPalette.defaultColor(tool)
    fun setColor(tool: AnnotTool, c: Int) { try { sp?.edit()?.putInt("color_${tool.name}", c)?.apply() } catch (_: Exception) { } }
    fun thickness(tool: AnnotTool): Int = try { sp?.getInt("thick_${tool.name}", 1) } catch (_: Exception) { null } ?: 1
    fun setThickness(tool: AnnotTool, i: Int) { try { sp?.edit()?.putInt("thick_${tool.name}", i)?.apply() } catch (_: Exception) { } }
}

/** What is being drawn right now, read only by canvases (so a stroke never recomposes anything). */
class LiveAnnot {
    var page = -1
    var type: AnnotType = AnnotType.INK
    var color = 0
    var opacity = 1f
    var width = 0f
    var points: List<NormPt> = emptyList()
    var quads: List<NormRect> = emptyList()
    var erasing: Set<String> = emptySet()
    /** Bumped on every change; canvases read it in their draw block. */
    var revision by mutableIntStateOf(0)
    fun bump() { revision++ }
    fun clear() { page = -1; points = emptyList(); quads = emptyList(); erasing = emptySet(); bump() }
}

/**
 * All the logic of the Annotate mode, without any Compose UI: the current tool, colours, the selected annotation, and the
 * drawing / text-snapping / erasing gestures ([PageInput]). The annotations live in [session] (undo / redo, dirty state).
 */
class AnnotateController(
    private val prefs: AnnotPrefs,
    private val scope: CoroutineScope,
    private val textOf: suspend (Int) -> PageText?,
) : PageInput {
    val session = AnnotSession()
    val live = LiveAnnot()

    private val _tool = MutableStateFlow(AnnotTool.SELECT)
    val tool: StateFlow<AnnotTool> = _tool.asStateFlow()
    private val _colors = MutableStateFlow(AnnotTool.entries.associateWith { prefs.color(it) })
    val colors: StateFlow<Map<AnnotTool, Int>> = _colors.asStateFlow()
    private val _thickness = MutableStateFlow(AnnotTool.entries.associateWith { prefs.thickness(it) })
    val thickness: StateFlow<Map<AnnotTool, Int>> = _thickness.asStateFlow()
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()
    /** The note whose text is being edited (a sheet is open), and whether it was just placed. */
    private val _editingNote = MutableStateFlow<String?>(null)
    val editingNote: StateFlow<String?> = _editingNote.asStateFlow()
    private var freshNote: String? = null

    var stylusSeen = false
        private set

    fun selectTool(t: AnnotTool) { _tool.value = t; _selectedId.value = null; live.clear() }
    fun colorOf(t: AnnotTool): Int = _colors.value[t] ?: AnnotPalette.defaultColor(t)
    fun setColor(t: AnnotTool, c: Int) { prefs.setColor(t, c); _colors.value = _colors.value + (t to c) }
    fun setThickness(t: AnnotTool, i: Int) { prefs.setThickness(t, i); _thickness.value = _thickness.value + (t to i) }

    // ---- selection of an existing annotation ----
    fun select(id: String?) { _selectedId.value = id }
    val selected: Annot? get() = _selectedId.value?.let { session.find(it) }

    fun recolorSelected(color: Int) { selected?.let { session.replace(it.copy(color = color)) } }
    fun deleteSelected() { selected?.let { session.remove(listOf(it.id)); _selectedId.value = null } }
    fun editSelectedNote() { selected?.takeIf { it.type == AnnotType.NOTE }?.let { _editingNote.value = it.id } }

    fun saveNote(id: String, text: String) {
        session.find(id)?.let { session.replace(it.copy(text = text.trim())) }
        freshNote = null; _editingNote.value = null
    }

    /** Closing the note sheet: a note that was just placed and left empty is taken back. */
    fun cancelNote() {
        val id = _editingNote.value
        if (id != null && id == freshNote && session.find(id)?.text.isNullOrEmpty() && session.current.lastOrNull()?.id == id) { session.undo(); _selectedId.value = null }
        freshNote = null; _editingNote.value = null
    }

    fun deleteNote(id: String) { session.remove(listOf(id)); freshNote = null; _editingNote.value = null; _selectedId.value = null }

    /** Called when a document is opened or reloaded; [pending] is unsaved work restored from disk. */
    fun load(baseline: List<Annot>, pending: List<Annot>? = null) {
        session.reset(baseline, pending)
        _selectedId.value = null; _editingNote.value = null; freshNote = null; live.clear()
    }

    // ---- PageInput ----
    override val claimsDrag: Boolean get() = _tool.value.let { it.isTextTool || it.isFreehand || it == AnnotTool.ERASER }
    override val tapIsStroke: Boolean get() = _tool.value.isFreehand

    override fun accepts(stylus: Boolean): Boolean { if (stylus) stylusSeen = true; return stylus || !stylusSeen }

    private var startHit: PageHit? = null
    private var last: NormPt? = null
    private var text: PageText? = null
    private var textJob: Job? = null
    private var points = ArrayList<NormPt>()
    private var erased = LinkedHashSet<String>()

    override fun onStart(hit: PageHit) {
        startHit = hit; last = NormPt(hit.x, hit.y)
        val t = _tool.value
        live.page = hit.page
        when {
            t.isFreehand -> {
                points = arrayListOf(NormPt(hit.x, hit.y))
                live.type = AnnotType.INK; live.color = colorOf(t); live.opacity = if (t == AnnotTool.HIGHLIGHTER) AnnotPalette.HIGHLIGHTER_OPACITY else 1f
                live.width = strokeWidth(t); live.points = points
            }
            t.isTextTool -> {
                text = null
                textJob?.cancel()
                textJob = scope.launch { text = textOf(hit.page); last?.let { updateQuads(it.x, it.y) } }
                live.type = textType(t); live.color = colorOf(t); live.opacity = textOpacity(t); live.quads = emptyList()
            }
            t == AnnotTool.ERASER -> { erased = LinkedHashSet(); live.erasing = emptySet() }
        }
        live.bump()
    }

    private fun strokeWidth(t: AnnotTool): Float {
        val base = AnnotPalette.thicknesses[(_thickness.value[t] ?: 1).coerceIn(0, 2)]
        return if (t == AnnotTool.HIGHLIGHTER) base * AnnotPalette.HIGHLIGHTER_FACTOR else base
    }

    private fun textOpacity(t: AnnotTool) = if (t == AnnotTool.HIGHLIGHT) AnnotPalette.HIGHLIGHT_OPACITY else 1f

    private fun textType(t: AnnotTool) = when (t) { AnnotTool.HIGHLIGHT -> AnnotType.HIGHLIGHT; AnnotTool.UNDERLINE -> AnnotType.UNDERLINE; else -> AnnotType.STRIKEOUT }

    private fun updateQuads(x: Float, y: Float) {
        val pt = text ?: return
        val s = startHit ?: return
        live.quads = TextSnap.quadsBetween(pt, s.x, s.y, x, y).orEmpty()
        live.bump()
    }

    override fun onMove(x: Float, y: Float) {
        val s = startHit ?: return
        val prev = last ?: return
        last = NormPt(x, y)
        val t = _tool.value
        when {
            t.isFreehand -> {
                val p = points.last()
                if (hypot(x - p.x, (y - p.y) / s.aspect) < 0.0012f) return
                points.add(NormPt(x, y)); live.points = points; live.bump()
            }
            t.isTextTool -> updateQuads(x, y)
            t == AnnotTool.ERASER -> {
                val ids = AnnotHit.erased(session.current, s.page, prev.x, prev.y, x, y, s.aspect)
                if (ids.isNotEmpty() && erased.addAll(ids)) { live.erasing = erased.toSet(); live.bump() }
            }
        }
    }

    override fun onEnd() {
        val s = startHit ?: return
        val t = _tool.value
        when {
            t.isFreehand -> {
                val pts = StrokeMath.thin(points, s.aspect)
                session.add(Annot(session.newId(), s.page, AnnotType.INK, colorOf(t), if (t == AnnotTool.HIGHLIGHTER) AnnotPalette.HIGHLIGHTER_OPACITY else 1f, strokes = listOf(pts), width = strokeWidth(t)))
                finish()
            }
            t.isTextTool -> {
                val job = textJob; val end = last ?: NormPt(s.x, s.y)
                finish(keepJob = true)
                scope.launch {
                    job?.join()
                    val q = text?.let { TextSnap.quadsBetween(it, s.x, s.y, end.x, end.y) }
                    if (q != null && q.isNotEmpty()) session.add(Annot(session.newId(), s.page, textType(t), colorOf(t), textOpacity(t), quads = q))
                    live.clear()
                }
            }
            t == AnnotTool.ERASER -> { session.remove(erased); if (_selectedId.value in erased) _selectedId.value = null; finish() }
            else -> finish()
        }
    }

    private fun finish(keepJob: Boolean = false) {
        startHit = null; last = null
        if (!keepJob) { textJob?.cancel(); textJob = null }
        points = ArrayList(); erased = LinkedHashSet()
        if (!keepJob) live.clear()
    }

    override fun onCancel() { finish() }

    override fun onTap(hit: PageHit): Boolean {
        val t = _tool.value
        val under = AnnotHit.topmost(session.current, hit.page, hit.x, hit.y, hit.aspect)
        when (t) {
            AnnotTool.NOTE -> {
                if (under?.type == AnnotType.NOTE) { _selectedId.value = under.id; return true }
                val half = AnnotPalette.NOTE_SIZE / 2
                val n = Annot(session.newId(), hit.page, AnnotType.NOTE, colorOf(AnnotTool.NOTE),
                    anchor = NormPt((hit.x - half).coerceIn(0f, 1f - AnnotPalette.NOTE_SIZE), (hit.y - half * hit.aspect).coerceIn(0f, 1f - AnnotPalette.NOTE_SIZE * hit.aspect)))
                session.add(n); _selectedId.value = n.id; freshNote = n.id; _editingNote.value = n.id
                return true
            }
            AnnotTool.ERASER -> { if (under != null) { session.remove(listOf(under.id)); if (_selectedId.value == under.id) _selectedId.value = null; return true }; return false }
            AnnotTool.PEN, AnnotTool.HIGHLIGHTER -> return false
            else -> {
                if (under != null) { _selectedId.value = under.id; return true }
                if (_selectedId.value != null) { _selectedId.value = null; return true }
                return false
            }
        }
    }
}
