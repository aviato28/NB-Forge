package com.namanbansal.forge.tools.util

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.util.Lap
import com.namanbansal.forge.core.util.Phase
import com.namanbansal.forge.core.util.PlanClock
import com.namanbansal.forge.core.util.Pomodoro
import com.namanbansal.forge.core.util.StopwatchState
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.Perms
import com.namanbansal.forge.ui.toolkit.Readout
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stopwatch state survives rotation (elapsed time is based on elapsedRealtime). */
class TimersViewModel : ViewModel() {
    private val _sw = MutableStateFlow(StopwatchState())
    val stopwatch: StateFlow<StopwatchState> = _sw.asStateFlow()
    fun startStop() { val now = SystemClock.elapsedRealtime(); _sw.value = if (_sw.value.running) _sw.value.stop(now) else _sw.value.start(now) }
    fun lap() { _sw.value = _sw.value.lap(SystemClock.elapsedRealtime()) }
    fun reset() { _sw.value = _sw.value.reset() }
}

/** U7: stopwatch with laps, countdown and Pomodoro (both run in a foreground service and ring when done). */
@Composable
fun TimersScreen(host: ToolHost, vm: TimersViewModel = viewModel()) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    ToolFrame(host) {
        Segmented(listOf("Stopwatch", "Countdown", "Pomodoro"), mode, { mode = it })
        when (mode) { 0 -> Stopwatch(vm); 1 -> Countdown(); else -> PomodoroPane() }
    }
}

@Composable
private fun ticker(intervalMs: Long = 30): Long {
    val owner = LocalLifecycleOwner.current
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { now = SystemClock.elapsedRealtime(); delay(intervalMs) } } }
    return now
}

@Composable
private fun Stopwatch(vm: TimersViewModel) {
    val c = ForgeTheme.colors
    val sw by vm.stopwatch.collectAsStateWithLifecycle()
    val now = if (sw.running) ticker() else SystemClock.elapsedRealtime()
    Readout(StopwatchState.format(sw.elapsed(now)), if (sw.running) "Running" else if (sw.accumulatedMs > 0) "Paused" else "Ready", size = 56)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ForgeButton(if (sw.running) "Stop" else if (sw.accumulatedMs > 0) "Resume" else "Start", onClick = vm::startStop, icon = if (sw.running) ForgeIcons.Minus else ForgeIcons.Clock, modifier = Modifier.weight(1f))
        if (sw.running) ForgeButton("Lap", onClick = vm::lap, secondary = true, icon = ForgeIcons.Bookmark, modifier = Modifier.weight(1f))
        else ForgeButton("Reset", onClick = vm::reset, secondary = true, icon = ForgeIcons.Refresh, enabled = sw.accumulatedMs > 0, modifier = Modifier.weight(1f))
    }
    if (sw.laps.isNotEmpty()) {
        val bw = sw.bestWorst()
        ForgeCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                sw.laps.forEachIndexed { i, l: Lap ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("Lap ${l.number}", style = ForgeType.Body, color = c.text2, modifier = Modifier.weight(1f))
                        Text(StopwatchState.format(l.lapMs), style = ForgeType.BodyMedium, color = when (i) { bw?.first -> androidx.compose.ui.graphics.Color(0xFF2FB36A); bw?.second -> androidx.compose.ui.graphics.Color(0xFFE5484D); else -> c.text }, modifier = Modifier.padding(end = 16.dp))
                        Text(StopwatchState.format(l.totalMs), style = ForgeType.Small, color = c.text2)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationNote() {
    val perm = rememberPermission(Perms.NOTIFICATIONS)
    if (!perm.granted && android.os.Build.VERSION.SDK_INT >= 33) {
        InfoNote("Allow notifications so the timer can show its countdown and ring when Forge is in the background.")
        ForgeButton("Allow notifications", onClick = perm::request, secondary = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RunningView(run: TimerRun) {
    val context = LocalContext.current
    val c = ForgeTheme.colors
    val tick = if (run.ringing) 0L else ticker(250)
    val pos = PlanClock.at(run.phases, run.startedAt, System.currentTimeMillis() + tick * 0)
    val phase = run.phases[pos.index]
    if (run.ringing) {
        Readout("00:00", "Time's up", size = 64, emphasised = true)
        ForgeButton("Stop alarm", onClick = { TimerEngine.stop(context) }, icon = ForgeIcons.Mute, modifier = Modifier.fillMaxWidth())
    } else {
        Readout(PlanClock.clock(pos.remainingMs), phase.label.ifBlank { "Counting down" }, size = 64, emphasised = true)
        LinearProgressIndicator(progress = { 1f - pos.remainingMs / (phase.seconds * 1000f) }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.border)
        if (run.phases.size > 1) Text("Phase ${pos.index + 1} of ${run.phases.size}", style = ForgeType.Small, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        ForgeButton("Cancel timer", onClick = { TimerEngine.stop(context) }, secondary = true, icon = ForgeIcons.Close, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Countdown() {
    val context = LocalContext.current
    val run by TimerEngine.run.collectAsStateWithLifecycle()
    var h by rememberSaveable { mutableIntStateOf(0) }; var m by rememberSaveable { mutableIntStateOf(5) }; var s by rememberSaveable { mutableIntStateOf(0) }
    val r = run
    if (r != null && r.phases.size == 1) { RunningView(r); return }
    if (r != null) { InfoNote("A Pomodoro is running. Cancel it on the Pomodoro tab to start a countdown."); return }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 5, 10, 15, 30, 60).forEach { min -> OptionChip(PlanClock.describe(min * 60), false, { h = min / 60; m = min % 60; s = 0 }) }
    }
    ForgeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Unit("Hours", h, 0..23) { h = it }; Unit("Minutes", m, 0..59) { m = it }; Unit("Seconds", s, 0..59) { s = it }
        }
    }
    val total = h * 3600 + m * 60 + s
    NotificationNote()
    ForgeButton("Start ${if (total > 0) PlanClock.describe(total) else "timer"}", enabled = total > 0, onClick = { TimerEngine.start(context, listOf(Phase("Timer", total))) }, icon = ForgeIcons.Clock, modifier = Modifier.fillMaxWidth())
    InfoNote("The timer keeps running in the background and rings an alarm when it ends, even if you leave Forge.")
}

@Composable
private fun Unit(label: String, value: Int, range: IntRange, onChange: (Int) -> kotlin.Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = ForgeType.Small, color = ForgeTheme.colors.text2)
        Stepper(value, range, onChange, label)
    }
}

@Composable
private fun PomodoroPane() {
    val context = LocalContext.current
    val run by TimerEngine.run.collectAsStateWithLifecycle()
    var work by rememberSaveable { mutableIntStateOf(25) }; var short by rememberSaveable { mutableIntStateOf(5) }; var long by rememberSaveable { mutableIntStateOf(15) }; var rounds by rememberSaveable { mutableIntStateOf(4) }
    val r = run
    if (r != null && r.phases.size > 1) { RunningView(r); return }
    if (r != null) { InfoNote("A countdown is running. Cancel it on the Countdown tab first."); return }
    ForgeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple("Focus (min)", work, 1..90), Triple("Short break (min)", short, 1..30), Triple("Long break (min)", long, 1..60), Triple("Rounds", rounds, 1..8)).forEachIndexed { i, (label, v, range) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = ForgeType.BodyMedium, color = ForgeTheme.colors.text, modifier = Modifier.weight(1f))
                    Stepper(v, range, { n -> when (i) { 0 -> work = n; 1 -> short = n; 2 -> long = n; else -> rounds = n } }, label)
                }
            }
        }
    }
    val plan = Pomodoro.plan(work, short, long, rounds)
    Text("${plan.size} phases · ${PlanClock.describe((PlanClock.totalMs(plan) / 1000).toInt())} in total", style = ForgeType.Small, color = ForgeTheme.colors.text2)
    NotificationNote()
    ForgeButton("Start Pomodoro", onClick = { TimerEngine.start(context, plan) }, icon = ForgeIcons.Clock, modifier = Modifier.fillMaxWidth())
}
