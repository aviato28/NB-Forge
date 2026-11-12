package com.namanbansal.forge.tools.util

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.device.rememberToolPrefs
import com.namanbansal.forge.core.util.Tally
import com.namanbansal.forge.core.util.TallyStore
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Hardware volume keys while a screen that wants them is open; MainActivity forwards key presses here. */
object VolumeKeys {
    @Volatile var handler: ((keyCode: Int) -> Boolean)? = null
}

class TallyViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TallyStore(File(app.filesDir, "tallies.json"))
    private val _list = MutableStateFlow<List<Tally>>(emptyList())
    val list: StateFlow<List<Tally>> = _list.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init { viewModelScope.launch(Dispatchers.IO) { _list.value = store.load().ifEmpty { TallyStore.add(emptyList(), "Counter 1") }; _loaded.value = true } }

    private fun change(f: (List<Tally>) -> List<Tally>) {
        val next = f(_list.value); _list.value = next
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.save(next) } }
    }
    fun add(name: String) = change { TallyStore.add(it, name) }
    fun bump(id: Long, d: Int) = change { TallyStore.bump(it, id, d) }
    fun reset(id: Long) = change { TallyStore.reset(it, id) }
    fun rename(id: Long, n: String) = change { TallyStore.rename(it, id, n) }
    fun step(id: Long, s: Int) = change { TallyStore.setStep(it, id, s) }
    fun remove(id: Long) = change { TallyStore.remove(it, id) }
}

/** U9: several persisted tallies; volume up / down count the selected one while the screen is open. */
@Composable
fun TallyScreen(host: ToolHost, vm: TallyViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val prefs = rememberToolPrefs()
    val haptics = rememberHaptics()
    val list by vm.list.collectAsStateWithLifecycle()
    var useKeys by rememberSaveable { mutableStateOf(prefs.bool("tally.keys", true)) }
    var selected by rememberSaveable { mutableStateOf(-1L) }
    var renaming by rememberSaveable { mutableStateOf(-1L) }
    var draft by rememberSaveable { mutableStateOf("") }
    val target = list.firstOrNull { it.id == selected } ?: list.firstOrNull()
    val currentTarget by rememberUpdatedState(target)
    DisposableEffect(useKeys) {
        if (useKeys) VolumeKeys.handler = { code ->
            val t = currentTarget
            if (t == null) false else { vm.bump(t.id, if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1); haptics.tick(); true }
        }
        onDispose { VolumeKeys.handler = null }
    }
    ToolFrame(host, actions = { RoundIconButton(ForgeIcons.Plus, "Add a counter", { vm.add("") }); Box(Modifier.padding(end = 8.dp)) }) {
        ToggleRow("Volume keys count", useKeys, { useKeys = it; prefs.putBool("tally.keys", it) }, subtitle = "Up adds, down subtracts, for the highlighted counter")
        list.forEach { t ->
            val sel = t.id == target?.id
            ForgeCard(Modifier.fillMaxWidth(), onClick = { selected = t.id }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = ForgeType.Section, color = if (sel) c.accent else c.text, modifier = Modifier.weight(1f).clickable { renaming = t.id; draft = t.name })
                        if (t.step != 1) Text("step ${t.step}", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(end = 8.dp))
                        RoundIconButton(ForgeIcons.Refresh, "Reset ${t.name}", { vm.reset(t.id) })
                        Box(Modifier.padding(4.dp))
                        RoundIconButton(ForgeIcons.Trash, "Delete ${t.name}", { vm.remove(t.id) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        RoundButton(ForgeIcons.Minus, "Subtract from ${t.name}", false) { selected = t.id; vm.bump(t.id, -1); haptics.tick() }
                        Text(t.count.toString(), style = ForgeType.Display.copy(fontSize = androidx.compose.ui.unit.TextUnit(56f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text, modifier = Modifier.semantics { contentDescription = "${t.name} count ${t.count}" })
                        RoundButton(ForgeIcons.Plus, "Add to ${t.name}", true) { selected = t.id; vm.bump(t.id, 1); haptics.tick() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 5, 10).forEach { s -> com.namanbansal.forge.ui.components.OptionChip("+$s", t.step == s, { vm.step(t.id, s) }) }
                    }
                }
            }
        }
        ForgeButton("Add counter", onClick = { vm.add("") }, secondary = true, icon = ForgeIcons.Plus, modifier = Modifier.fillMaxWidth())
    }
    if (renaming >= 0) AlertDialog(
        onDismissRequest = { renaming = -1 }, title = { Text("Rename counter") },
        text = { com.namanbansal.forge.ui.components.ForgeTextField(draft, { draft = it }) },
        confirmButton = { TextButton({ vm.rename(renaming, draft); renaming = -1 }) { Text("Save") } },
        dismissButton = { TextButton({ renaming = -1 }) { Text("Cancel") } },
    )
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, primary: Boolean, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(Modifier.size(64.dp).background(if (primary) c.accent else c.surface2, CircleShape).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (primary) Color.White else c.text, modifier = Modifier.size(28.dp))
    }
}
