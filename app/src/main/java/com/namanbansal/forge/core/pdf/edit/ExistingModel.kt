package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.pdf.NormRect
import org.json.JSONObject

enum class ExistingKind { TEXT, IMAGE, PATH }

/**
 * A change to something that is already in the PDF (Edit existing). [key] identifies the object on [page] by its order in the
 * content stream (first character of a text block, picture number, path number), [rect] is where it was (page fractions).
 * TEXT: [text] replaces the block's text ([origText] guards against a changed file), the rest are style changes (0 / -1 = keep).
 * IMAGE: [newRect] + [turns] move / resize / rotate it, [replaceImageId] swaps the picture. [delete] removes the object.
 */
data class ExistingEdit(
    val id: String, val page: Int, val kind: ExistingKind, val key: Int, val rect: NormRect,
    val text: String = "", val origText: String = "",
    val sizePt: Float = 0f, val color: Int = 0, val hasColor: Boolean = false, val bold: Int = -1, val italic: Int = -1, val align: Int = -1,
    val newRect: NormRect? = null, val turns: Int = 0, val replaceImageId: String = "", val delete: Boolean = false,
) {
    /** The same object as [o] (a re-edit replaces the earlier change). */
    fun sameTarget(o: ExistingEdit) = page == o.page && kind == o.kind && key == o.key
}

internal object ExistingJson {
    fun encode(e: ExistingEdit): JSONObject = JSONObject().apply {
        put("id", e.id); put("page", e.page); put("kind", e.kind.name); put("key", e.key)
        put("l", e.rect.left.toDouble()); put("t", e.rect.top.toDouble()); put("r", e.rect.right.toDouble()); put("b", e.rect.bottom.toDouble())
        put("text", e.text); put("orig", e.origText); put("size", e.sizePt.toDouble()); put("color", e.color); put("hasColor", e.hasColor)
        put("bold", e.bold); put("italic", e.italic); put("align", e.align); put("turns", e.turns); put("img", e.replaceImageId); put("del", e.delete)
        e.newRect?.let { put("nl", it.left.toDouble()); put("nt", it.top.toDouble()); put("nr", it.right.toDouble()); put("nb", it.bottom.toDouble()) }
    }

    fun decode(j: JSONObject): ExistingEdit = ExistingEdit(
        j.getString("id"), j.getInt("page"), ExistingKind.valueOf(j.getString("kind")), j.getInt("key"),
        NormRect(j.getDouble("l").toFloat(), j.getDouble("t").toFloat(), j.getDouble("r").toFloat(), j.getDouble("b").toFloat()),
        j.optString("text"), j.optString("orig"), j.optDouble("size", 0.0).toFloat(), j.optInt("color"), j.optBoolean("hasColor"),
        j.optInt("bold", -1), j.optInt("italic", -1), j.optInt("align", -1),
        if (j.has("nl")) NormRect(j.getDouble("nl").toFloat(), j.getDouble("nt").toFloat(), j.getDouble("nr").toFloat(), j.getDouble("nb").toFloat()) else null,
        j.optInt("turns"), j.optString("img"), j.optBoolean("del"),
    )
}
