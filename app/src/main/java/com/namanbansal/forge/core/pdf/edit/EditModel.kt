package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.UndoRedo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EditKind { TEXT, IMAGE, RECT, ELLIPSE, LINE, ARROW, WHITEOUT;
    val isShape: Boolean get() = this == RECT || this == ELLIPSE || this == LINE || this == ARROW
    val isLine: Boolean get() = this == LINE || this == ARROW
}

/** The three standard-14 families the text box offers. */
enum class EditFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

/**
 * Something the user added to a page in Edit mode (written into the page content on save). [rect] is in fractions of the displayed
 * page. [color] is the text colour, or the stroke colour of a shape. [sizeFrac] is a text size as a fraction of the page width.
 * Lines and arrows run from the top-left to the bottom-right corner of [rect], or from the bottom-left to the top-right when [flip].
 * A [WHITEOUT][EditKind.WHITEOUT] is a filled box ([fillColor], opaque) whose optional [text] is the replacement text.
 */
data class EditObject(
    val id: String,
    val page: Int,
    val kind: EditKind,
    val rect: NormRect,
    val text: String = "",
    val font: EditFont = EditFont.SANS,
    val sizeFrac: Float = EditStyle.DEFAULT_SIZE,
    val bold: Boolean = false,
    val align: Int = 0,
    val color: Int = EditStyle.BLACK,
    val strokeWidth: Float = EditStyle.DEFAULT_STROKE,
    val filled: Boolean = false,
    val fillColor: Int = EditStyle.WHITE,
    val fillOpacity: Float = 1f,
    val imageId: String = "",
    val turns: Int = 0,
    val flip: Boolean = false,
    /** An arrow's head sits at the start of the line instead of the end. */
    val reverse: Boolean = false,
)

object EditStyle {
    const val BLACK = 0xFF111111.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFD32F2F.toInt()
    const val BLUE = 0xFF1F4FD8.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val ORANGE = 0xFFF57C00.toInt()
    val palette = listOf(BLACK, RED, BLUE, GREEN, ORANGE, WHITE)
    val paletteNames = listOf("Black", "Red", "Blue", "Green", "Orange", "White")
    const val DEFAULT_SIZE = 0.024f
    const val MIN_SIZE = 0.010f
    const val MAX_SIZE = 0.10f
    const val DEFAULT_STROKE = 2f
    val strokeWidths = listOf(1f, 2f, 4f, 8f)
    val alignNames = listOf("Left", "Center", "Right")
}

/** A red box the user marked for true redaction; [origin] says how it was made (for the review list). */
data class RedactMark(val id: String, val page: Int, val rect: NormRect, val origin: String = ORIGIN_DRAG) {
    companion object { const val ORIGIN_DRAG = "drag"; const val ORIGIN_TEXT = "text"; const val ORIGIN_SEARCH = "search" }
}

/** Everything unsaved in Edit mode. Order of [items] is the stacking order (last = in front). */
data class EditState(val items: List<EditObject> = emptyList(), val marks: List<RedactMark> = emptyList(), val existing: List<ExistingEdit> = emptyList()) {
    val isEmpty: Boolean get() = items.isEmpty() && marks.isEmpty() && existing.isEmpty()
}

/** [EditState] with undo / redo, the same shape as the fill and annotate sessions. Pure Kotlin. */
class EditSession(initial: EditState = EditState(), private val maxHistory: Int = 100) : UndoRedo {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<EditState> = _state.asStateFlow()
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    private val _dirty = MutableStateFlow(!initial.isEmpty)
    override val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    override val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    val current: EditState get() = _state.value

    private fun set(next: EditState) {
        _state.value = next
        _canUndo.value = undoStack.isNotEmpty(); _canRedo.value = redoStack.isNotEmpty()
        _dirty.value = !next.isEmpty
    }

    private fun commit(next: EditState) {
        if (next == current) return
        undoStack.addLast(current)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        set(next)
    }

    fun add(o: EditObject) = commit(current.copy(items = current.items + o))
    fun replace(o: EditObject) = commit(current.copy(items = current.items.map { if (it.id == o.id) o else it }))
    fun remove(id: String) = commit(current.copy(items = current.items.filter { it.id != id }))
    fun find(id: String): EditObject? = current.items.firstOrNull { it.id == id }
    fun bringToFront(id: String) {
        val o = find(id) ?: return
        if (current.items.lastOrNull()?.id == id) return
        commit(current.copy(items = current.items.filter { it.id != id } + o))
    }
    /** A copy of [id] a little down and right of the original (kept on the page); returns its id. */
    fun duplicate(id: String): String? {
        val o = find(id) ?: return null
        val w = o.rect.right - o.rect.left; val h = o.rect.bottom - o.rect.top
        val l = (o.rect.left + 0.03f).coerceIn(0f, (1f - w).coerceAtLeast(0f)); val t = (o.rect.top + 0.03f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        val copy = o.copy(id = newId(), rect = NormRect(l, t, l + w, t + h))
        add(copy)
        return copy.id
    }

    /** Adds a change to existing content, replacing an earlier change to the same object. */
    fun upsertExisting(e: ExistingEdit) = commit(current.copy(existing = current.existing.filter { !it.sameTarget(e) } + e))
    fun upsertExisting(list: List<ExistingEdit>) { if (list.isNotEmpty()) commit(current.copy(existing = current.existing.filter { o -> list.none { it.sameTarget(o) } } + list)) }
    fun removeExisting(id: String) = commit(current.copy(existing = current.existing.filter { it.id != id }))
    fun findExisting(page: Int, kind: ExistingKind, key: Int): ExistingEdit? = current.existing.firstOrNull { it.page == page && it.kind == kind && it.key == key }

    fun addMark(m: RedactMark) = commit(current.copy(marks = current.marks + m))
    fun addMarks(ms: List<RedactMark>) { if (ms.isNotEmpty()) commit(current.copy(marks = current.marks + ms)) }
    fun removeMark(id: String) = commit(current.copy(marks = current.marks.filter { it.id != id }))
    fun clearMarks() { if (current.marks.isNotEmpty()) commit(current.copy(marks = emptyList())) }

    /** Drops the last committed change without keeping it for redo (a text box that was never typed into). */
    fun undoLast() { val prev = undoStack.removeLastOrNull() ?: return; set(prev) }

    override fun undo() { val prev = undoStack.removeLastOrNull() ?: return; redoStack.addLast(current); set(prev) }
    override fun redo() { val next = redoStack.removeLastOrNull() ?: return; undoStack.addLast(current); set(next) }

    fun reset(state: EditState = EditState()) { undoStack.clear(); redoStack.clear(); set(state) }

    private var counter = 0
    fun newId(): String = "e" + java.lang.Long.toString(System.nanoTime(), 36) + counter++
}

/** What the save sheet / apply dialog decides for redaction. */
data class RedactOptions(val removeDocumentInfo: Boolean = false)

/** JSON for pending Edit changes that must survive process death. */
object EditJson {
    private fun JSONObject.rect(r: NormRect) { put("l", r.left.toDouble()); put("t", r.top.toDouble()); put("r", r.right.toDouble()); put("b", r.bottom.toDouble()) }
    private fun JSONObject.rect() = NormRect(getDouble("l").toFloat(), getDouble("t").toFloat(), getDouble("r").toFloat(), getDouble("b").toFloat())

    fun encode(s: EditState): String = JSONObject().apply {
        put("items", JSONArray().also { arr ->
            s.items.forEach { i ->
                arr.put(JSONObject().apply {
                    put("id", i.id); put("page", i.page); put("kind", i.kind.name); rect(i.rect)
                    put("text", i.text); put("font", i.font.name); put("size", i.sizeFrac.toDouble()); put("bold", i.bold); put("align", i.align)
                    put("color", i.color); put("sw", i.strokeWidth.toDouble()); put("filled", i.filled); put("fc", i.fillColor); put("fo", i.fillOpacity.toDouble())
                    put("img", i.imageId); put("turns", i.turns); put("flip", i.flip); put("rev", i.reverse)
                })
            }
        })
        put("existing", JSONArray().also { arr -> s.existing.forEach { arr.put(ExistingJson.encode(it)) } })
        put("marks", JSONArray().also { arr ->
            s.marks.forEach { m -> arr.put(JSONObject().apply { put("id", m.id); put("page", m.page); rect(m.rect); put("origin", m.origin) }) }
        })
    }.toString()

    fun decode(json: String): EditState = try {
        val o = JSONObject(json)
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).map { k ->
            arr.getJSONObject(k).let { j ->
                EditObject(
                    j.getString("id"), j.getInt("page"), EditKind.valueOf(j.getString("kind")), j.rect(),
                    j.getString("text"), EditFont.valueOf(j.getString("font")), j.getDouble("size").toFloat(), j.getBoolean("bold"), j.getInt("align"),
                    j.getInt("color"), j.getDouble("sw").toFloat(), j.getBoolean("filled"), j.getInt("fc"), j.getDouble("fo").toFloat(),
                    j.getString("img"), j.getInt("turns"), j.getBoolean("flip"), j.optBoolean("rev", false),
                )
            }
        }
        val ma = o.getJSONArray("marks")
        val marks = (0 until ma.length()).map { k -> ma.getJSONObject(k).let { j -> RedactMark(j.getString("id"), j.getInt("page"), j.rect(), j.getString("origin")) } }
        val ea = o.optJSONArray("existing")
        val existing = if (ea == null) emptyList() else (0 until ea.length()).map { ExistingJson.decode(ea.getJSONObject(it)) }
        EditState(items, marks, existing)
    } catch (_: Exception) { EditState() }
}

/** Keeps unsaved Edit changes on disk (one file per document), like the other pending stores. */
class EditPendingStore(private val dir: File) {
    private fun file(key: String) = File(dir, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".json")
    fun save(key: String, s: EditState) {
        try { dir.mkdirs(); val f = file(key); val tmp = File(dir, f.name + ".tmp"); tmp.writeText(EditJson.encode(s)); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() } } catch (_: Exception) { /* best effort */ }
    }
    fun load(key: String): EditState? = try { file(key).takeIf { it.exists() }?.readText()?.let { EditJson.decode(it) } } catch (_: Exception) { null }
    fun clear(key: String) { runCatching { file(key).delete() } }
}

/** Turns "full_name" / "fullName" / "person.first-name" into "Full name" for field sheets. */
object FieldLabels {
    fun humanise(name: String): String {
        val last = name.replace(Regex("\\[\\d+]$"), "").substringAfterLast('.').ifBlank { name }
        val spaced = last.replace(Regex("[_\\-]+"), " ").replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim().lowercase()
        return if (spaced.isEmpty()) name else spaced.replaceFirstChar { it.uppercase() }
    }
}
