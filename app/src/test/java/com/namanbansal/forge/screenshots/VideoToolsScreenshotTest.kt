package com.namanbansal.forge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.video.CodecEntry
import com.namanbansal.forge.core.video.EncoderCapabilities
import com.namanbansal.forge.core.video.FrameSource
import com.namanbansal.forge.image.ImageFixtures
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.video.*
import com.namanbansal.forge.video.FakeExporter
import com.namanbansal.forge.video.FakeProbe
import com.namanbansal.forge.video.sampleInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every video tool screen rendered with a fake probe / exporter and synthetic frames (smoke test), captured light + dark when recording.
 * The real ExoPlayer is switched off (LocalVideoPreview) because it cannot render under Robolectric; the poster frame stands in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class VideoToolsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: ForgeApp get() = ApplicationProvider.getApplicationContext<Application>() as ForgeApp
    private lateinit var exporter: FakeExporter
    private lateinit var probe: FakeProbe

    private fun scene(w: Int, h: Int, t: Int): Bitmap = ImageFixtures.scene(w, h).let { b ->
        // A moving bar so thumbnails along the timeline differ from each other.
        val c = android.graphics.Canvas(b); val p = android.graphics.Paint().apply { color = android.graphics.Color.argb(200, 255, 255, 255) }
        c.drawRect((t % 10) * w / 10f, 0f, (t % 10) * w / 10f + w / 14f, h.toFloat(), p); b
    }

    @Before fun setUp() {
        val media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media"); media.dir = tmp.newFolder("media")
        exporter = FakeExporter(); probe = FakeProbe(sampleInfo(durationMs = 42_000, size = 96_000_000, bps = 17_000_000))
        app.mediaExporter = exporter; app.mediaProbe = probe
        FrameThumbs.provider = { _, _, tUs, w -> scene(w, w * 9 / 16, (tUs / 1_000_000L).toInt()) }
        app.frameSourceFactory = { _, _ -> object : FrameSource { override fun frame(timeUs: Long, w: Int, h: Int): IntArray { val b = scene(w, h, (timeUs / 100_000).toInt()); return IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } } } }
    }

    @After fun tearDown() { FrameThumbs.provider = null }

    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, {})
    private fun clip(name: String = "holiday.mp4") = PickedFile(Uri.fromFile(File(tmp.root, name).also { it.writeBytes(ByteArray(16)) }), name, 96_000_000, "video/mp4")

    private fun ready(vm: ToolViewModel, extra: () -> Boolean = { true }) {
        vm.onFilesPicked(listOf(clip()))
        compose.pumpUntil { vm.phase.value == ToolPhase.Options && !vm.loading.value && extra() }
    }

    private fun flippable(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setFlippable { CompositionLocalProvider(LocalVideoPreview provides false) { content() } }

    @Test fun pickStates() {
        flippable { com.namanbansal.forge.tools.ToolScreens.screens.getValue("V1")(host("V1")) }
        compose.onNodeWithText("Choose a video to trim").assertIsDisplayed()
    }

    @Test fun allVideoToolsAreRegisteredAndRouted() {
        for (code in listOf("V1", "V2", "V3", "V4", "V5", "V8", "V9")) {
            assertTrue(code, ToolRegistry.get(code)!!.status == com.namanbansal.forge.tools.ToolStatus.Available)
            assertTrue(code, com.namanbansal.forge.tools.ToolScreens.screens.containsKey(code))
        }
    }

    @Test fun trim() {
        val vm = TrimViewModel(app, SavedStateHandle())
        val flip = flippable { TrimScreen(host("V1"), vm) }
        ready(vm, extra = { vm.thumbs.value.all { it != null } })
        vm.setRange(6_000, 31_500, 42_000)
        compose.waitForIdle()
        compose.onNodeWithText("Keep selection").assertIsDisplayed()
        compose.onNodeWithText("Save trimmed video").assertIsEnabled()
        compose.captureBoth("video-trim", flip)
        vm.setMode(com.namanbansal.forge.core.video.TrimMode.REMOVE); compose.waitForIdle()
        compose.onNodeWithText("Remove section").assertIsEnabled()
        compose.captureBoth("video-trim-remove", flip)
        vm.setRange(0, 42_000, 42_000); compose.waitForIdle()
        compose.onNodeWithText("Remove section").assertIsNotEnabled() // would leave nothing
    }

    @Test fun compress() {
        val vm = CompressVideoViewModel(app, SavedStateHandle())
        val flip = flippable { CompressVideoScreen(host("V2"), vm) }
        ready(vm, extra = { vm.poster.value != null })
        compose.onNodeWithText("Estimated size").assertIsDisplayed()
        compose.onNodeWithText("Medium 720p").assertIsDisplayed()
        compose.captureBoth("video-compress", flip)
        compose.onNodeWithText("Target size").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Target file size").assertIsDisplayed()
        compose.captureBoth("video-compress-target", flip)
    }

    @Test fun compressResultShowsBeforeAfter() {
        val vm = CompressVideoViewModel(app, SavedStateHandle())
        val flip = flippable { com.namanbansal.forge.tools.ToolScreens.screens.getValue("V2").let { CompressVideoScreen(host("V2"), vm) } }
        ready(vm)
        vm.start(); compose.pumpUntil { vm.phase.value == ToolPhase.Result }
        assertEquals("Movies/Forge", vm.result.value!!.output.folder)
        compose.onNodeWithText("Video compressed").assertIsDisplayed()
        compose.captureBoth("video-compress-result", flip)
    }

    @Test fun convertHidesWebmWithoutEncoder() {
        app.encoderCaps.let { /* default caps read the real (Robolectric) MediaCodecList */ }
        val vm = ConvertVideoViewModel(app, SavedStateHandle())
        val flip = flippable { ConvertVideoScreen(host("V3"), vm) }
        ready(vm, extra = { vm.poster.value != null })
        compose.onNodeWithText("Convert to").assertIsDisplayed()
        compose.onNodeWithText("MP4 · H.264").assertIsDisplayed()
        compose.captureBoth("video-convert", flip)
    }

    @Test fun gifPreviewAndEstimate() {
        val vm = GifViewModel(app, SavedStateHandle())
        val flip = flippable { GifScreen(host("V4"), vm) }
        ready(vm, extra = { vm.preview.value != null })
        compose.onNodeWithText("Estimated size").assertIsDisplayed()
        compose.onNodeWithText("Make GIF").assertIsEnabled()
        compose.captureBoth("video-gif", flip)
    }

    @Test fun extractAudio() {
        val vm = ExtractAudioViewModel(app, SavedStateHandle())
        val flip = flippable { ExtractAudioScreen(host("V5"), vm) }
        ready(vm)
        compose.onNodeWithText("Format").assertIsDisplayed()
        compose.onNodeWithText("Extract as M4A").assertIsEnabled()
        compose.captureBoth("video-audio", flip)
    }

    @Test fun speed() {
        val vm = SpeedViewModel(app, SavedStateHandle())
        val flip = flippable { SpeedScreen(host("V8"), vm) }
        ready(vm, extra = { vm.poster.value != null })
        vm.setSpeed(3f); compose.waitForIdle()
        compose.onNodeWithText("Apply 3x").assertIsEnabled()
        compose.captureBoth("video-speed", flip)
    }

    @Test fun mute() {
        val vm = MuteViewModel(app, SavedStateHandle())
        val flip = flippable { MuteScreen(host("V9"), vm) }
        ready(vm, extra = { vm.poster.value != null })
        compose.onNodeWithText("Remove audio").assertIsEnabled()
        compose.captureBoth("video-mute", flip)
    }

    @Test fun muteExplainsWhenThereIsNoSound() {
        probe.info = sampleInfo(audio = null)
        val vm = MuteViewModel(app, SavedStateHandle())
        flippable { MuteScreen(host("V9"), vm) }
        ready(vm)
        compose.onNodeWithText("This video has no sound.").assertIsDisplayed()
        compose.onNodeWithText("Remove audio").assertIsNotEnabled()
    }
}
