package com.namanbansal.forge.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.EditImageStore
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditObject
import com.namanbansal.forge.core.pdf.edit.RedactMark
import com.namanbansal.forge.screenshots.pumpUntil
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditViewModelTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun open(file: File): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(file), file.name, file.length(), "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.restored.value }
        return vm
    }

    @Test fun editModeIsAvailableAndPresetsPickTheMatchingTool() {
        assertTrue(ViewerModeId.EDIT.available && ViewerModeId.EDIT.staysInViewer)
        for ((code, tool) in listOf("P16" to EditTool.TEXT, "P18" to EditTool.WHITEOUT, "P19" to EditTool.REDACT)) {
            assertTrue("$code available", com.namanbansal.forge.tools.ToolRegistry.get(code)!!.status == com.namanbansal.forge.tools.ToolStatus.Available)
            assertNotNull(com.namanbansal.forge.tools.ToolScreens.screens[code])
            val vm = open(PdfFixtures.create(File(tmp.root, "$code.pdf")))
            vm.startInEdit(tool)
            assertEquals(ViewerModeId.EDIT, vm.mode.value); assertEquals(tool, vm.edit.tool.value)
        }
    }

    @Test fun pendingEditsAndMarksSurviveProcessDeathAndDiscardForgetsThem() {
        val f = PdfFixtures.withText(File(tmp.root, "doc.pdf"), 2)
        val vm = open(f)
        vm.setMode(ViewerModeId.EDIT)
        vm.edit.session.add(EditObject("o1", 0, EditKind.TEXT, NormRect(0.1f, 0.5f, 0.4f, 0.55f), text = "Note", color = Color.RED))
        vm.edit.session.addMark(RedactMark("m1", 1, NormRect(0.1f, 0.1f, 0.3f, 0.15f)))
        assertTrue(vm.anyDirty.value)
        val dir = File(app.filesDir, "edit-pending")
        compose.pumpUntil { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)); dir.listFiles()?.any { it.extension == "json" } == true }
        val vm2 = open(f)
        compose.pumpUntil { !vm2.edit.session.current.isEmpty }
        assertEquals("Note", vm2.edit.session.current.items.single().text)
        assertEquals(1, vm2.edit.session.current.marks.single().page)
        assertTrue(vm2.anyDirty.value)
        vm2.discardAnnotations()
        assertFalse(vm2.anyDirty.value)
        compose.pumpUntil { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)); dir.listFiles()?.none { it.extension == "json" } != false }
    }

    private fun png(alpha: Boolean, w: Int = 60, h: Int = 30): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (alpha) { b.eraseColor(Color.TRANSPARENT); android.graphics.Canvas(b).drawRect(0f, 0f, w / 2f, h.toFloat(), android.graphics.Paint().apply { color = Color.BLUE }) } else b.eraseColor(Color.GREEN)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun imageStoreKeepsAlphaAsPngPhotosAsJpegAndDownsamples() {
        val store = EditImageStore(tmp.newFolder("imgs"))
        val a = store.import { png(true).inputStream() }!!
        assertTrue(a.hasAlpha); assertEquals(2f, a.aspect, 0.01f)
        assertTrue(store.bytes(a.id)!!.take(4) == listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47))
        val p = store.import { png(false).inputStream() }!!
        assertFalse(p.hasAlpha)
        assertEquals(0xFF.toByte(), store.bytes(p.id)!![0]) // JPEG SOI
        val big = store.import { png(false, 5000, 100).inputStream() }!!
        val bm = android.graphics.BitmapFactory.decodeByteArray(store.bytes(big.id)!!, 0, store.bytes(big.id)!!.size)
        assertTrue("downsampled to <= ${EditImageStore.MAX_SIDE}: ${bm.width}", bm.width <= EditImageStore.MAX_SIDE)
        assertNull(store.import { "not an image".byteInputStream() })
        store.retainOnly(setOf(a.id))
        assertNotNull(store.bytes(a.id)); assertNull(store.bytes(p.id)); assertNull(store.bytes(big.id))
    }
}
