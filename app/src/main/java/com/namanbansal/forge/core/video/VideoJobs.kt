package com.namanbansal.forge.core.video

import android.content.Context
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Where a background video / audio job is. A tiny state machine: Running -> Done | Failed | Cancelled. */
sealed interface JobState {
    data class Running(val progress: Float?) : JobState
    data class Done(val outcome: ToolOutcome) : JobState
    data class Failed(val message: String) : JobState
    data object Cancelled : JobState

    val terminal: Boolean get() = this !is Running
}

/** What a job's work block can do: report progress and write outputs (rolled back if the job fails or is cancelled). */
class JobContext internal constructor(
    private val saver: OutputSaver,
    private val report: (Float?) -> Unit,
    private val saved: MutableList<SavedOutput>,
    val inputName: String,
) {
    fun progress(fraction: Float?) = report(fraction)

    suspend fun save(action: String, ext: String, mime: String, target: com.namanbansal.forge.core.MediaTarget, write: suspend (java.io.OutputStream) -> Unit): SavedOutput =
        saver.save(inputName, action, ext, mime, onSaved = { saved += it }, target = target, write = write)
}

/** One submitted job; its [state] outlives the screen that started it. */
class JobRecord internal constructor(val id: Long, val toolCode: String, val label: String, val inputs: List<PickedFile>) {
    private val _state = MutableStateFlow<JobState>(JobState.Running(null))
    val state: StateFlow<JobState> = _state.asStateFlow()
    internal var job: Job? = null
    internal fun set(s: JobState) { _state.value = s }

    /** Waits for the end of the job, forwarding progress. Throws on failure / cancellation. */
    suspend fun await(onProgress: (Float?) -> Unit): ToolOutcome {
        val end = state.onEach { if (it is JobState.Running) onProgress(it.progress) }.first { it.terminal }
        return when (end) {
            is JobState.Done -> end.outcome
            is JobState.Failed -> throw ToolException(end.message)
            else -> throw CancellationException("Cancelled")
        }
    }
}

/** Starts / stops the foreground service. A seam so the runner can be tested without Android services. */
fun interface ServiceLauncher {
    fun ensureRunning()
}

/**
 * Runs one video or audio job at a time on an app-wide scope, so it keeps going when the screen is left. A foreground service
 * ([VideoJobService]) is started with each job to show the progress notification and keep the process alive.
 */
class VideoJobs(
    private val saver: OutputSaver,
    private val scope: CoroutineScope,
    private val launcher: ServiceLauncher,
) {
    private val ids = AtomicLong(0)
    private val _current = MutableStateFlow<JobRecord?>(null)
    /** The running or just-finished job (kept until its result is picked up). */
    val current: StateFlow<JobRecord?> = _current.asStateFlow()

    fun recordFor(toolCode: String): JobRecord? = _current.value?.takeIf { it.toolCode == toolCode }

    /** True while a job is actually running. */
    val busy: Boolean get() = _current.value?.state?.value?.terminal == false

    /** Starts [work]; returns null (and does nothing) when another job is still running. */
    fun submit(toolCode: String, label: String, inputs: List<PickedFile>, work: suspend JobContext.() -> ToolOutcome): JobRecord? {
        if (busy) return null
        val record = JobRecord(ids.incrementAndGet(), toolCode, label, inputs)
        _current.value = record
        val saved = java.util.concurrent.CopyOnWriteArrayList<SavedOutput>()
        record.job = scope.launch {
            val ctx = JobContext(saver, { record.set(JobState.Running(it?.coerceIn(0f, 1f))) }, saved, inputs.firstOrNull()?.name ?: "file")
            try {
                val outcome = ctx.work()
                record.set(JobState.Done(outcome))
            } catch (e: CancellationException) {
                rollback(saved)
                record.set(JobState.Cancelled)
            } catch (t: Throwable) {
                rollback(saved)
                record.set(JobState.Failed(friendly(t)))
            }
        }
        launcher.ensureRunning()
        return record
    }

    fun cancel() { _current.value?.job?.cancel() }

    /** The result has been shown: forget the job. */
    fun clear(record: JobRecord) { if (_current.value === record) _current.value = null }

    private fun rollback(saved: List<SavedOutput>) { saved.forEach { runCatching { saver.delete(it.uri) } } }

    private fun friendly(t: Throwable): String = when (t) {
        is ToolException -> t.message ?: "Something went wrong."
        is OutOfMemoryError -> "This device ran out of memory processing the file. Try a shorter clip or a smaller size."
        else -> t.message?.takeIf { it.isNotBlank() }?.let { "Couldn't finish: $it" } ?: "Something went wrong."
    }
}

/** What the progress notification shows for a job state. Pure, so the whole state machine is unit-tested. */
data class NotificationSpec(
    val title: String,
    val text: String,
    /** 0..100, or null for indeterminate (only when [ongoing]). */
    val percent: Int?,
    val ongoing: Boolean,
    val showCancel: Boolean,
)

object JobNotifications {
    fun spec(label: String, state: JobState): NotificationSpec = when (state) {
        is JobState.Running -> NotificationSpec(
            title = label, text = state.progress?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" } ?: "Working…",
            percent = state.progress?.let { (it * 100).toInt().coerceIn(0, 100) }, ongoing = true, showCancel = true,
        )
        is JobState.Done -> NotificationSpec(title = "$label finished", text = state.outcome.headline, percent = null, ongoing = false, showCancel = false)
        is JobState.Failed -> NotificationSpec(title = "$label failed", text = state.message, percent = null, ongoing = false, showCancel = false)
        JobState.Cancelled -> NotificationSpec(title = "$label cancelled", text = "Nothing was saved.", percent = null, ongoing = false, showCancel = false)
    }

    /** Notification updates are throttled to whole-percent changes so a fast job doesn't flood the system. */
    fun shouldUpdate(previous: NotificationSpec?, next: NotificationSpec): Boolean =
        previous == null || previous.percent != next.percent || previous.ongoing != next.ongoing || previous.title != next.title || previous.text != next.text
}
