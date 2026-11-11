package com.namanbansal.forge.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.pdf.edit.content.CoverageFixtures
import com.namanbansal.forge.core.pdf.edit.content.ExistingIndex
import com.namanbansal.forge.core.pdf.edit.content.FontOpener
import com.namanbansal.forge.core.pdf.edit.content.LockReason
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.form.FormField
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.tools.pdf.EditController
import com.namanbansal.forge.tools.pdf.ExBlockRef
import com.namanbansal.forge.tools.pdf.ExRoute
import com.namanbansal.forge.tools.pdf.PageHit
import com.namanbansal.forge.tools.pdf.WhyLocked
import com.namanbansal.forge.tools.pdf.WhyLockedContent
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.io.File

/** Round 2F in the viewer: why text is locked, routing of form fields and comment boxes, replace-only text, the debug overlay data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverageUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val fonts = FontOpener { File("src/main/assets/$it").inputStream() }
    private var index: ExistingIndex? = null
    private val messages = ArrayList<String>()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }
    @After fun done() { index?.close() }

    private fun controller(f: File): EditController {
        val ix = ExistingIndex(f, fonts).also { index = it }
        return EditController({ 0.77f }, { 0 }, { 1 }, CoroutineScope(Dispatchers.Unconfined), { null }, { _, _, _ -> null }, { messages += it },
            loadObjects = { p -> ix.page(p) }, loadDebug = { p -> ix.debugBoxes(p) })
    }

    private fun center(r: NormRect) = PageHit(0, (r.left + r.right) / 2, (r.top + r.bottom) / 2, 0.77f)

    @Test fun tappingOutlinedTextExplainsWhy() {
        val c = controller(CoverageFixtures.outlined(tmp.newFile("o.pdf")))
        c.existing.ensure(0)
        val locked = c.existing.objects.value.getValue(0).locked.single()
        assertTrue(c.existing.onTap(center(locked.rect)))
        val why = c.existing.why.value!!
        assertEquals(LockReason.OUTLINES.title, why.title)
        assertEquals(LockReason.OUTLINES.detail, why.detail)
        c.existing.dismissWhy(); assertNull(c.existing.why.value)
    }

    @Test fun tappingBlankPaperExplainsNothing() {
        val c = controller(CoverageFixtures.outlined(tmp.newFile("o.pdf")))
        c.existing.ensure(0)
        assertFalse(c.existing.onTap(PageHit(0, 0.9f, 0.9f, 0.77f)))
        assertNull(c.existing.why.value)
    }

    @Test fun tappingACommentBoxOffersAnnotate() {
        val c = controller(CoverageFixtures.withFreeText(tmp.newFile("ft.pdf")))
        c.existing.ensure(0)
        var routed: ExRoute? = null
        c.existing.onRoute = { routed = it }
        val ft = c.existing.objects.value.getValue(0).freeText.single()
        assertTrue(c.existing.onTap(center(ft.rect)))
        val why = c.existing.why.value!!
        assertEquals(ExRoute.Annotate, why.route)
        c.existing.onRoute(why.route!!)
        assertEquals(ExRoute.Annotate, routed)
    }

    @Test fun tappingAFormFieldRoutesToFillAndSign() {
        val c = controller(CoverageFixtures.withFreeText(tmp.newFile("f.pdf")))
        c.existing.ensure(0)
        val field = FormField("f1", "name", FieldKind.TEXT, 0, NormRect(0.1f, 0.6f, 0.5f, 0.65f), value = "Ada")
        c.existing.formFieldsOf = { listOf(field) }
        var routed: ExRoute? = null
        c.existing.onRoute = { routed = it }
        assertTrue(c.existing.onTap(center(field.rect)))
        assertEquals(ExRoute.FillSign(field), routed)
        assertNull("no sheet: it goes straight to the form", c.existing.why.value)
    }

    @Test fun unreadableTextOpensEmptyAndIsReplacedByWhatIsTyped() {
        val c = controller(CoverageFixtures.cidNoToUnicode(tmp.newFile("lost.pdf"), listOf("Invoice 2026-0042 total due 1,250.00"), subset = true))
        c.existing.ensure(0)
        val b = c.existing.objects.value.getValue(0).blocks.single()
        assertTrue(b.unreadable)
        val ref = ExBlockRef(0, b.key)
        c.existing.openEditor(ref)
        assertEquals("", c.existing.draft)
        assertTrue(c.existing.replaceOnly.value)
        assertTrue(messages.last().contains("can't read this text"))
        c.existing.draft = "Total due 980.50"
        c.existing.closeEditor(true)
        assertEquals("Total due 980.50", c.existing.editOf(ref)!!.text)
    }

    @Test fun closingAnEmptyReplaceOnlyEditorLeavesTheTextAlone() {
        val c = controller(CoverageFixtures.cidNoToUnicode(tmp.newFile("lost.pdf"), listOf("Invoice 2026-0042"), subset = true))
        c.existing.ensure(0)
        val ref = ExBlockRef(0, c.existing.objects.value.getValue(0).blocks.single().key)
        c.existing.openEditor(ref); c.existing.closeEditor(true)
        assertNull(c.existing.editOf(ref))
    }

    @Test fun readableTextStillOpensWithItsOwnWords() {
        val c = controller(CoverageFixtures.mixedParagraph(tmp.newFile("m.pdf"), listOf(CoverageFixtures.Span("Hello there world"))))
        c.existing.ensure(0)
        val b = c.existing.objects.value.getValue(0).blocks.single()
        c.existing.openEditor(ExBlockRef(0, b.key))
        assertEquals("Hello there world", c.existing.draft)
        assertFalse(c.existing.replaceOnly.value)
    }

    @Test fun debugOverlayLoadsPerGlyphBoxesOnlyWhenAsked() {
        val c = controller(CoverageFixtures.outlined(tmp.newFile("o.pdf")))
        assertFalse(c.existing.debugOn.value)
        assertTrue(c.existing.debug.value.isEmpty())
        c.existing.setDebug(true); c.existing.ensureDebug(0)
        val boxes = c.existing.debug.value.getValue(0)
        assertTrue(boxes.any { it.editable } && boxes.any { !it.editable })
    }

    @Test fun whySheetShowsReasonAndOptionalButton() {
        var routed: ExRoute? = null; var closed = false
        compose.setContent { ForgeTheme { WhyLockedContent(WhyLocked("Rotated text", "Turned too far.", ExRoute.Annotate, "Open Annotate"), { routed = it }, { closed = true }) } }
        compose.onNodeWithText("Why can't I edit this?").assertExists()
        compose.onNodeWithText("Rotated text").assertExists()
        compose.onNodeWithText("Turned too far.").assertExists()
        compose.onNodeWithText("Open Annotate").performClick()
        assertEquals(ExRoute.Annotate, routed)
        compose.onNodeWithText("Got it").performClick()
        assertTrue(closed)
    }
}
