package com.namanbansal.forge.core.pdf.annot

import com.namanbansal.forge.core.pdf.NormRect
import org.json.JSONArray
import org.json.JSONObject

/** A point as fractions (0..1) of the displayed page, origin top-left (rotation already applied). */
data class NormPt(val x: Float, val y: Float)

enum class AnnotType { HIGHLIGHT, UNDERLINE, STRIKEOUT, INK, NOTE }

/** Where an annotation that already lives in the PDF sits, so a save can replace or remove exactly that one. */
data class ExistingRef(val page: Int, val index: Int)

/**
 * One annotation in the editing model, in page-fraction coordinates so it is independent of zoom and page rotation.
 * Text markup carries [quads] (one box per line), ink carries [strokes] and a [width] (fraction of the page width), a note
 * carries [anchor] (top-left of its icon) and [text]. A highlighter pen is simply ink with [opacity] below 1.
 */
data class Annot(
    val id: String,
    val page: Int,
    val type: AnnotType,
    val color: Int,
    val opacity: Float = 1f,
    val quads: List<NormRect> = emptyList(),
    val strokes: List<List<NormPt>> = emptyList(),
    val width: Float = 0f,
    val anchor: NormPt? = null,
    val text: String = "",
    val ref: ExistingRef? = null,
) {
    val isHighlighterInk: Boolean get() = type == AnnotType.INK && opacity < 0.95f
    /** True when this annotation came from the PDF (made by Forge earlier or by another app). */
    val isExisting: Boolean get() = ref != null
}

/** The palette (6 colours, ARGB, opaque) and the defaults every tool starts with. */
object AnnotPalette {
    const val YELLOW = 0xFFFFD60A.toInt()
    const val EMBER = 0xFFFF6A1A.toInt()
    const val RED = 0xFFE5322D.toInt()
    const val BLUE = 0xFF2F6FED.toInt()
    const val GREEN = 0xFF22A05B.toInt()
    const val BLACK = 0xFF111111.toInt()
    val colors = listOf(YELLOW, EMBER, RED, BLUE, GREEN, BLACK)
    val names = mapOf(YELLOW to "Yellow", EMBER to "Ember", RED to "Red", BLUE to "Blue", GREEN to "Green", BLACK to "Black")

    /** Text highlights are drawn with Multiply at this opacity: text stays readable even in a viewer without blend-mode support. */
    const val HIGHLIGHT_OPACITY = 0.55f
    const val HIGHLIGHTER_OPACITY = 0.4f
    /** Pen thickness presets as a fraction of page width (about 1.8 / 3.6 / 7 pt on A4). */
    val thicknesses = listOf(0.003f, 0.006f, 0.012f)
    val thicknessNames = listOf("Thin", "Medium", "Thick")
    /** The highlighter pen is wider than the pen at the same setting. */
    const val HIGHLIGHTER_FACTOR = 3.5f

    /** Note icon size as a fraction of page width. */
    const val NOTE_SIZE = 0.045f

    fun defaultColor(tool: AnnotTool): Int = when (tool) {
        AnnotTool.HIGHLIGHT, AnnotTool.HIGHLIGHTER -> YELLOW
        AnnotTool.UNDERLINE -> RED
        AnnotTool.STRIKEOUT -> RED
        AnnotTool.PEN -> BLUE
        AnnotTool.NOTE -> YELLOW
        else -> YELLOW
    }
}

enum class AnnotTool(val label: String) {
    SELECT("Select"), HIGHLIGHT("Highlight"), UNDERLINE("Underline"), STRIKEOUT("Strikethrough"),
    PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"), NOTE("Note");

    val isTextTool: Boolean get() = this == HIGHLIGHT || this == UNDERLINE || this == STRIKEOUT
    val isFreehand: Boolean get() = this == PEN || this == HIGHLIGHTER
    val hasColor: Boolean get() = this != SELECT && this != ERASER
    val hasThickness: Boolean get() = isFreehand
}

/** JSON for pending changes that must survive process death. */
object AnnotJson {
    fun encode(list: List<Annot>): String = JSONArray().also { arr -> list.forEach { arr.put(toJson(it)) } }.toString()

    fun decode(json: String): List<Annot> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun toJson(a: Annot) = JSONObject().apply {
        put("id", a.id); put("page", a.page); put("type", a.type.name); put("color", a.color)
        put("opacity", a.opacity.toDouble()); put("width", a.width.toDouble()); put("text", a.text)
        put("quads", JSONArray().also { q -> a.quads.forEach { r -> q.put(JSONArray(listOf(r.left, r.top, r.right, r.bottom).map { it.toDouble() })) } })
        put("strokes", JSONArray().also { s -> a.strokes.forEach { st -> s.put(JSONArray().also { p -> st.forEach { pt -> p.put(pt.x.toDouble()); p.put(pt.y.toDouble()) } }) } })
        a.anchor?.let { put("ax", it.x.toDouble()); put("ay", it.y.toDouble()) }
        a.ref?.let { put("rp", it.page); put("ri", it.index) }
    }

    private fun fromJson(o: JSONObject): Annot {
        val quads = o.getJSONArray("quads").let { q -> (0 until q.length()).map { i -> q.getJSONArray(i).let { NormRect(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) } } }
        val strokes = o.getJSONArray("strokes").let { s ->
            (0 until s.length()).map { i -> s.getJSONArray(i).let { p -> (0 until p.length() / 2).map { j -> NormPt(p.getDouble(2 * j).toFloat(), p.getDouble(2 * j + 1).toFloat()) } } }
        }
        return Annot(
            id = o.getString("id"), page = o.getInt("page"), type = AnnotType.valueOf(o.getString("type")), color = o.getInt("color"),
            opacity = o.getDouble("opacity").toFloat(), quads = quads, strokes = strokes, width = o.getDouble("width").toFloat(),
            anchor = if (o.has("ax")) NormPt(o.getDouble("ax").toFloat(), o.getDouble("ay").toFloat()) else null,
            text = o.getString("text"), ref = if (o.has("rp")) ExistingRef(o.getInt("rp"), o.getInt("ri")) else null,
        )
    }
}
