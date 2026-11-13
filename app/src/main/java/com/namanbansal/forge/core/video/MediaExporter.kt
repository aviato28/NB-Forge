package com.namanbansal.forge.core.video

import android.net.Uri
import com.namanbansal.forge.core.ToolException
import java.io.File

/** Everything an export can ask for. Unset fields mean "leave as is" so Transformer can remux instead of re-encoding. */
data class ExportRequest(
    val input: Uri,
    /** Pieces of the source to keep, in order. Null = the whole file. */
    val segments: List<TrimRange>? = null,
    /** Requested picture codec mime (null = keep source codec when possible). */
    val videoMime: String? = null,
    val audioMime: String? = null,
    val removeAudio: Boolean = false,
    val removeVideo: Boolean = false,
    val videoBitrate: Int? = null,
    val audioBitrate: Int? = null,
    /** Output picture size, upright (already rotated). Null = unchanged. */
    val width: Int? = null,
    val height: Int? = null,
    val speed: Float? = null,
    /** Convert HDR to SDR (when the output is H.264). */
    val toneMapToSdr: Boolean = false,
    val webm: Boolean = false,
)

/** Runs an export to a local file. The real implementation is Media3 Transformer; tests use fakes. */
interface MediaExporter {
    /** Writes [out]; reports progress 0..1; throws [ToolException] with a friendly message. Cancelling the coroutine cancels the export. */
    suspend fun export(request: ExportRequest, out: File, onProgress: (Float) -> Unit)
}

/** Friendly texts for Transformer failures, keyed by `ExportException.errorCode` (plain ints so this is testable without Media3). */
object ExportErrors {
    // Values of androidx.media3.transformer.ExportException.ERROR_CODE_* (stable public API).
    const val ENCODER_INIT_FAILED = 4001
    const val ENCODING_FORMAT_UNSUPPORTED = 4003
    const val DECODER_INIT_FAILED = 3001
    const val DECODING_FORMAT_UNSUPPORTED = 3003
    const val MUXING_FAILED = 7001
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_NO_PERMISSION = 2006
    const val IO_UNSPECIFIED = 2000
    const val PARSING_CONTAINER_UNSUPPORTED = 1004
    const val PARSING_CONTAINER_MALFORMED = 1002
    const val AUDIO_PROCESSING_FAILED = 6001
    const val VIDEO_FRAME_PROCESSING_FAILED = 5001

    fun message(code: Int, hdr: Boolean = false): String = when (code) {
        ENCODER_INIT_FAILED, ENCODING_FORMAT_UNSUPPORTED ->
            "This device can't encode that video format or size. Try MP4 (H.264) or a smaller resolution." + if (hdr) " HDR clips may need HEVC." else ""
        DECODER_INIT_FAILED, DECODING_FORMAT_UNSUPPORTED ->
            "This device can't read the video inside that file (unsupported codec). Try a different file."
        PARSING_CONTAINER_UNSUPPORTED -> "That file format isn't supported."
        PARSING_CONTAINER_MALFORMED -> "The file looks damaged and can't be read."
        IO_FILE_NOT_FOUND, IO_NO_PERMISSION -> "Forge can no longer read the original file. Pick it again."
        IO_UNSPECIFIED -> "There was a problem reading or writing the file. Check that there is free storage and try again."
        MUXING_FAILED -> "Couldn't write the output file. The source may use a format that can't be copied into this container."
        AUDIO_PROCESSING_FAILED -> "The sound couldn't be processed. Try removing the audio."
        VIDEO_FRAME_PROCESSING_FAILED -> "The picture couldn't be processed on this device."
        else -> "The video couldn't be processed (error $code)."
    }
}
