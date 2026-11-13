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
}
