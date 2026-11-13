package com.namanbansal.forge.video

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.tools.video.*
import com.namanbansal.forge.video.GifDecode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pick -> options -> background job -> result for each video tool, with a fake probe and exporter (see VideoFakes). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoToolsE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private val app: ForgeApp get() = ApplicationProvider.getApplicationContext<Application>() as ForgeApp
    private lateinit var media: FakeMediaProvider
    private lateinit var exporter: FakeExporter
    private lateinit var probe: FakeProbe

    @Before fun init() {
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        media.dir = tmp.newFolder("media")
        exporter = FakeExporter(); probe = FakeProbe(sampleInfo())
        app.mediaExporter = exporter; app.mediaProbe = probe
    }

    private fun pump(timeoutMs: Long = 30_000, until: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not reached in time", until())
    }

    private fun clip(name: String = "clip.mp4") = PickedFile(Uri.fromFile(File(tmp.root, name).also { it.writeBytes(ByteArray(20_000_000 / 1000)) }), name, 20_000_000, "video/mp4")

    private fun open(vm: ToolViewModel, f: PickedFile = clip()) {
        vm.onFilesPicked(listOf(f))
        pump { vm.phase.value == ToolPhase.Options && !vm.loading.value }
    }

    private fun run(vm: ToolViewModel) {
        vm.start()
        pump { vm.phase.value == ToolPhase.Result || vm.phase.value == ToolPhase.Failed }
        assertEquals(vm.error.value, ToolPhase.Result, vm.phase.value)
    }

    @Test fun muteRemovesAudioAndSavesToMoviesForge() {
        val vm = MuteViewModel(app, SavedStateHandle())
        open(vm)
        assertNotNull(vm.info.value)
        run(vm)
        val r = vm.result.value!!
        assertEquals("clip_muted.mp4", r.output.displayName)
        assertEquals("Movies/Forge", r.output.folder)
        assertEquals("video/mp4", r.output.mime)
        assertTrue(exporter.requests.single().removeAudio)
        assertEquals(4096L, r.output.size)
        assertEquals(0, tmp.root.walkTopDown().count { it.name.startsWith("job-") }) // temp file removed
    }

    @Test fun exporterFailureShowsFriendlyErrorAndLeavesNoFile() {
        exporter.failWith = com.namanbansal.forge.core.ToolException("This device can't encode that video format or size.")
        val vm = MuteViewModel(app, SavedStateHandle())
        open(vm)
        vm.start()
        pump { vm.phase.value == ToolPhase.Failed }
        assertEquals("This device can't encode that video format or size.", vm.error.value)
        assertTrue(media.rows.isEmpty())
    }

    @Test fun audioOnlyFileIsRejectedByVideoTools() {
        probe.info = sampleInfo(video = null)
        val vm = MuteViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(clip()))
        pump { vm.phase.value == ToolPhase.Pick && !vm.loading.value }
    }

    @Test fun screenReattachesToFinishedJobAfterLeaving() {
        val vm = MuteViewModel(app, SavedStateHandle())
        open(vm)
        vm.start()
        pump { vm.phase.value == ToolPhase.Result }
        // A second screen instance (user left and came back) while a job result is still pending.
        val record = app.videoJobs.submit("V9", "Removing audio", listOf(clip("b.mp4")), { com.namanbansal.forge.core.ToolOutcome(save("muted", "mp4", "video/mp4", com.namanbansal.forge.core.MediaTarget.VIDEO) { it.write(1) }, "done") })
        assertNotNull(record)
        pump { record!!.state.value.terminal }
        val back = MuteViewModel(app, SavedStateHandle())
        pump { back.phase.value == ToolPhase.Result }
        assertEquals("done", back.result.value!!.headline)
        assertNull(app.videoJobs.recordFor("V9")) // consumed
    }

    @Test fun secondJobIsRefusedWhileOneRuns() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val a = app.videoJobs.submit("V9", "A", listOf(clip()), { gate.await(); com.namanbansal.forge.core.ToolOutcome(save("x", "mp4", "video/mp4", com.namanbansal.forge.core.MediaTarget.VIDEO) { it.write(1) }, "a") })
        assertNotNull(a)
        assertNull(app.videoJobs.submit("V9", "B", listOf(clip()), { error("never") }))
        app.videoJobs.cancel()
        gate.countDown()
        pump { a!!.state.value.terminal }
        assertEquals(com.namanbansal.forge.core.video.JobState.Cancelled, a!!.state.value)
        app.videoJobs.clear(a)
    }

    @Test fun compressPresetAndTargetBuildTheRightRequests() {
        val vm = CompressVideoViewModel(app, SavedStateHandle())
        open(vm)
        vm.setMode(com.namanbansal.forge.core.video.CompressMode.SMALL); run(vm)
        val r = exporter.requests.last()
        assertEquals("video/avc", r.videoMime); assertEquals(480, r.height); assertEquals(854 - 2, r.width); assertEquals("clip_compressed.mp4", vm.result.value!!.output.displayName)
        assertEquals("Video compressed", vm.result.value!!.headline)
        vm.backToOptions(); vm.setMode(com.namanbansal.forge.core.video.CompressMode.TARGET); vm.setTargetKb(4096); run(vm)
        val t = exporter.requests.last()
        // ~4 MB over 20 s = ~1.6 Mbps total; audio takes 128 kbps.
        assertEquals(1_600_000.0, t.videoBitrate!!.toDouble() + 128_000, 160_000.0)
    }

    @Test fun portraitAndRotatedClipsKeepTheirUprightSize() {
        probe.info = sampleInfo(w = 1080, h = 1920, rotation = 90)
        val vm = CompressVideoViewModel(app, SavedStateHandle())
        open(vm); vm.setMode(com.namanbansal.forge.core.video.CompressMode.MEDIUM); run(vm)
        val r = exporter.requests.last()
        assertEquals(720, r.width); assertEquals(1280, r.height)
    }

    @Test fun convertRemuxesWhenPossibleAndReencodesOtherwise() {
        val vm = ConvertVideoViewModel(app, SavedStateHandle())
        open(vm); run(vm)
        assertNull(exporter.requests.last().videoBitrate)
        assertEquals("Copied without re-encoding: no quality loss.", vm.result.value!!.detail)
        probe.info = sampleInfo(video = "video/x-vnd.on2.vp9", audio = "audio/opus")
        vm.changeFiles(); open(vm); run(vm)
        assertNotNull(exporter.requests.last().videoBitrate); assertNull(vm.result.value!!.detail)
        assertEquals("video/mp4", vm.result.value!!.output.mime)
    }

    @Test fun speedMutesAboveTwoXAndKeepsAudioBelow() {
        val vm = SpeedViewModel(app, SavedStateHandle())
        open(vm); vm.setSpeed(1.5f); run(vm)
        assertEquals(1.5f, exporter.requests.last().speed!!, 1e-4f); assertFalse(exporter.requests.last().removeAudio)
        vm.backToOptions(); vm.setSpeed(4f); run(vm)
        assertTrue(exporter.requests.last().removeAudio)
        assertTrue(vm.result.value!!.detail!!.contains("0:20 → 0:05"))
    }

    @Test fun trimKeepAndRemoveMiddleSendTheRightSegments() {
        val vm = TrimViewModel(app, SavedStateHandle())
        open(vm)
        assertEquals(20_000, vm.endMs.value)
        vm.setRange(5_000, 12_000, 20_000); run(vm)
        assertEquals(listOf(com.namanbansal.forge.core.video.TrimRange(5_000, 12_000)), exporter.requests.last().segments)
        assertEquals("clip_trimmed.mp4", vm.result.value!!.output.displayName)
        vm.backToOptions(); vm.setMode(com.namanbansal.forge.core.video.TrimMode.REMOVE); run(vm)
        assertEquals(listOf(com.namanbansal.forge.core.video.TrimRange(0, 5_000), com.namanbansal.forge.core.video.TrimRange(12_000, 20_000)), exporter.requests.last().segments)
        assertEquals("clip_cut.mp4", vm.result.value!!.output.displayName)
        assertTrue(vm.result.value!!.detail!!.contains("two clips joined"))
        vm.backToOptions(); vm.setRange(0, 20_000, 20_000); vm.start()
        pump { vm.phase.value == ToolPhase.Failed }
        assertTrue(vm.error.value!!.contains("Nothing would be left"))
    }

    @Test fun extractAudioToM4aGoesToMusicForge() {
        val vm = ExtractAudioViewModel(app, SavedStateHandle())
        open(vm); run(vm)
        val r = exporter.requests.last()
        assertTrue(r.removeVideo); assertEquals("audio/mp4a-latm", r.audioMime); assertNull(r.segments)
        assertEquals("Music/Forge", vm.result.value!!.output.folder); assertEquals("clip_audio.m4a", vm.result.value!!.output.displayName)
        assertTrue(vm.result.value!!.detail!!.contains("lossless"))
        vm.backToOptions(); vm.setTrim(true); vm.setRange(2_000, 6_000, 20_000); run(vm)
        assertEquals(com.namanbansal.forge.core.video.TrimRange(2_000, 6_000), exporter.requests.last().segments!!.single())
    }

    @Test fun videoWithoutSoundCannotBeExtracted() {
        probe.info = sampleInfo(audio = null)
        val vm = ExtractAudioViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(clip()))
        pump { vm.phase.value == ToolPhase.Pick && !vm.loading.value }
    }

    @Test fun gifJobWritesAValidGifToPicturesForge() {
        app.frameSourceFactory = { _, _ -> object : com.namanbansal.forge.core.video.FrameSource { override fun frame(timeUs: Long, w: Int, h: Int) = IntArray(w * h) { (0xFF000000.toInt() or ((it + (timeUs / 50_000).toInt()) * 977)) } } }
        val vm = GifViewModel(app, SavedStateHandle())
        open(vm)
        vm.setRange(0, 2_000, 20_000); vm.setFps(10); vm.setWidth(240)
        run(vm)
        val r = vm.result.value!!
        assertEquals("clip_gif.gif", r.output.displayName); assertEquals("Pictures/Forge", r.output.folder); assertEquals("image/gif", r.output.mime)
        val bytes = media.rows.values.single().file.readBytes()
        val gif = GifDecode.decode(bytes)
        assertEquals(20, gif.frames.size); assertEquals(240, gif.width); assertEquals(135, gif.height)
        assertTrue(r.detail!!.contains("20 frames"))
    }

    @Test fun gifRangeIsLimitedTo30Seconds() {
        probe.info = sampleInfo(durationMs = 120_000)
        val vm = GifViewModel(app, SavedStateHandle())
        open(vm)
        vm.setRange(10_000, 100_000, 120_000)
        assertTrue(vm.endMs.value - vm.startMs.value <= 30_000)
    }
}
