package com.namanbansal.forge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.namanbansal.forge.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val pins: List<String> = ToolRegistry.DEFAULT_PINS,
    val recentSearches: List<String> = emptyList(),
    val autoCheckUpdates: Boolean = true,
    val betaUpdates: Boolean = false,
    val lastUpdateCheck: Long = 0L,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_settings")

/** All persisted user preferences. Lists are stored as newline-joined strings (codes/queries never contain '\n'). */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val PINS = stringPreferencesKey("pins")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val AUTO_CHECK = booleanPreferencesKey("auto_check_updates")
        val BETA = booleanPreferencesKey("beta_updates")
        val LAST_CHECK = longPreferencesKey("last_update_check")
    }

    private fun String?.toList(): List<String>? = this?.split('\n')?.filter { it.isNotBlank() }

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            pins = Pins.migrate(p[Keys.PINS].toList())?.filter { ToolRegistry.get(it) != null } ?: ToolRegistry.DEFAULT_PINS,
            recentSearches = p[Keys.RECENT_SEARCHES].toList() ?: emptyList(),
            autoCheckUpdates = p[Keys.AUTO_CHECK] ?: true,
            betaUpdates = p[Keys.BETA] ?: false,
            lastUpdateCheck = p[Keys.LAST_CHECK] ?: 0L,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }

    /** Outcome of [togglePin]. */
    enum class PinResult { Pinned, Unpinned, LimitReached }

    suspend fun togglePin(code: String): PinResult {
        var result = PinResult.Unpinned
        store.edit { p ->
            val pins = Pins.migrate(p[Keys.PINS].toList()) ?: ToolRegistry.DEFAULT_PINS
            val next = Pins.toggle(pins, code)
            result = when {
                next == null -> PinResult.LimitReached
                code in next -> PinResult.Pinned
                else -> PinResult.Unpinned
            }
            if (next != null) p[Keys.PINS] = next.joinToString("\n")
        }
        return result
    }

    /** Persist a new pin order (unknown codes dropped, duplicates removed, capped at [ToolRegistry.MAX_PINS]). */
    suspend fun setPins(order: List<String>) = store.edit { p ->
        p[Keys.PINS] = order.filter { ToolRegistry.get(it) != null }.distinct().take(ToolRegistry.MAX_PINS).joinToString("\n")
    }

    suspend fun addRecentSearch(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        store.edit { p ->
            val list = p[Keys.RECENT_SEARCHES].toList() ?: emptyList()
            p[Keys.RECENT_SEARCHES] = (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES).joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() = store.edit { it.remove(Keys.RECENT_SEARCHES) }

    suspend fun setAutoCheck(enabled: Boolean) = store.edit { it[Keys.AUTO_CHECK] = enabled }

    suspend fun setBeta(enabled: Boolean) = store.edit { it[Keys.BETA] = enabled }

    suspend fun setLastUpdateCheck(time: Long) = store.edit { it[Keys.LAST_CHECK] = time }

    companion object {
        const val MAX_RECENT_SEARCHES = 8
    }
}

/** Pure pin-list rules, kept separate from storage so they are unit-testable. */
object Pins {
    /** Stored pins that are exactly the old defaults become the new defaults; anything the user changed is left alone. */
    fun migrate(stored: List<String>?): List<String>? =
        if (stored == ToolRegistry.LEGACY_DEFAULT_PINS) ToolRegistry.DEFAULT_PINS else stored

    /** Toggle [code]; returns null when pinning would exceed [ToolRegistry.MAX_PINS]. */
    fun toggle(pins: List<String>, code: String): List<String>? = when {
        code in pins -> pins - code
        pins.size >= ToolRegistry.MAX_PINS -> null
        else -> pins + code
    }

    /** Move the item at [from] to [to] (both clamped to the list). */
    fun move(pins: List<String>, from: Int, to: Int): List<String> {
        if (from !in pins.indices) return pins
        val target = to.coerceIn(0, pins.lastIndex)
        if (target == from) return pins
        return pins.toMutableList().apply { add(target, removeAt(from)) }
    }
}
