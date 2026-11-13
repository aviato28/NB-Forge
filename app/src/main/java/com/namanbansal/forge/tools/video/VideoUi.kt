package com.namanbansal.forge.tools.video

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.video.TrimMath
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.delay

/** Tests and screenshots switch the real ExoPlayer off (it can't render under Robolectric); the thumbnail is shown instead. */
val LocalVideoPreview = compositionLocalOf { true }

/** One line about a picked file: "1920 x 1080 · 0:32 · 54 MB · H.264 / AAC". */
fun VideoInfo.summary(): String = buildList {
    if (hasVideo) add("$width × $height")
    add(TrimMath.clock(durationMs, tenths = false))
    if (sizeBytes >= 0) add(formatBytes(sizeBytes))
    codecLabel()?.let { add(it) }
}.joinToString(" · ")

fun VideoInfo.codecLabel(): String? {
    fun v(m: String?) = when (m?.lowercase()) {
        "video/avc" -> "H.264"; "video/hevc" -> "HEVC"; "video/x-vnd.on2.vp9" -> "VP9"; "video/x-vnd.on2.vp8" -> "VP8"; "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"; "video/3gpp" -> "H.263"; null -> null; else -> m.substringAfter('/')
    }
    fun a(m: String?) = when (m?.lowercase()) {
        "audio/mp4a-latm" -> "AAC"; "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/mpeg" -> "MP3"; "audio/ac3" -> "AC-3"
        "audio/3gpp", "audio/amr-wb" -> "AMR"; "audio/raw" -> "PCM"; "audio/flac" -> "FLAC"; null -> null; else -> m.substringAfter('/')
    }
    return listOfNotNull(v(videoMime) + if (hdr) " HDR" else "", a(audioMime)).filter { it.isNotBlank() && it != "null" }.joinToString(" / ").ifBlank { null }
}

/** Asks once for the notification permission (Android 13+) so the progress notification can show; the job runs either way. */
@Composable
fun AskNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

/** An ExoPlayer for [uri] tied to the composition; released when it leaves. Null when previews are switched off. */
@Composable
fun rememberPreviewPlayer(uri: Uri?, exact: Boolean = true): ExoPlayer? {
    val enabled = LocalVideoPreview.current
    val context = LocalContext.current
    if (!enabled || uri == null) return null
    val player = remember(uri) {
        runCatching {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                if (exact) setSeekParameters(SeekParameters.EXACT) // frame-accurate scrubbing
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    return player
}

/** The picture of [player] (no built-in controls: each tool draws its own). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

/** Tracks whether [player] is playing and its position (polled; cheap). */
@Composable
fun rememberPlayback(player: Player?): Pair<Boolean, Long> {
    var playing by remember(player) { mutableStateOf(false) }
    var pos by remember(player) { mutableStateOf(0L) }
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            playing = player.isPlaying
            pos = player.currentPosition
            delay(if (player.isPlaying) 60 else 200)
        }
    }
    return playing to pos
}

/**
 * A video preview: ExoPlayer with tap-to-play, or the [thumbnail] while the player is unavailable (loading, tests).
 * [aspect] is width / height of the upright picture, so portrait videos get a tall box instead of a letterboxed wide one.
 */
@Composable
fun VideoPreviewBox(
    player: ExoPlayer?,
    thumbnail: Bitmap?,
    aspect: Float,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null,
) {
    val (playing, _) = rememberPlayback(player)
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.5f, 2.4f)).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .clickable(enabled = player != null) { onToggle?.invoke() ?: player?.let { if (it.isPlaying) it.pause() else it.play() } },
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null && (player == null)) {
            Image(thumbnail.asImageBitmap(), "Video preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (player != null) PlayerSurface(player, Modifier.fillMaxSize())
        if (!playing) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0x99000000)).semantics { contentDescription = "Play" }, contentAlignment = Alignment.Center) {
                Icon(ForgeIcons.Play, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Frame thumbnails of the video for timelines and static previews (via MediaMetadataRetriever; streams, never loads the whole file). */
object FrameThumbs {
    /** [count] evenly spaced frames, [width] px wide; entries are null where a frame couldn't be decoded. */
    fun load(context: android.content.Context, uri: Uri, durationMs: Long, count: Int, width: Int, onFrame: (Int, Bitmap?) -> Unit) {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            for (i in 0 until count) {
                val t = if (count == 1) 0L else (durationMs * 1000L * (2 * i + 1) / (2 * count))
                val bmp = try { scaled(r, t, width) } catch (_: Throwable) { null }
                onFrame(i, bmp)
            }
        } catch (_: Throwable) {
            for (i in 0 until count) onFrame(i, null)
        } finally {
            runCatching { r.release() }
        }
    }

    fun scaled(r: android.media.MediaMetadataRetriever, timeUs: Long, width: Int, option: Int = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC): Bitmap? {
        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return r.getFrameAtTime(timeUs, option)
        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return r.getFrameAtTime(timeUs, option)
        val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (uw, uh) = if (rot == 90 || rot == 270) h to w else w to h
        val tw = minOf(width, uw)
        val th = (uh.toDouble() * tw / uw).toInt().coerceAtLeast(2)
        val frame = r.getScaledFrameAtTime(timeUs, option, tw, th) ?: return null
        if (frame.width == tw && frame.height == th) return frame
        // Some devices scale before rotating: make sure the result is upright and the requested size.
        return Bitmap.createScaledBitmap(frame, tw, th, true).also { if (it !== frame) frame.recycle() }
    }
}
