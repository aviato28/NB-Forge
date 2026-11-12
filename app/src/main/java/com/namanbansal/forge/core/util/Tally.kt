package com.namanbansal.forge.core.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Tally(val id: Long, val name: String, val count: Int = 0, val step: Int = 1)

/** Tallies kept in a small JSON file. Every change returns the new list and is written immediately (atomically). */
class TallyStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Tally> = try { if (file.exists()) json.decodeFromString<List<Tally>>(file.readText()) else emptyList() } catch (_: Exception) { emptyList() }

    fun save(list: List<Tally>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        fun add(list: List<Tally>, name: String): List<Tally> = list + Tally((list.maxOfOrNull { it.id } ?: 0) + 1, name.trim().ifEmpty { "Counter ${list.size + 1}" }.take(40))
        fun bump(list: List<Tally>, id: Long, delta: Int): List<Tally> = list.map { if (it.id == id) it.copy(count = (it.count + delta * it.step).coerceIn(0, 999_999_999)) else it }
        fun reset(list: List<Tally>, id: Long): List<Tally> = list.map { if (it.id == id) it.copy(count = 0) else it }
        fun rename(list: List<Tally>, id: Long, name: String): List<Tally> = list.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim().take(40)) else it }
        fun setStep(list: List<Tally>, id: Long, step: Int): List<Tally> = list.map { if (it.id == id) it.copy(step = step.coerceIn(1, 1000)) else it }
        fun remove(list: List<Tally>, id: Long): List<Tally> = list.filterNot { it.id == id }
    }
}
