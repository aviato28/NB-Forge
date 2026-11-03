package com.namanbansal.forge.core.pdf.sign

import android.graphics.BitmapFactory
import com.namanbansal.forge.core.ToolException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class SigKind { SIGNATURE, INITIALS }

/** One saved signature (or the initials). [aspect] is width / height of its picture. */
data class SigEntry(val id: String, val kind: SigKind, val label: String, val aspect: Float, val createdAt: Long)

/**
 * The user's signatures, stored privately in the app's files (never in Downloads): a PNG per entry plus an index. Up to
 * [MAX_SIGNATURES] signatures and one set of initials.
 */
class SignatureLibrary(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val index get() = File(dir, "index.json")

    fun list(): List<SigEntry> = try {
        val arr = JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            SigEntry(it.getString("id"), SigKind.valueOf(it.getString("kind")), it.getString("label"), it.getDouble("aspect").toFloat(), it.getLong("at"))
        }.filter { File(dir, "${it.id}.png").exists() }
    } catch (_: Exception) { emptyList() }

    val signatures: List<SigEntry> get() = list().filter { it.kind == SigKind.SIGNATURE }
    val initials: SigEntry? get() = list().firstOrNull { it.kind == SigKind.INITIALS }

    fun canAdd(kind: SigKind): Boolean = when (kind) {
        SigKind.SIGNATURE -> signatures.size < MAX_SIGNATURES
        SigKind.INITIALS -> true // adding initials replaces the old ones
    }

    /** Saves [png]. Initials replace the previous initials; a sixth signature is refused with a friendly message. */
    @Synchronized fun add(kind: SigKind, png: ByteArray, label: String = ""): SigEntry {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw ToolException("That picture couldn't be read.")
        if (kind == SigKind.SIGNATURE && !canAdd(kind)) throw ToolException("You can keep up to $MAX_SIGNATURES signatures. Delete one to add another.")
        if (kind == SigKind.INITIALS) initials?.let { delete(it.id) }
        dir.mkdirs()
        val id = "s" + java.lang.Long.toString(clock(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)
        File(dir, "$id.png").writeBytes(png)
        val e = SigEntry(id, kind, label.ifBlank { if (kind == SigKind.INITIALS) "Initials" else "Signature" }, opts.outWidth.toFloat() / opts.outHeight, clock())
        write(list() + e)
        return e
    }

    fun png(id: String): ByteArray? = try { File(dir, "$id.png").takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }

    @Synchronized fun delete(id: String) {
        runCatching { File(dir, "$id.png").delete() }
        write(list().filter { it.id != id })
    }

    @Synchronized fun rename(id: String, label: String) = write(list().map { if (it.id == id) it.copy(label = label.trim().ifBlank { it.label }) else it })

    private fun write(list: List<SigEntry>) {
        dir.mkdirs()
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("label", it.label).put("aspect", it.aspect.toDouble()).put("at", it.createdAt)) }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) { index.writeText(tmp.readText()); tmp.delete() }
    }

    companion object { const val MAX_SIGNATURES = 5 }
}
