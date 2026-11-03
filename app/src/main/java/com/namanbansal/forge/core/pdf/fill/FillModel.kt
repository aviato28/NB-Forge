package com.namanbansal.forge.core.pdf.fill

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.UndoRedo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ItemKind { TEXT, CHECK, CROSS, SIGNATURE }

/**
 * Something the user placed on a page in Fill & Sign: a text box, a quick check mark / cross, or a signature picture.
 * [rect] is in fractions of the displayed page. A text box's size is [size] (an index into [FillPalette.sizes]); a signature
 * names a library entry in [sigId] and is turned [turns] quarter turns clockwise.
 */
data class FillItem(
    val id: String,
    val page: Int,
    val kind: ItemKind,
    val rect: NormRect,
    val text: String = "",
    val size: Int = 1,
    val color: Int = FillPalette.BLACK,
    val sigId: String = "",
    val turns: Int = 0,
)

object FillPalette {
    const val BLACK = 0xFF111111.toInt()
    const val BLUE = 0xFF1F4FD8.toInt()
    val colors = listOf(BLACK, BLUE)
    val colorNames = listOf("Black", "Blue")
    /** Text sizes (S / M / L) as a fraction of the page width (about 9.5 / 12.5 / 18 pt on A4). */
    val sizes = listOf(0.016f, 0.021f, 0.030f)
    val sizeNames = listOf("S", "M", "L")
}

/** The whole Fill & Sign edit: form values the user changed (by field name) and the things they placed. */
data class FillState(val overrides: Map<String, String> = emptyMap(), val items: List<FillItem> = emptyList()) {
    val isEmpty: Boolean get() = overrides.isEmpty() && items.isEmpty()
    val hasSignatures: Boolean get() = items.any { it.kind == ItemKind.SIGNATURE }
}

/** [FillState] with undo / redo, the same shape as the annotate session. Pure Kotlin. */
class FillSession(initial: FillState = FillState(), private val maxHistory: Int = 100) : UndoRedo {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<FillState> = _state.asStateFlow()
    private val undoStack = ArrayDeque<FillState>()
    private val redoStack = ArrayDeque<FillState>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    private val _dirty = MutableStateFlow(!initial.isEmpty)
    override val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    override val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    val current: FillState get() = _state.value

    private fun set(next: FillState) {
        _state.value = next
        _canUndo.value = undoStack.isNotEmpty(); _canRedo.value = redoStack.isNotEmpty()
        _dirty.value = !next.isEmpty
    }

    private fun commit(next: FillState) {
        if (next == current) return
        undoStack.addLast(current)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        set(next)
    }

    fun setOverrides(o: Map<String, String>) = commit(current.copy(overrides = o))
    fun addItem(i: FillItem) = commit(current.copy(items = current.items + i))
    fun replaceItem(i: FillItem) = commit(current.copy(items = current.items.map { if (it.id == i.id) i else it }))
    fun removeItem(id: String) = commit(current.copy(items = current.items.filter { it.id != id }))
    fun find(id: String): FillItem? = current.items.firstOrNull { it.id == id }

    /** Drops the last committed change without keeping it for redo (used to throw away a text box that was never typed into). */
    fun undoLast() { val prev = undoStack.removeLastOrNull() ?: return; set(prev) }

    override fun undo() { val prev = undoStack.removeLastOrNull() ?: return; redoStack.addLast(current); set(prev) }
    override fun redo() { val next = redoStack.removeLastOrNull() ?: return; undoStack.addLast(current); set(next) }

    /** Starts over (document opened, saved, or changes discarded) with an empty history. */
    fun reset(state: FillState = FillState()) { undoStack.clear(); redoStack.clear(); set(state) }

    private var counter = 0
    fun newId(): String = "i" + java.lang.Long.toString(System.nanoTime(), 36) + counter++
}

/** What the save-options sheet decides. */
data class FillSaveOptions(val flattenForm: Boolean = false, val flattenSignatures: Boolean = false)

/** JSON for pending Fill & Sign changes that must survive process death. */
object FillJson {
    fun encode(s: FillState): String = JSONObject().apply {
        put("overrides", JSONObject().also { o -> s.overrides.forEach { (k, v) -> o.put(k, v) } })
        put("items", JSONArray().also { arr ->
            s.items.forEach { i ->
                arr.put(JSONObject().apply {
                    put("id", i.id); put("page", i.page); put("kind", i.kind.name)
                    put("l", i.rect.left.toDouble()); put("t", i.rect.top.toDouble()); put("r", i.rect.right.toDouble()); put("b", i.rect.bottom.toDouble())
                    put("text", i.text); put("size", i.size); put("color", i.color); put("sig", i.sigId); put("turns", i.turns)
                })
            }
        })
    }.toString()

    fun decode(json: String): FillState = try {
        val o = JSONObject(json)
        val ov = o.getJSONObject("overrides").let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
        val arr = o.getJSONArray("items")
        FillState(ov, (0 until arr.length()).map { k ->
            arr.getJSONObject(k).let { j ->
                FillItem(j.getString("id"), j.getInt("page"), ItemKind.valueOf(j.getString("kind")),
                    NormRect(j.getDouble("l").toFloat(), j.getDouble("t").toFloat(), j.getDouble("r").toFloat(), j.getDouble("b").toFloat()),
                    j.getString("text"), j.getInt("size"), j.getInt("color"), j.getString("sig"), j.getInt("turns"))
            }
        })
    } catch (_: Exception) { FillState() }
}

/** Keeps unsaved Fill & Sign changes on disk (one file per document), like the annotate pending store. */
class FillPendingStore(private val dir: java.io.File) {
    private fun file(key: String) = java.io.File(dir, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".json")
    fun save(key: String, s: FillState) {
        try { dir.mkdirs(); val f = file(key); val tmp = java.io.File(dir, f.name + ".tmp"); tmp.writeText(FillJson.encode(s)); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() } } catch (_: Exception) { /* best effort */ }
    }
    fun load(key: String): FillState? = try { file(key).takeIf { it.exists() }?.readText()?.let { FillJson.decode(it) } } catch (_: Exception) { null }
    fun clear(key: String) { runCatching { file(key).delete() } }
}
