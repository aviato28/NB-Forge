package com.namanbansal.forge.core.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.namanbansal.forge.core.ToolException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RIFF/WAVE header for 16-bit PCM. */
object WavWriter {
    const val HEADER_SIZE = 44L

    fun header(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        b.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }

    /** Converts float PCM (-1..1, little endian) to 16-bit. */
    fun floatToPcm16(src: ByteBuffer, count: Int, dst: ByteArray, dstOff: Int) {
        val fb = src.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var o = dstOff
        for (i in 0 until count) {
            val v = (fb.get(i) * 32767f).toInt().coerceIn(-32768, 32767)
            dst[o++] = (v and 0xFF).toByte(); dst[o++] = ((v shr 8) and 0xFF).toByte()
        }
    }
}

/**
 * Decodes the first audio track of a file to 16-bit PCM and writes a WAV, streaming: nothing larger than one codec buffer is in memory.
 * The header is patched at the end because the length isn't known up front.
 */
class WavExtractor(private val context: Context) {
    suspend fun extract(uri: Uri, range: TrimRange?, out: File, onProgress: (Float) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var raf: RandomAccessFile? = null
        try {
            try { ex.setDataSource(context, uri, null) } catch (e: Exception) { throw ToolException("Forge couldn't open this file.") }
            var track = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) { track = i; break }
            if (track < 0) throw ToolException("This file has no sound to extract.")
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = (range?.startMs ?: 0L) * 1000L
            val endUs = range?.endMs?.times(1000L) ?: Long.MAX_VALUE
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = try { MediaCodec.createDecoderByType(mime).also { it.configure(inFormat, null, null, 0); it.start() } } catch (e: Exception) {
                throw ToolException("This device can't decode that audio format ($mime).")
            }
            raf = RandomAccessFile(out, "rw").also { it.setLength(0); it.write(ByteArray(WavWriter.HEADER_SIZE.toInt())) }
            val info = MediaCodec.BufferInfo()
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var inputDone = false; var outputDone = false
            var written = 0L
            var scratch = ByteArray(0)
            val total = (if (endUs == Long.MAX_VALUE) durationUs else endUs) - startUs
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t > endUs) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true } else {
                            codec.queueInputBuffer(ii, 0, n, t, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!
                        if (info.size > 0 && info.presentationTimeUs >= startUs - 50_000) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (floatPcm) {
                                val samples = info.size / 4
                                if (scratch.size < samples * 2) scratch = ByteArray(samples * 2)
                                WavWriter.floatToPcm16(buf.slice(), samples, scratch, 0)
                                raf.write(scratch, 0, samples * 2); written += samples * 2
                            } else {
                                if (scratch.size < info.size) scratch = ByteArray(info.size)
                                buf.get(scratch, 0, info.size)
                                raf.write(scratch, 0, info.size); written += info.size
                            }
                            if (total > 0) onProgress(((info.presentationTimeUs - startUs).toFloat() / total).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (written == 0L) throw ToolException("No sound was found in that range.")
            raf.seek(0); raf.write(WavWriter.header(written, sampleRate, channels))
        } catch (t: Throwable) {
            runCatching { raf?.close() }; raf = null
            out.delete()
            throw t
        } finally {
            runCatching { raf?.close() }
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
