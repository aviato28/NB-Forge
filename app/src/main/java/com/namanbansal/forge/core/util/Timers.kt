package com.namanbansal.forge.core.util

data class Lap(val number: Int, val lapMs: Long, val totalMs: Long)

/** Stopwatch maths with an injectable "now" (SystemClock.elapsedRealtime in the app). Immutable: every action returns a new state. */
data class StopwatchState(val accumulatedMs: Long = 0, val runningSince: Long? = null, val laps: List<Lap> = emptyList()) {
    val running get() = runningSince != null
    fun elapsed(now: Long): Long = accumulatedMs + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
    fun start(now: Long) = if (running) this else copy(runningSince = now)
    fun stop(now: Long) = if (!running) this else copy(accumulatedMs = elapsed(now), runningSince = null)
    fun lap(now: Long): StopwatchState {
        if (!running) return this
        val total = elapsed(now)
        val prev = laps.firstOrNull()?.totalMs ?: 0
        return copy(laps = listOf(Lap(laps.size + 1, total - prev, total)) + laps)
    }
    fun reset() = StopwatchState()

    /** Index (in [laps]) of the fastest and slowest lap, only meaningful with 3 or more laps. */
    fun bestWorst(): Pair<Int, Int>? {
        if (laps.size < 3) return null
        val best = laps.indices.minByOrNull { laps[it].lapMs } ?: return null
        val worst = laps.indices.maxByOrNull { laps[it].lapMs } ?: return null
        return best to worst
    }

    companion object {
        /** 1:02:03.45 / 02:03.45 / 3.45 style. */
        fun format(ms: Long, hundredths: Boolean = true): String {
            val t = ms.coerceAtLeast(0)
            val h = t / 3_600_000; val m = (t / 60_000) % 60; val s = (t / 1000) % 60; val cs = (t % 1000) / 10
            val core = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            return if (hundredths) "$core.%02d".format(cs) else core
        }
    }
}

data class Phase(val label: String, val seconds: Int)
data class PlanPosition(val index: Int, val remainingMs: Long, val finished: Boolean)

/** A countdown is a list of phases that run back to back (one phase = plain countdown, several = Pomodoro). */
object PlanClock {
    fun totalMs(phases: List<Phase>): Long = phases.sumOf { it.seconds * 1000L }

    fun at(phases: List<Phase>, startedAt: Long, now: Long): PlanPosition {
        var elapsed = (now - startedAt).coerceAtLeast(0)
        phases.forEachIndexed { i, p ->
            val len = p.seconds * 1000L
            if (elapsed < len) return PlanPosition(i, len - elapsed, false)
            elapsed -= len
        }
        return PlanPosition(phases.lastIndex.coerceAtLeast(0), 0, true)
    }

    /** Wall-clock end time of phase [index]. */
    fun phaseEnd(phases: List<Phase>, startedAt: Long, index: Int): Long = startedAt + phases.take(index + 1).sumOf { it.seconds * 1000L }

    /** "1 h 05 min", "5 min", "30 s" style for notifications and presets. */
    fun describe(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return buildList { if (h > 0) add("$h h"); if (m > 0) add("$m min"); if (s > 0 || isEmpty()) add("$s s") }.joinToString(" ")
    }

    /** mm:ss or h:mm:ss for a remaining time, rounded up so the display hits 00:00 exactly when the alarm rings. */
    fun clock(remainingMs: Long): String {
        val total = (remainingMs.coerceAtLeast(0) + 999) / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

object Pomodoro {
    /** work, short break, ... and a long break after the last round: with 4 rounds W S W S W S W L. */
    fun plan(workMin: Int = 25, shortMin: Int = 5, longMin: Int = 15, rounds: Int = 4): List<Phase> = buildList {
        val n = rounds.coerceAtLeast(1)
        for (r in 1..n) {
            add(Phase("Focus $r of $n", workMin * 60))
            add(if (r == n) Phase("Long break", longMin * 60) else Phase("Short break", shortMin * 60))
        }
    }
}
