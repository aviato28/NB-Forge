package com.namanbansal.forge.tools.video

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ResumedRun
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.JobRecord
import com.namanbansal.forge.core.video.MediaExporter
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base for the video tools. It probes the picked file (duration, size, codecs, rotation, HDR), and runs the actual work as a
 * background job (foreground service + notification) so it survives leaving the screen. A screen that comes back while the
 * job runs, or after it finished, re-attaches to it ([resumedRun]).
 */
abstract class VideoToolViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {

    private val _info = MutableStateFlow<VideoInfo?>(null)
    val info: StateFlow<VideoInfo?> = _info.asStateFlow()

    private val _poster = MutableStateFlow<android.graphics.Bitmap?>(null)
    /** A still frame for previews while (or instead of) the player. */
    val poster: StateFlow<android.graphics.Bitmap?> = _poster.asStateFlow()

    /** False for tools that also take audio-only files. */
    protected open val requiresVideo: Boolean get() = true

    protected val exporter: MediaExporter get() = app.mediaExporter
    protected val workDir get() = VideoOps.workDir(app)

    override fun onInputsReset() { _info.value = null; _poster.value = null }

    override suspend fun onInputsReady(files: List<PickedFile>) {
        val first = files.first()
        val i = io { app.mediaProbe.probe(first.uri, first.size) }
        if (requiresVideo && !i.hasVideo) throw ToolException("This file has no video picture. Pick a video.")
        if (i.durationMs <= 0L) throw ToolException("Forge couldn't work out how long this file is. It may be damaged.")
        _info.value = i
        if (i.hasVideo) viewModelScope.launch { _poster.value = io { loadPoster(first, i) } }
        onVideoReady(first, i)
    }

    private fun loadPoster(file: PickedFile, info: VideoInfo): android.graphics.Bitmap? {
        FrameThumbs.provider?.let { return it(app, file.uri, info.durationMs * 100, 720) }
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(app, file.uri)
            FrameThumbs.scaled(r, (info.durationMs * 1000L / 10).coerceAtLeast(0L), 720)
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    /** The probe finished: set defaults (trim range, target size...). Runs again after a restore. */
    protected open suspend fun onVideoReady(file: PickedFile, info: VideoInfo) {}

    /** Name shown in the notification, e.g. "Compressing video". */
    protected abstract fun jobLabel(): String

    /** The work, run in the background job. Return what to show on the result screen. */
    protected abstract fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val file = inputs.value.first()
        val info = _info.value ?: throw ToolException("The file hasn't been read yet.")
        val record = app.videoJobs.submit(toolCode, jobLabel(), inputs.value, buildJob(file, info))
            ?: throw ToolException("Another video job is still running. Wait for it to finish, or cancel it from its notification.")
        return awaitRecord(scope, record)
    }

    private suspend fun awaitRecord(scope: RunScope, record: JobRecord): ToolOutcome = try {
        record.await { scope.progress(it) }
    } catch (e: CancellationException) {
        if (cancelRequested) app.videoJobs.cancel()
        throw e
    } finally {
        if (record.state.value.terminal) app.videoJobs.clear(record)
    }

    override fun resumedRun(): ResumedRun? {
        val record = app.videoJobs.recordFor(toolCode) ?: return null
        return ResumedRun(record.inputs) { awaitRecord(it, record) }
    }

    override fun backToOptions() {
        super.backToOptions()
        // A screen that re-attached to a finished job never probed its file.
        val files = inputs.value
        if (_info.value == null && files.isNotEmpty()) {
            viewModelScope.launch {
                runCatching { onInputsReady(files) }.onFailure { notify("Couldn't read that file again. Pick it again.") }
            }
        }
    }
}
