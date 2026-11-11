package com.namanbansal.forge.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.ForgeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A Recent row plus whether its file can still be opened. */
data class RecentItem(val entry: RecentEntry, val exists: Boolean)

/** Recent outputs for Home and Files. Existence is re-checked off the main thread whenever the list or [refresh] changes. */
class RecentsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ForgeApp
    private val tick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<RecentItem>> = combine(app.recents.entries, tick) { list, _ -> list }
        .map { list -> list.map { RecentItem(it, app.outputSaver.isReadable(Uri.parse(it.uri))) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-check which files still exist (call when a screen appears). */
    fun refresh() { tick.value++ }

    /** Removes the row only; the file itself is never deleted. */
    fun remove(entry: RecentEntry) { viewModelScope.launch { app.recents.remove(entry.uri) } }

    /** Renames the file on disk and in the list; says so when the provider refuses. */
    fun rename(entry: RecentEntry, newName: String) {
        viewModelScope.launch {
            val used = app.outputSaver.rename(Uri.parse(entry.uri), newName)
            if (used == null) _messages.tryEmit("Couldn't rename that file.")
            else { app.recents.rename(entry.uri, used); refresh(); _messages.tryEmit("Renamed to $used") }
        }
    }

    /** Deletes the file itself (the screen has already asked for confirmation) and its row. */
    fun deleteFile(entry: RecentEntry) {
        viewModelScope.launch {
            if (app.outputSaver.deleteFile(Uri.parse(entry.uri))) { app.recents.remove(entry.uri); _messages.tryEmit("Deleted ${entry.name}") }
            else _messages.tryEmit("Couldn't delete ${entry.name}.")
        }
    }

    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for a snackbar. */
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages
}
