package com.namanbansal.forge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.image.*
import com.namanbansal.forge.image.ImageFixtures
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.image.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Every image tool screen rendered with real pictures (smoke test) and captured light + dark when recording. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ImageToolsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, {})
    private fun pic(name: String, w: Int = 1200, h: Int = 800, tint: Float = 0f): PickedFile {
        val f = ImageFixtures.write(tmp.root, name, ImageFixtures.scene(w, h))
        return PickedFile(Uri.fromFile(f), name, f.length(), "image/jpeg")
    }
    private fun file(f: File, mime: String) = PickedFile(Uri.fromFile(f), f.name, f.length(), mime)

    @Before fun fake() {
        SegmenterProvider.factory = { object : SubjectSegmenter {
            override val engine = "Test model"
            override suspend fun segment(bmp: Bitmap) = ByteArray(bmp.width * bmp.height) { i -> val x = i % bmp.width; val y = i / bmp.width; if (x in bmp.width / 5 until bmp.width * 3 / 5 && y in bmp.height / 4 until bmp.height * 9 / 10) 255.toByte() else 0 }
        } }
    }

    private fun ready(vm: ToolViewModel, vararg f: PickedFile, extra: () -> Boolean = { true }) {
        vm.onFilesPicked(f.toList())
        compose.pumpUntil { vm.phase.value == ToolPhase.Options && !vm.loading.value && extra() }
    }

    @Test fun pickStates() {
        val flip = compose.setFlippable { com.namanbansal.forge.tools.ToolScreens.screens.getValue("I1")(host("I1")) }
        compose.onNodeWithText("Choose pictures to resize or crop").assertIsDisplayed()
    }

    @Test fun resize() {
        val vm = ResizeViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { ResizeScreen(host("I1"), vm) }
        vm.onFilesPicked(listOf(pic("r.jpg"))); compose.pumpUntil { vm.preview.value != null && vm.phase.value == ToolPhase.Options }
        compose.onNodeWithText("Width (px)").assertIsDisplayed()
        compose.captureBoth("img-resize", flip)
        vm.setMode(ResizeMode.CROP); vm.setRatio(1); compose.pumpUntil { compose.onAllNodesWithText("Free").fetchSemanticsNodes().isNotEmpty() }; compose.captureBoth("img-crop", flip)
    }

    @Test fun compress() {
        val vm = CompressViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { CompressScreen(host("I2"), vm) }
        vm.onFilesPicked(listOf(pic("c.jpg"))); compose.pumpUntil { vm.after.value != null }
        compose.onNodeWithText("Original").assertIsDisplayed(); compose.captureBoth("img-compress", flip)
    }

    @Test fun convert() { val vm = ConvertViewModel(app, SavedStateHandle()); val flip = compose.setFlippable { ConvertScreen(host("I3"), vm) }; vm.onFilesPicked(listOf(pic("v.jpg"))); compose.pumpUntil { vm.preview.value != null && vm.phase.value == ToolPhase.Options }; compose.onNodeWithText("Convert to").assertIsDisplayed(); compose.captureBoth("img-convert", flip) }

    @Test fun background() {
        val vm = BgRemoveViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { BgRemoveScreen(host("I4"), vm) }
        vm.onFilesPicked(listOf(pic("b.jpg"))); compose.pumpUntil { vm.composite.value != null }
        compose.onNodeWithText("Refine").assertIsDisplayed(); compose.captureBoth("img-bgremove", flip)
    }

    @Test fun strip() {
        val f = File(tmp.root, "g.jpg").also { it.writeBytes(ImageFixtures.jpegWithExif(tmp.root)) }
        val vm = StripViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { StripScreen(host("I5"), vm) }
        vm.onFilesPicked(listOf(file(f, "image/jpeg"))); compose.pumpUntil { vm.meta.value != null && vm.phase.value == ToolPhase.Options && !vm.loading.value }
        compose.onNodeWithText("What this picture carries").assertIsDisplayed(); compose.captureBoth("img-strip", flip)
    }

    @Test fun palette() {
        val vm = PaletteViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { PaletteScreen(host("I6"), vm) }
        vm.onFilesPicked(listOf(pic("p.jpg"))); compose.pumpUntil { vm.palette.value.isNotEmpty() && !vm.loading.value }
        compose.onNodeWithText("Palette").assertIsDisplayed(); compose.captureBoth("img-palette", flip)
    }

    @Test fun upscale() { val vm = UpscaleViewModel(app, SavedStateHandle()); val flip = compose.setFlippable { UpscaleScreen(host("I7"), vm) }; vm.onFilesPicked(listOf(pic("u.jpg", 400, 300))); compose.pumpUntil { vm.preview.value != null && !vm.loading.value }; compose.onNodeWithText("Enlarge by").assertIsDisplayed(); compose.captureBoth("img-upscale", flip) }

    @Test fun collage() {
        val vm = CollageViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { CollageScreen(host("I9"), vm) }
        vm.onFilesPicked(listOf(pic("1.jpg", 600, 400), pic("2.jpg", 400, 600), pic("3.jpg", 500, 500), pic("4.jpg", 800, 500))); compose.pumpUntil { vm.previews.value.size == 4 && !vm.loading.value }
        vm.setSpacing(24); vm.setRadius(30); compose.waitForIdle()
        compose.onNodeWithText("Layout").assertIsDisplayed(); compose.captureBoth("img-collage", flip)
    }

    @Test fun meme() {
        val vm = MemeViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { MemeScreen(host("I10"), vm) }
        vm.onFilesPicked(listOf(pic("m.jpg", 900, 700))); compose.pumpUntil { vm.preview.value != null && vm.list().isNotEmpty() }
        vm.setMeme(true, "when it compiles"); vm.setMeme(false, "on the first try"); vm.select(MemeViewModel.TOP); compose.waitForIdle()
        compose.captureBoth("img-meme", flip)
    }

    @Test fun annotate() {
        val vm = AnnotateViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { AnnotateScreen(host("I12"), vm) }
        vm.onFilesPicked(listOf(pic("a.jpg", 900, 700))); compose.pumpUntil { vm.rendered.value != null }
        vm.add(Mark.Blur(0.15f, 0.4f, 0.35f, 0.65f, false)); vm.add(Mark.Box(0.55f, 0.15f, 0.9f, 0.45f, 0xE5484D, 0.008f)); vm.add(Mark.Arrow(0.5f, 0.8f, 0.7f, 0.6f, 0xFFD60A, 0.008f)); vm.add(Mark.Text(0.1f, 0.08f, "Look here", 0xFFFFFF, 0.07f))
        compose.pumpUntil { vm.marks().size == 4 }; compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        compose.onNodeWithText("Find faces and blur them").assertIsDisplayed(); compose.captureBoth("img-annotate", flip)
    }

    @Test fun zip() {
        val a = File(tmp.root, "notes.txt").also { it.writeText("x") }; val b = File(tmp.root, "photo.jpg").also { it.writeBytes(ByteArray(2000)) }
        val vm = ZipViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { ZipScreen(host("F1"), vm) }
        vm.onFilesPicked(listOf(file(a, "text/plain"), file(b, "image/jpeg"))); compose.pumpUntil { vm.phase.value == ToolPhase.Options && !vm.loading.value }
        compose.onNodeWithText("Zip name").assertIsDisplayed(); compose.captureBoth("img-zip", flip)
        val z = File(tmp.root, "bundle.zip"); ZipOutputStream(z.outputStream()).use { o -> listOf("docs/a.txt", "docs/b.txt", "img/c.png").forEach { n -> o.putNextEntry(ZipEntry(n)); o.write(ByteArray(900)); o.closeEntry() } }
        vm.onFilesPicked(listOf(file(z, "application/zip"))); compose.pumpUntil { vm.isExtract && !vm.loading.value }
        compose.onNodeWithText("docs/a.txt").assertIsDisplayed(); compose.captureBoth("img-zip-extract", flip)
    }

    @Test fun base64() {
        val vm = Base64ViewModel(app, SavedStateHandle())
        val flip = compose.setFlippable { Base64Screen(host("I11"), vm) }
        compose.onNodeWithText("Image to Base64").assertIsDisplayed()
        vm.setTab(1); vm.setText(Base64Image.encode(ImageCodec.encodeToBytes(ImageFixtures.scene(200, 120), ImgFormat.PNG, 100), "image/png")); compose.pumpUntil { vm.decoded.value != null }
        compose.onNodeWithText("Save image").assertIsDisplayed(); compose.captureBoth("img-base64", flip)
    }
}
