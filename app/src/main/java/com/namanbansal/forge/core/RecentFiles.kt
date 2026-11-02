package com.namanbansal.forge.core

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

/** One output Forge produced. Sizes are bytes (-1 unknown). */
@Serializable
data class RecentEntry(
    val name: String,
    val toolCode: String,
    val beforeSize: Long,
    val afterSize: Long,
    val timestamp: Long,
    val uri: String,
    val mime: String,
)

@Serializable
private data class RecentFile(val version: Int = 1, val entries: List<RecentEntry> = emptyList())

/**
 * Persistent, newest-first log of outputs, stored as a small JSON file (atomic temp-file + rename writes).
 * Corrupt or missing files simply start an empty log. Call [load] once before use.
 */
class RecentFilesStore(private val file: File, private val max: Int = MAX_ENTRIES) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _entries = MutableStateFlow<List<RecentEntry>>(emptyList())
    val entries: StateFlow<List<RecentEntry>> = _entries.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(Dispatchers.IO) {
            _entries.value = try {
                if (file.exists()) json.decodeFromString<RecentFile>(file.readText()).entries else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Adds [entry] at the top; an older entry for the same uri is replaced. */
    suspend fun add(entry: RecentEntry) = update { list -> (listOf(entry) + list.filterNot { it.uri == entry.uri }).take(max) }

    suspend fun remove(uri: String) = update { list -> list.filterNot { it.uri == uri } }

    suspend fun clear() = update { emptyList() }

    private suspend fun update(transform: (List<RecentEntry>) -> List<RecentEntry>) = lock.withLock {
        val next = transform(_entries.value)
        withContext(Dispatchers.IO) { persist(next) }
        _entries.value = next
    }

    private fun persist(list: List<RecentEntry>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(RecentFile(entries = list)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Best effort: losing a history row must never break a finished job.
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
