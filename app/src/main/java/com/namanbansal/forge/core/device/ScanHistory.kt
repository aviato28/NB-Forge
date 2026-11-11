package com.namanbansal.forge.core.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ScanEntry(val raw: String, val format: String, val at: Long)

/** The last [MAX] scans, newest first, kept in a small JSON file. Not thread-safe: call from one coroutine (IO). */
class ScanHistoryStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<ScanEntry> = try {
        if (file.exists()) json.decodeFromString<List<ScanEntry>>(file.readText()) else emptyList()
    } catch (_: Exception) { emptyList() }

    /** Adds [raw] on top; an older identical scan is replaced rather than duplicated. */
    fun add(raw: String, format: String): List<ScanEntry> {
        val list = (listOf(ScanEntry(raw, format, clock())) + load().filterNot { it.raw == raw }).take(MAX)
        write(list)
        return list
    }

    fun remove(entry: ScanEntry): List<ScanEntry> = load().filterNot { it == entry }.also(::write)
    fun clear() { write(emptyList()) }

    private fun write(list: List<ScanEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object { const val MAX = 100 }
}
