package com.namanbansal.forge.core.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.AudioEncoderSettings
import com.namanbansal.forge.core.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** Media3 Transformer behind [MediaExporter]. Transformer must be driven from one looper thread, so everything here runs on Main. */
@androidx.annotation.OptIn(UnstableApi::class)
class TransformerExporter(private val context: Context) : MediaExporter {

    override suspend fun export(request: ExportRequest, out: File, onProgress: (Float) -> Unit) {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(ToolException(ExportErrors.message(exportException.errorCode, request.toneMapToSdr), exportException))
                }
            }
            val transformer = build(request, listener)
            try {
                transformer.start(composition(request), out.absolutePath)
                coroutineScope {
                    val poll = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                            delay(250)
                        }
                    }
                    try { done.await() } finally { poll.cancel() }
                }
                onProgress(1f)
            } catch (e: CancellationException) {
                runCatching { transformer.cancel() }
                out.delete()
                throw e
            } catch (t: Throwable) {
                runCatching { transformer.cancel() }
                out.delete()
                throw t
            }
        }
    }

    private fun build(r: ExportRequest, listener: Transformer.Listener): Transformer {
        val b = Transformer.Builder(context).addListener(listener)
        b.setLooper(Looper.getMainLooper())
        r.videoMime?.let { b.setVideoMimeType(it) }
        r.audioMime?.let { b.setAudioMimeType(it) }
        if (r.videoBitrate != null || r.audioBitrate != null) {
            val enc = DefaultEncoderFactory.Builder(context).setEnableFallback(true)
            r.videoBitrate?.let { enc.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(it).build()) }
            r.audioBitrate?.let { enc.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(it).build()) }
            b.setEncoderFactory(enc.build())
        }
        if (r.webm) b.setMuxerFactory(WebmMuxer.Factory())
        return b.build()
    }

    private fun composition(r: ExportRequest): Composition {
        val segments = r.segments ?: listOf(null)
        val items = segments.map { seg ->
            val mi = MediaItem.Builder().setUri(r.input).apply {
                if (seg != null) setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(seg.startMs).setEndPositionMs(seg.endMs).build(),
                )
            }.build()
            val videoEffects = buildList<androidx.media3.common.Effect> {
                if (r.width != null && r.height != null) add(Presentation.createForWidthAndHeight(r.width, r.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            EditedMediaItem.Builder(mi)
                .setRemoveAudio(r.removeAudio)
                .setRemoveVideo(r.removeVideo)
                .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                .apply {
                    if (r.speed != null && r.speed != 1f) setSpeed(constantSpeed(r.speed))
                }
                .build()
        }
        val sequence = EditedMediaItemSequence.Builder(items).build()
        return Composition.Builder(sequence)
            .setHdrMode(if (r.toneMapToSdr) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
            .build()
    }

    private fun constantSpeed(speed: Float) = object : androidx.media3.common.audio.SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }
}
