package com.namanbansal.forge.viewer

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.fill.FillItem
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.screenshots.pumpUntil
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FillSignViewModelTest {
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

    @Test fun formIsDetectedOnOpenAndPendingEditsSurviveProcessDeath() {
        val f = FormFixtures.acroForm(File(tmp.root, "form.pdf"))
        val vm = open(f)
        compose.pumpUntil { vm.fill.form.value.hasFields }
        assertTrue(vm.fill.form.value.fillable.any { it.name == "person.name" })
        assertFalse(vm.anyDirty.value)
        vm.setMode(ViewerModeId.FILL_SIGN)
        vm.fill.setValue(vm.fill.form.value.fields.first { it.name == "person.name" }, "Alice")
        vm.fill.session.addItem(FillItem("t", 0, ItemKind.TEXT, NormRect(0.1f, 0.5f, 0.4f, 0.55f), "Note"))
        assertTrue(vm.anyDirty.value)
        val dir = File(app.filesDir, "fill-pending")
        compose.pumpUntil { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)); dir.listFiles()?.any { it.extension == "json" } == true }
        // "process death": a brand new view model opens the same document and gets the edits back
        val vm2 = open(f)
        compose.pumpUntil { !vm2.fill.session.current.isEmpty }
        assertEquals("Alice", vm2.fill.session.current.overrides["person.name"])
        assertEquals("Note", vm2.fill.session.current.items.single().text)
        assertTrue(vm2.anyDirty.value)
        // discarding forgets them everywhere
        vm2.discardAnnotations()
        assertFalse(vm2.anyDirty.value)
        compose.pumpUntil { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)); dir.listFiles()?.none { it.extension == "json" } != false }
    }

    @Test fun xfaOnlyFormsAreFlaggedAndPdfsWithoutFieldsHaveNone() {
        val x = open(FormFixtures.acroForm(File(tmp.root, "x.pdf"), xfaOnly = true))
        compose.pumpUntil { x.fill.form.value.hasAcroForm }
        assertTrue(x.fill.form.value.xfaOnly)
        val plain = open(com.namanbansal.forge.core.PdfFixtures.create(File(tmp.root, "plain.pdf")))
        compose.waitForIdle()
        assertFalse(plain.fill.form.value.hasFields)
        assertNotNull(plain.signatures)
    }

    @Test fun deletingASignatureThatIsPlacedIsRefusedUntilItIsRemoved() {
        val vm = open(com.namanbansal.forge.core.PdfFixtures.create(File(tmp.root, "plain.pdf")))
        val e = vm.signatures.add(com.namanbansal.forge.core.pdf.sign.SigKind.SIGNATURE, run {
            val b = android.graphics.Bitmap.createBitmap(30, 10, android.graphics.Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.BLACK)
            com.namanbansal.forge.core.pdf.sign.SignatureImage.toPng(b)
        })
        vm.refreshSignatures(); compose.pumpUntil { vm.sigEntries.value.isNotEmpty() }
        vm.fill.session.addItem(FillItem("s", 0, ItemKind.SIGNATURE, NormRect(0.2f, 0.2f, 0.5f, 0.3f), sigId = e.id))
        vm.deleteSignature(e)
        compose.waitForIdle()
        assertEquals(1, vm.signatures.list().size)
        vm.fill.session.removeItem("s")
        vm.deleteSignature(e)
        compose.pumpUntil { vm.signatures.list().isEmpty() }
    }
}
