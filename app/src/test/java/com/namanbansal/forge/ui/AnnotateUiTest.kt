package com.namanbansal.forge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotTool
import com.namanbansal.forge.tools.pdf.AnnotateToolbar
import com.namanbansal.forge.tools.pdf.ColorPicker
import com.namanbansal.forge.tools.pdf.ComingSoonContent
import com.namanbansal.forge.tools.pdf.NoteEditorContent
import com.namanbansal.forge.tools.pdf.SaveButton
import com.namanbansal.forge.tools.pdf.UnsavedChangesDialog
import com.namanbansal.forge.tools.pdf.ViewerModeBar
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.ui.theme.ForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AnnotateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modeBarShowsAllSixModesAndReportsTaps() {
        var picked: ViewerModeId? = null
        compose.setContent { ForgeTheme { ViewerModeBar(ViewerModeId.READ, { picked = it }) } }
        listOf("Read", "Annotate", "Edit", "Fill & Sign", "Pages", "More").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Read").assertIsSelected()
        compose.onNodeWithContentDescription("Fill & Sign").performClick()
        assertEquals(ViewerModeId.FILL_SIGN, picked)
        compose.onNodeWithContentDescription("Annotate").performClick()
        assertEquals(ViewerModeId.ANNOTATE, picked)
    }

    @Test fun toolPaletteSwitchesToolsAndColoursAndOnlyPensHaveThickness() {
        var tool by mutableStateOf(AnnotTool.HIGHLIGHT)
        val colours = HashMap<AnnotTool, Int>()
        var thick = -1
        compose.setContent {
            ForgeTheme {
                AnnotateToolbar(tool, AnnotTool.entries.associateWith { AnnotPalette.defaultColor(it) }, emptyMap(), { tool = it }, { t, c -> colours[t] = c }, { _, i -> thick = i })
            }
        }
        listOf("Select", "Highlight", "Underline", "Strikethrough", "Pen", "Highlighter", "Eraser", "Note").forEach { compose.onNodeWithContentDescription(it).assertExists() }
        compose.onNodeWithContentDescription("Highlight").assertIsSelected()
        compose.onNodeWithContentDescription("Thin pen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Green colour").performClick()
        assertEquals(AnnotPalette.GREEN, colours[AnnotTool.HIGHLIGHT])
        compose.onNodeWithContentDescription("Pen").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pen").assertIsSelected()
        compose.onNodeWithContentDescription("Thick pen").performClick()
        assertEquals(2, thick)
        compose.onNodeWithContentDescription("Eraser").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Red colour").assertDoesNotExist() // the eraser has no colour
    }

    @Test fun colourPickerOffersSixColoursAndMarksTheChosenOne() {
        var chosen by mutableStateOf(AnnotPalette.YELLOW)
        compose.setContent { ForgeTheme { ColorPicker(chosen, { chosen = it }) } }
        assertEquals(6, AnnotPalette.colors.size)
        AnnotPalette.names.values.forEach { compose.onNodeWithContentDescription("$it colour").assertExists() }
        compose.onNodeWithContentDescription("Yellow colour").assertIsSelected()
        compose.onNodeWithContentDescription("Ember colour").performClick()
        assertEquals(AnnotPalette.EMBER, chosen)
        compose.onNodeWithContentDescription("Ember colour").assertIsSelected()
    }

    @Test fun unsavedDialogOffersSaveDiscardCancel() {
        val log = ArrayList<String>()
        compose.setContent { ForgeTheme { UnsavedChangesDialog({ log += "save" }, { log += "discard" }, { log += "cancel" }) } }
        compose.onNodeWithText("Save your changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("discard", "cancel", "save"), log)
    }

    @Test fun noteEditorSavesTypedTextAndOffersDeleteOnlyForExistingNotes() {
        var saved: String? = null; var deleted = false
        compose.setContent { ForgeTheme { NoteEditorContent("Old", isNew = false, onSave = { saved = it }, onCancel = {}, onDelete = { deleted = true }) } }
        compose.onNodeWithText("Edit note").assertIsDisplayed()
        compose.onNodeWithContentDescription("Note text").performTextInput(" text")
        compose.onNodeWithText("Save note").performClick()
        assertEquals("Old text", saved)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, deleted)
    }

    @Test fun newNoteHasNoDelete() {
        compose.setContent { ForgeTheme { NoteEditorContent("", isNew = true, onSave = {}, onCancel = {}, onDelete = {}) } }
        compose.onNodeWithText("New note").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Type your note").assertIsDisplayed()
    }

    @Test fun saveButtonShowsTheUnsavedDot() {
        var dirty by mutableStateOf(true)
        compose.setContent { ForgeTheme { SaveButton(dirty, false) {} } }
        compose.onNode(hasContentDescription("Save, unsaved changes")).assertIsDisplayed()
        dirty = false
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Save")).assertIsDisplayed()
    }

    @Test fun comingSoonNamesTheMode() {
        compose.setContent { ForgeTheme { ComingSoonContent(ViewerModeId.EDIT, {}) } }
        compose.onNodeWithText("Edit is coming soon").assertIsDisplayed()
    }
}
