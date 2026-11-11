package com.namanbansal.forge.core.device

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Small per-tool settings (calibration offsets, last unit, favourites). Reads are cached by SharedPreferences; writes are async. */
class ToolPrefs(private val sp: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("forge_tools", Context.MODE_PRIVATE))

    fun float(key: String, default: Float): Float = try { sp.getFloat(key, default) } catch (_: ClassCastException) { default }
    fun string(key: String, default: String = ""): String = try { sp.getString(key, default) ?: default } catch (_: ClassCastException) { default }
    fun int(key: String, default: Int): Int = try { sp.getInt(key, default) } catch (_: ClassCastException) { default }
    fun bool(key: String, default: Boolean): Boolean = try { sp.getBoolean(key, default) } catch (_: ClassCastException) { default }
    fun long(key: String, default: Long): Long = try { sp.getLong(key, default) } catch (_: ClassCastException) { default }

    fun putFloat(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    fun putString(key: String, v: String) = sp.edit().putString(key, v).apply()
    fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    fun remove(key: String) = sp.edit().remove(key).apply()

    fun stringSet(key: String): Set<String> = try { sp.getStringSet(key, emptySet())?.toSet() ?: emptySet() } catch (_: ClassCastException) { emptySet() }
    fun putStringSet(key: String, v: Set<String>) = sp.edit().putStringSet(key, v).apply()
}

@Composable
fun rememberToolPrefs(): ToolPrefs {
    val context = LocalContext.current
    return remember(context) { ToolPrefs(context) }
}
