package com.namanbansal.forge.core.device

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Source of microphone levels in dBFS (about 10 per second). Collecting starts recording; cancelling stops and releases it. */
interface AudioLevelSource {
    fun levels(): Flow<Float>
}

object AudioRecordLevelSource : AudioLevelSource {
    private const val RATE = 44100

    @SuppressLint("MissingPermission") // The screen only collects after RECORD_AUDIO is granted.
    override fun levels(): Flow<Float> = flow {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@flow
        val window = RATE / 10
        val buf = ShortArray(window)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, window * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@flow }
        try {
            rec.startRecording()
            while (coroutineContext.isActive) {
                var read = 0
                while (read < window && coroutineContext.isActive) {
                    val n = rec.read(buf, read, window - read)
                    if (n <= 0) return@flow
                    read += n
                }
                if (read == window) emit(SoundMath.rmsDbfs(buf, window))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)
}

val LocalAudioLevelSource = compositionLocalOf<AudioLevelSource> { AudioRecordLevelSource }
