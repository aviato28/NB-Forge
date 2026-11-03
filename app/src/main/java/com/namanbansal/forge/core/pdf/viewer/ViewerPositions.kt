package com.namanbansal.forge.core.pdf.viewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Where you stopped reading one document. [key] identifies the content (see [DocumentKey]); [uri] is how to reopen it. */
@Serializable
data class ViewerPosition(
    val key: String,
    val uri: String,
    val name: String,
    val size: Long,
    val pageCount: Int,
    val page: Int,
    val zoom: Float,
    val lastOpened: Long,
)

@Serializable
private data class PositionFile(val version: Int = 1, val entries: List<ViewerPosition> = emptyList())

/** Identity of a document independent of which uri it arrived through (Gmail hands out a new uri every time). */
object DocumentKey {
    /** SHA-1 of the first 64 KB plus the length. */
    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            md.update(buf, 0, n)
        }
        md.update(file.length().toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Newest-first list of documents read in the viewer, capped at [max] (default 200), stored as a small JSON file with atomic
 * writes. Covers (first-page thumbnails for the home screen) live in [coverDir] and are deleted with their entry.
 */
class ViewerPositionStore(private val file: File, private val coverDir: File, private val max: Int = MAX_ENTRIES) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _entries = MutableStateFlow<List<ViewerPosition>>(emptyList())
    val entries: StateFlow<List<ViewerPosition>> = _entries.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            _entries.value = try {
                if (file.exists()) json.decodeFromString<PositionFile>(file.readText()).entries.take(max) else emptyList()
            } catch (_: Exception) { emptyList() }
        }
    }

    fun get(key: String): ViewerPosition? = _entries.value.firstOrNull { it.key == key }

    fun coverFile(key: String) = File(coverDir, "$key.jpg")

    /** Records [position] at the top (replacing the same document). */
    suspend fun put(position: ViewerPosition) = update { list -> listOf(position) + list.filterNot { it.key == position.key } }

    suspend fun remove(key: String) = update { list -> list.filterNot { it.key == key } }

    private suspend fun update(transform: (List<ViewerPosition>) -> List<ViewerPosition>) = lock.withLock {
        val next = transform(_entries.value).take(max)
        val dropped = _entries.value.map { it.key } - next.map { it.key }.toSet()
        withContext(Dispatchers.IO) {
            persist(next)
            dropped.forEach { runCatching { coverFile(it).delete() } }
        }
        _entries.value = next
    }

    private fun persist(list: List<ViewerPosition>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(PositionFile(entries = list)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { /* remembering a position is best effort */ }
    }

    companion object { const val MAX_ENTRIES = 200 }
}
