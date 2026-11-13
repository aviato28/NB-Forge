package com.namanbansal.forge.video

import android.net.Uri
import com.namanbansal.forge.core.video.ExportRequest
import com.namanbansal.forge.core.video.MediaExporter
import com.namanbansal.forge.core.video.MediaProbe
import com.namanbansal.forge.core.video.VideoInfo
import kotlinx.coroutines.yield
import java.io.File

/**
 * Media3 can't encode under Robolectric (no codecs), so processing tests run against these fakes: the exporter records what it was
 * asked to do and writes a small file; the real Transformer is exercised only on a device.
 */
class FakeExporter : MediaExporter {
    val requests = mutableListOf<ExportRequest>()
    var failWith: Throwable? = null
    var bytes = 4096
    override suspend fun export(request: ExportRequest, out: File, onProgress: (Float) -> Unit) {
        requests += request
        failWith?.let { throw it }
        for (i in 1..4) { onProgress(i / 4f); yield() }
        out.parentFile?.mkdirs()
        out.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
    }
}

class FakeProbe(var info: VideoInfo) : MediaProbe {
    override fun probe(uri: Uri, sizeBytes: Long): VideoInfo = info.copy(sizeBytes = sizeBytes)
}

fun sampleInfo(
    durationMs: Long = 20_000, w: Int = 1920, h: Int = 1080, video: String? = "video/avc", audio: String? = "audio/mp4a-latm",
    fps: Float = 30f, bps: Long? = 8_000_000, size: Long = 20_000_000, hdr: Boolean = false, rotation: Int = 0,
) = VideoInfo(durationMs, w, h, rotation, fps, video, audio, bps, size, hdr)
