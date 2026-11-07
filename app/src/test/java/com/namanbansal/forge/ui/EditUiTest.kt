package com.namanbansal.forge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditObject
import com.namanbansal.forge.core.pdf.edit.EditStyle
import com.namanbansal.forge.core.pdf.edit.RedactHit
import com.namanbansal.forge.core.pdf.edit.RedactPreset
import com.namanbansal.forge.core.pdf.edit.StoredImage
import com.namanbansal.forge.tools.pdf.ApplyRedactionsDialog
import com.namanbansal.forge.tools.pdf.EditController
import com.namanbansal.forge.tools.pdf.EditToolbar
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.RedactSearchContent
import com.namanbansal.forge.tools.pdf.RedactSearchUi
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EditUiTest {
    @get:Rule val compose = createComposeRule()

    private fun controller() = EditController({ 0.7f }, { 0 }, { 2 }, CoroutineScope(Dispatchers.Unconfined), { null }, { _, _, _ -> null }, {})

    private fun show(c: EditController, hasSel: Boolean = false, onMark: () -> Unit = {}, onPick: () -> Unit = {}, onCamera: () -> Unit = {}) =
        compose.setContent { ForgeTheme { EditToolbar(c, hasSel, onMark, onPick, onCamera) } }

    @Test fun toolbarListsTheToolsAndSwitchesBetweenThem() {
        val c = controller(); show(c)
        listOf("Existing", "Text", "Image", "Shape", "Whiteout", "Redact").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Text").performClick()
        assertEquals(EditTool.TEXT, c.tool.value)
        compose.onNodeWithContentDescription("Text").assertIsSelected()
        compose.onNodeWithContentDescription("Serif font").assertIsDisplayed()
        compose.onNodeWithContentDescription("Text").performClick()   // tapping the active tool again leaves it (back to Edit existing)
        assertEquals(EditTool.EXISTING, c.tool.value)
        compose.onNodeWithContentDescription("Serif font").assertDoesNotExist()
    }

    @Test fun textOptionsChangeFontSizeBoldAlignmentAndColour() {
        val c = controller(); c.setTool(EditTool.TEXT); show(c)
        compose.onNodeWithContentDescription("Mono font").performClick()
        compose.onNodeWithContentDescription("Bold").performClick()
        compose.onNodeWithContentDescription("Align Center").performClick()
        compose.onNodeWithContentDescription("Text size L").performClick()
        compose.onNodeWithContentDescription("Red text").performClick()
        val t = c.template.value
        assertEquals(com.namanbansal.forge.core.pdf.edit.EditFont.MONO, t.font); assertTrue(t.bold); assertEquals(1, t.align)
        assertEquals(0.036f, t.sizeFrac, 1e-4f); assertEquals(EditStyle.RED, t.color)
        compose.onNodeWithContentDescription("Mono font").assertIsSelected()
        compose.onNodeWithContentDescription("Align Left").assertIsNotSelected()
    }

    @Test fun shapeOptionsOfferKindsStrokeFillAndOpacity() {
        val c = controller(); c.setTool(EditTool.SHAPE); show(c)
        listOf("Rectangle", "Ellipse", "Line", "Arrow").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Fill opacity 50 percent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fill the shape").performClick()
        compose.onNodeWithContentDescription("Fill opacity 50 percent").assertIsSelected()
        compose.onNodeWithContentDescription("Fill opacity 75 percent").performClick()
        compose.onNodeWithContentDescription("Stroke width 8").performClick()
        compose.onNodeWithContentDescription("Green stroke").performClick()
        val t = c.template.value
        assertTrue(t.filled); assertEquals(0.75f, t.fillOpacity, 1e-4f); assertEquals(8f, t.strokeWidth, 1e-4f); assertEquals(EditStyle.GREEN, t.color)
        compose.onNodeWithContentDescription("Line").performClick()
        assertEquals(EditKind.LINE, c.shapeKind.value)
        compose.onNodeWithContentDescription("Fill the shape").assertDoesNotExist() // lines have no fill
    }

    @Test fun selectedObjectShowsItsActionsAndTheyWork() {
        val c = controller()
        val o = EditObject(c.session.newId(), 0, EditKind.RECT, NormRect(0.1f, 0.1f, 0.3f, 0.2f))
        c.session.add(o); c.select(o.id); show(c)
        listOf("Make smaller", "Make bigger", "Duplicate", "Bring to front", "Delete", "Done").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Rotate a quarter turn").assertDoesNotExist()
        compose.onNodeWithContentDescription("Duplicate").performClick()
        assertEquals(2, c.session.current.items.size)
        compose.onNodeWithContentDescription("Done").performClick()
        assertEquals(null, c.selectedId.value)
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()
        c.select(c.session.current.items.first().id); compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertEquals(1, c.session.current.items.size)
    }

    @Test fun placedImageOffersPickersRotateAndAspectLock() {
        val c = controller()
        var picked = 0; var photo = 0
        c.setTool(EditTool.IMAGE); show(c, onPick = { picked++ }, onCamera = { photo++ })
        compose.onNodeWithContentDescription("Pick a picture from the gallery").performClick()
        compose.onNodeWithContentDescription("Take a photo").performClick()
        assertEquals(1, picked); assertEquals(1, photo)
        c.placeImage(StoredImage("i1", 1.5f, false))
        compose.onNodeWithContentDescription("Rotate a quarter turn").performClick()
        assertEquals(1, c.session.current.items.single().turns)
        compose.onNodeWithContentDescription("Lock the aspect ratio").assertIsSelected()
        compose.onNodeWithContentDescription("Lock the aspect ratio").performClick()
        assertEquals(false, c.lockAspect.value)
    }

    @Test fun whiteoutOffersColoursEyedropperAndReplaceWithText() {
        val c = controller(); c.setTool(EditTool.WHITEOUT); show(c)
        compose.onNodeWithContentDescription("Cream whiteout").performClick()
        assertEquals(0xFFF5F0E1.toInt(), c.whiteColor.value)
        compose.onNodeWithContentDescription("Pick a colour from the page").performClick()
        assertTrue(c.eyedropper.value)
        compose.onNodeWithText("Tap the page where you want to copy the colour from.").assertIsDisplayed()
    }

    @Test fun redactOptionsMarkSelectionSearchAndApply() {
        val c = controller(); c.setTool(EditTool.REDACT)
        var marked = 0
        var sel by mutableStateOf(true)
        compose.setContent { ForgeTheme { EditToolbar(c, sel, { marked++; sel = false }, {}, {}) } }
        compose.onNodeWithContentDescription("Mark the selected text").performClick()
        assertEquals(1, marked)
        compose.onNodeWithContentDescription("Search and redact").performClick()
        assertTrue(c.search.value.open)
        compose.onNodeWithContentDescription("Apply redactions").assertIsNotSelected()   // nothing marked yet
        c.session.addMark(com.namanbansal.forge.core.pdf.edit.RedactMark("m", 0, NormRect(0.1f, 0.1f, 0.3f, 0.2f)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Apply redactions").performClick()
        assertTrue(c.applyConfirm.value)
        compose.onNodeWithContentDescription("Clear all marks").performClick()
        assertTrue(c.session.current.marks.isEmpty())
    }

    private val hits = listOf(
        RedactHit("0:5", 0, "jane@example.org", "Mail me at", "or call", emptyList()),
        RedactHit("2:9", 2, "bob@example.org", "cc", "thanks", emptyList()),
    )

    @Test fun reviewListTogglesHitsAndMarksTheChosen() {
        var ui by mutableStateOf(RedactSearchUi(open = true, hits = hits, on = setOf("0:5", "2:9"), done = true, label = RedactPreset.EMAIL.label))
        var presets = ArrayList<RedactPreset>(); var terms = ArrayList<String>(); var marked = 0
        compose.setContent {
            ForgeTheme {
                RedactSearchContent(ui, { presets += it }, { terms += it }, { id -> ui = ui.copy(on = if (id in ui.on) ui.on - id else ui.on + id) }, { all -> ui = ui.copy(on = if (all) hits.map { it.id }.toSet() else emptySet()) }, { marked = ui.on.size }, {})
            }
        }
        compose.onNodeWithText("2 found for Email addresses. 2 will be marked.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 1: jane@example.org").assertIsSelected()
        compose.onNodeWithContentDescription("Page 3: bob@example.org").performClick()
        compose.onNodeWithContentDescription("Page 3: bob@example.org").assertIsNotSelected()
        compose.onNodeWithText("Mark 1").performClick()
        assertEquals(1, marked)
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("Mark").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find Phone numbers").performClick()
        assertEquals(listOf(RedactPreset.PHONE), presets)
        compose.onNodeWithContentDescription("Find this text").assertExists()
        listOf("Emails", "Dates").forEach { _ -> }
        compose.onNodeWithContentDescription("Find Dates").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find Long numbers & cards").assertIsDisplayed()
    }

    @Test fun reviewListSaysWhenNothingWasFound() {
        compose.setContent { ForgeTheme { RedactSearchContent(RedactSearchUi(open = true, done = true, label = "“zebra”"), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Nothing found for “zebra”.").assertIsDisplayed()
    }

    @Test fun applyDialogWarnsAndOffersToRemoveDocumentInfo() {
        var removeInfo by mutableStateOf(false); var applied = false; var cancelled = false
        compose.setContent { ForgeTheme { ApplyRedactionsDialog(3, 2, removeInfo, { removeInfo = it }, false, { applied = true }, { cancelled = true }) } }
        compose.onNodeWithText("This permanently removes the content. A backup is kept.").assertIsDisplayed()
        compose.onNodeWithText("3 areas on 2 pages", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Also remove document info").performClick()
        assertTrue(removeInfo)
        compose.onNodeWithText("Apply redactions").performClick()
        assertTrue(applied)
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }
}
