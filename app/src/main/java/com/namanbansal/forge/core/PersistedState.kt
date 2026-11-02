package com.namanbansal.forge.core

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A piece of tool option state (range text, selected pages, a mode...) that is observable as a [StateFlow] and mirrored
 * into the [SavedStateHandle], so it survives process death as well as rotation. Only keep cheap values here.
 * [encode] maps the value to something a Bundle can hold; [decode] maps it back (null = unusable, use the default).
 */
class PersistedState<T : Any>(
    private val saved: SavedStateHandle,
    private val key: String,
    private val default: T,
    private val encode: (T) -> Any,
    decode: (Any) -> T?,
) {
    private val _flow = MutableStateFlow(saved.get<Any>(key)?.let { runCatching { decode(it) }.getOrNull() } ?: default)
    val flow: StateFlow<T> = _flow.asStateFlow()

    var value: T
        get() = _flow.value
        set(v) {
            _flow.value = v
            saved[key] = encode(v)
        }

    fun update(block: (T) -> T) { value = block(value) }

    fun reset() {
        _flow.value = default
        saved.remove<Any>(key)
    }
}
