package com.namanbansal.forge.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.image.*
import com.namanbansal.forge.tools.image.*
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Whole image tools: pick -> options -> save through a fake MediaStore -> read the saved file back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageToolsE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var media: FakeMediaProvider

    @Before fun init() {
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        media.dir = tmp.newFolder("media")
        SegmenterProvider.factory = { FakeSegmenter() }
        FaceFinders.factory = { object : FaceFinder { override suspend fun find(bmp: Bitmap) = listOf(FRect(0.1f, 0.1f, 0.3f, 0.3f)) } }
    }

    class FakeSegmenter : SubjectSegmenter {
        override val engine = "Test model"
        override suspend fun segment(bmp: Bitmap): ByteArray = ByteArray(bmp.width * bmp.height) { i -> val x = i % bmp.width; val y = i / bmp.width; if (x in bmp.width / 4 until bmp.width * 3 / 4 && y in bmp.height / 4 until bmp.height * 3 / 4) 255.toByte() else 0 }
    }

    private fun pump(timeoutMs: Long = 60_000, until: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not reached in time", until())
    }

    private fun jpg(name: String, w: Int = 1200, h: Int = 800) = pick(ImageFixtures.write(tmp.root, name, ImageFixtures.scene(w, h)), "image/jpeg")
    private fun pick(f: File, mime: String) = PickedFile(Uri.fromFile(f), f.name, f.length(), mime)

    private fun open(vm: ToolViewModel, vararg files: PickedFile) {
        vm.onFilesPicked(files.toList())
        pump { vm.phase.value == ToolPhase.Options && !vm.loading.value }
    }

    private fun run(vm: ToolViewModel) {
        vm.start()
        pump { vm.phase.value == ToolPhase.Result || vm.phase.value == ToolPhase.Failed }
        assertEquals(vm.error.value, ToolPhase.Result, vm.phase.value)
    }

    private fun outFiles(vm: ToolViewModel): List<File> = vm.result.value!!.outputs.map { o -> media.rows.values.first { it.values.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME) == o.displayName }.file }
    private fun bmp(f: File) = BitmapFactory.decodeFile(f.absolutePath)!!

    @Test fun resizePixelsPercentPresetAndCropAndBatch() {
        val vm = ResizeViewModel(app, SavedStateHandle())
        open(vm, jpg("a.jpg"))
        pump { vm.preview.value != null }
        vm.setWidth(600)
        assertEquals(400, vm.height.value) // proportions locked
        run(vm)
        assertEquals(600 to 400, bmp(outFiles(vm).single()).let { it.width to it.height })
        assertTrue(vm.result.value!!.output.folder.startsWith("Pictures/Forge"))
        assertEquals("a_resized.jpg", vm.result.value!!.output.displayName)

        vm.backToOptions(); vm.setMode(ResizeMode.PRESET); vm.setPreset("passport"); run(vm)
        assertEquals(413 to 531, bmp(outFiles(vm).single()).let { it.width to it.height })

        vm.backToOptions(); vm.setMode(ResizeMode.PERCENT); vm.setPercent(25); vm.rotate(true); run(vm) // rotated right: 800x1200 -> 25 %
        assertEquals(200 to 300, bmp(outFiles(vm).single()).let { it.width to it.height })

        vm.backToOptions(); vm.rotate(false); vm.setMode(ResizeMode.CROP); vm.setCrop(0f, 0f, 0.5f, 1f); run(vm)
        assertEquals(600 to 800, bmp(outFiles(vm).single()).let { it.width to it.height })

        val batch = ResizeViewModel(app, SavedStateHandle())
        open(batch, jpg("b1.jpg", 800, 600), jpg("b2.jpg", 400, 800))
        batch.setMode(ResizeMode.PIXELS); batch.setWidth(200)
        run(batch)
        assertEquals(listOf(200 to 150, 200 to 400), outFiles(batch).map { bmp(it).let { b -> b.width to b.height } })
        assertEquals(2, batch.result.value!!.outputs.size)
    }

    @Test fun compressTargetSizeAndPng() {
        val vm = CompressViewModel(app, SavedStateHandle())
        open(vm, jpg("c.jpg"))
        vm.setTarget(true); vm.setTargetKb(30)
        run(vm)
        assertTrue(outFiles(vm).single().length() <= 30 * 1024)
        pump { vm.after.value != null }

        val png = pick(File(tmp.root, "p.png").also { it.writeBytes(ImageCodec.encodeToBytes(ImageFixtures.scene(300, 200), ImgFormat.PNG, 100)) }, "image/png")
        val pv = CompressViewModel(app, SavedStateHandle())
        open(pv, png); run(pv)
        assertEquals("image/png", pv.result.value!!.output.mime)
        assertTrue(pv.result.value!!.output.size <= png.size)
        assertEquals(300, bmp(outFiles(pv).single()).width)
    }

    @Test fun convertToWebpIcoAndSvg() {
        val vm = ConvertViewModel(app, SavedStateHandle())
        open(vm, jpg("v.jpg", 300, 200))
        vm.setFormat(ImgFormat.ICO); run(vm)
        assertEquals(listOf(16, 32, 48, 64, 128, 256), IcoWriter.parse(outFiles(vm).single().readBytes()).map { it.width })
        vm.backToOptions(); vm.setFormat(ImgFormat.WEBP_LOSSLESS); run(vm)
        assertEquals("image/webp", vm.result.value!!.output.mime)
        val svg = File(tmp.root, "logo.svg").also { it.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 40 20"><rect width="40" height="20" fill="#0a0"/></svg>""") }
        val sv = ConvertViewModel(app, SavedStateHandle())
        open(sv, pick(svg, "image/svg+xml")); sv.setSvgSize(512); sv.setFormat(ImgFormat.PNG); run(sv)
        assertEquals(512 to 256, bmp(outFiles(sv).single()).let { it.width to it.height })
    }

    @Test fun stripRemovesGpsLosslessly() {
        val src = File(tmp.root, "g.jpg").also { it.writeBytes(ImageFixtures.jpegWithExif(tmp.root)) }
        val vm = StripViewModel(app, SavedStateHandle())
        open(vm, pick(src, "image/jpeg"))
        pump { vm.meta.value != null }
        assertTrue(MetadataStripper.hasLocation(vm.meta.value!!))
        assertEquals(1, vm.withLocation.value)
        run(vm)
        val out = outFiles(vm).single().readBytes()
        assertFalse(MetadataStripper.hasLocation(MetadataStripper.read(out)))
        assertEquals("g_clean.jpg", vm.result.value!!.output.displayName)
        vm.backToOptions(); vm.setMode(StripMode.LOCATION); run(vm)
        assertTrue(MetadataStripper.read(outFiles(vm).single().readBytes()).any { it.label == "Make" })
    }

    @Test fun paletteExportAndUpscale() {
        val vm = PaletteViewModel(app, SavedStateHandle())
        open(vm, jpg("pal.jpg", 400, 300))
        pump { vm.palette.value.isNotEmpty() }
        assertEquals(6, vm.palette.value.size); assertTrue(vm.hasPick())
        vm.setCount(8); assertEquals(8, vm.palette.value.size)
        run(vm)
        assertEquals(1200, bmp(outFiles(vm).single()).width)

        val up = UpscaleViewModel(app, SavedStateHandle())
        open(up, jpg("small.jpg", 120, 80))
        up.setFactor(4); up.setSharpen(2); run(up)
        assertEquals(480 to 320, bmp(outFiles(up).single()).let { it.width to it.height })
        val big = UpscaleViewModel(app, SavedStateHandle())
        open(big, jpg("bigger.jpg", 4000, 3000))
        assertFalse(big.fits(4)); assertFalse(big.fits(2)) // 12 MP: even 2x would pass the 30 MP cap
    }

    @Test fun collageMemeAnnotateAndBackground() {
        val cl = CollageViewModel(app, SavedStateHandle())
        open(cl, jpg("1.jpg", 600, 400), jpg("2.jpg", 400, 600), jpg("3.jpg", 500, 500))
        pump { cl.previews.value.size == 3 }
        assertTrue(cl.templates().isNotEmpty()); assertNotNull(cl.renderPreview())
        run(cl)
        assertEquals(1080 to 1080, bmp(outFiles(cl).single()).let { it.width to it.height })
        val one = CollageViewModel(app, SavedStateHandle())
        one.onFilesPicked(listOf(jpg("only.jpg"), jpg("only2.jpg")))
        pump { one.phase.value == ToolPhase.Options && !one.loading.value }
        val bad = CollageViewModel(app, SavedStateHandle())
        bad.onFilesPicked(listOf(jpg("lonely.jpg")))
        pump { !bad.loading.value }; pump(2000) { bad.phase.value == ToolPhase.Pick }

        val mv = MemeViewModel(app, SavedStateHandle())
        val mf = jpg("meme.jpg", 800, 600)
        open(mv, mf); pump { mv.preview.value != null && mv.list().isNotEmpty() }
        val before = bmp(File(Uri.parse(mf.uri.toString()).path!!))
        mv.setMeme(true, "hello world"); mv.setMeme(false, "second line")
        run(mv)
        val after = bmp(outFiles(mv).single())
        val region = IntArray(400 * 40).also { after.getPixels(it, 0, 400, 200, 10, 400, 40) }
        val region0 = IntArray(400 * 40).also { before.getPixels(it, 0, 400, 200, 10, 400, 40) }
        assertFalse("top caption is drawn", region.contentEquals(region0))

        val an = AnnotateViewModel(app, SavedStateHandle())
        val af = jpg("an.jpg", 800, 600)
        open(an, af); pump { an.rendered.value != null }
        an.add(Mark.Blur(0.1f, 0.1f, 0.4f, 0.4f, pixelate = true)); an.add(Mark.Arrow(0.5f, 0.5f, 0.9f, 0.9f, 0xE5484D, 0.01f)); an.add(Mark.Text(0.5f, 0.1f, "hi", 0xFFFFFF, 0.06f))
        run(an)
        val aa = bmp(outFiles(an).single()); val ao = bmp(File(af.uri.path!!))
        val ra = IntArray(80 * 60).also { aa.getPixels(it, 0, 80, 100, 80, 80, 60) }; val rb = IntArray(80 * 60).also { ao.getPixels(it, 0, 80, 100, 80, 80, 60) }
        assertFalse(ra.contentEquals(rb))
        an.backToOptions(); an.clear(); an.findFaces(); pump { an.marks().isNotEmpty() }
        assertTrue(an.marks().single() is Mark.Blur)

        val bg = BgRemoveViewModel(app, SavedStateHandle())
        open(bg, jpg("bg.jpg", 800, 600))
        pump { bg.status.value is BgStatus.Ready && bg.composite.value != null }
        bg.setRestoring(false); bg.stroke(0.5f, 0.5f)
        run(bg)
        val bo = bmp(outFiles(bg).single())
        assertEquals("transparent corner", 0, bo.getPixel(2, 2) ushr 24)
        assertEquals("opaque centre not erased", 255, bo.getPixel(bo.width / 3, bo.height / 3) ushr 24)
    }

    @Test fun zipCreateExtractZipSlipAndEncrypted() {
        val a = File(tmp.root, "a.txt").also { it.writeText("alpha") }; val b = File(tmp.root, "b.txt").also { it.writeText("beta") }
        val vm = ZipViewModel(app, SavedStateHandle())
        open(vm, pick(a, "text/plain"), pick(b, "text/plain"))
        vm.setName("My files"); run(vm)
        val zip = outFiles(vm).single()
        assertEquals("My files.zip", vm.result.value!!.output.displayName)
        assertEquals(listOf("a.txt", "b.txt"), ZipTools.list(zip).map { it.path })

        val evil = File(tmp.root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z -> listOf("ok/inside.txt" to "fine", "../escape.txt" to "bad").forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d.toByteArray()); z.closeEntry() } }
        val ex = ZipViewModel(app, SavedStateHandle())
        open(ex, pick(evil, "application/zip"))
        assertTrue(ex.isExtract); assertEquals(setOf(0), ex.selected.value) // the unsafe entry is not selected
        run(ex)
        val names = ex.result.value!!.outputs.map { it.displayName }
        assertEquals(listOf("inside.txt"), names)
        val row = media.rows.values.single { it.values.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME) == "inside.txt" }
        assertEquals("Download/Forge/evil/ok/", row.values.getAsString(android.provider.MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("fine", row.file.readText())
        assertTrue(media.rows.values.none { it.values.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME) == "escape.txt" })

        val enc = File(tmp.root, "enc.zip").also { f -> f.writeBytes(evil.readBytes().also { bytes -> for (i in 0 until bytes.size - 8) { if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte()) { if (bytes[i + 2].toInt() == 3 && bytes[i + 3].toInt() == 4) bytes[i + 6] = (bytes[i + 6].toInt() or 1).toByte(); if (bytes[i + 2].toInt() == 1 && bytes[i + 3].toInt() == 2) bytes[i + 8] = (bytes[i + 8].toInt() or 1).toByte() } } }) }
        val ev = ZipViewModel(app, SavedStateHandle())
        ev.onFilesPicked(listOf(pick(enc, "application/zip")))
        pump { !ev.loading.value }
        assertEquals(ToolPhase.Pick, ev.phase.value)
    }

    @Test fun base64ViewModelRoundTripAndSave() {
        val f = File(tmp.root, "i.png").also { it.writeBytes(ImageCodec.encodeToBytes(ImageFixtures.scene(64, 48), ImgFormat.PNG, 100)) }
        val vm = Base64ViewModel(app, SavedStateHandle())
        vm.encode(Uri.fromFile(f))
        pump { vm.encoded.value != null }
        val e = vm.encoded.value!!
        assertTrue(e.dataUri.startsWith("data:image/png;base64,"))
        vm.setText(e.dataUri)
        pump { vm.decoded.value != null || vm.decodeError.value != null }
        assertNull(vm.decodeError.value)
        assertArrayEquals(f.readBytes(), vm.decoded.value!!.first.bytes)
        var msg = ""
        vm.saveImage { msg = it }
        pump { vm.savedOut.value != null }
        assertEquals("decoded_image.png", vm.savedOut.value!!.displayName)
        vm.setText("%%% not base64")
        pump { vm.decodeError.value != null }
    }
}
