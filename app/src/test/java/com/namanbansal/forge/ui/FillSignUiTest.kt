package com.namanbansal.forge.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.fill.FillItem
import com.namanbansal.forge.core.pdf.fill.FillSaveOptions
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.form.FormField
import com.namanbansal.forge.core.pdf.form.FormInfo
import com.namanbansal.forge.core.pdf.sign.SigEntry
import com.namanbansal.forge.core.pdf.sign.SigKind
import com.namanbansal.forge.tools.pdf.AddMethodContent
import com.namanbansal.forge.tools.pdf.ChoiceContent
import com.namanbansal.forge.tools.pdf.FieldTextContent
import com.namanbansal.forge.tools.pdf.FillSignController
import com.namanbansal.forge.tools.pdf.FillSignToolbar
import com.namanbansal.forge.tools.pdf.PageHit
import com.namanbansal.forge.tools.pdf.SaveOptionsContent
import com.namanbansal.forge.tools.pdf.SignatureLibraryContent
import com.namanbansal.forge.tools.pdf.SignaturePadContent
import com.namanbansal.forge.tools.pdf.TypeSignatureContent
import com.namanbansal.forge.ui.theme.ForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FillSignUiTest {
    @get:Rule val compose = createComposeRule()

    private fun field(id: String, name: String, kind: FieldKind, top: Float, page: Int = 0, required: Boolean = false, maxLen: Int = -1, options: List<String> = emptyList(), value: String = "") =
        FormField(id, name, kind, page, NormRect(0.1f, top, 0.6f, top + 0.04f), required = required, maxLen = maxLen, options = options, value = value)

    private fun controller(vararg f: FormField, pages: Int = 2): FillSignController {
        val c = FillSignController(pageAspect = { 0.707f }, currentPage = { 0 }, sigAspect = { 3f }, message = {})
        c.setForm(FormInfo(f.toList(), hasAcroForm = true))
        return c
    }

    @Test fun fieldSheetShowsRequiredAndMaxLengthAndReturnsTheText() {
        var done: String? = null; var next: String? = null
        val f = field("f1", "person.name", FieldKind.TEXT, 0.1f, required = true, maxLen = 5)
        compose.setContent { ForgeTheme { FieldTextContent(f, "Al", hasNext = true, onDone = { done = it }, onNext = { next = it }, onCancel = {}) } }
        compose.onNodeWithText("name").assertIsDisplayed()
        compose.onNodeWithText("Required", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 / 5 characters").assertIsDisplayed()
        compose.onNodeWithContentDescription("name value").performTextInput("ice and more")
        compose.waitForIdle()
        compose.onNodeWithText("5 / 5 characters").assertIsDisplayed() // max length is enforced while typing
        compose.onNodeWithText("Next").performClick()
        assertEquals("Alice", next)
        compose.onNodeWithText("Done").performClick()
        assertEquals("Alice", done)
    }

    @Test fun dropdownListsOptionsAndPicks() {
        var picked: String? = null
        val f = field("f2", "fruit", FieldKind.COMBO, 0.2f, options = listOf("Apple", "Banana"), value = "Apple")
        compose.setContent { ForgeTheme { ChoiceContent(f, "Apple", { picked = it }, {}) } }
        compose.onNodeWithContentDescription("Apple").assertIsSelected()
        compose.onNodeWithContentDescription("Banana").performClick()
        assertEquals("Banana", picked)
    }

    @Test fun nextFieldWalksFillableFieldsInOrderAndWraps() {
        val c = controller(
            field("a", "one", FieldKind.TEXT, 0.1f), field("b", "two", FieldKind.CHECKBOX, 0.2f), field("c", "three", FieldKind.TEXT, 0.1f, page = 1),
            FormField("ro", "locked", FieldKind.TEXT, 0, NormRect(0.1f, 0.3f, 0.5f, 0.33f), readOnly = true),
        )
        compose.setContent { ForgeTheme { FillSignToolbar(c, null) } }
        compose.onNodeWithText("3 fields to fill", substring = true).assertIsDisplayed() // the read-only one is not counted
        compose.onNodeWithContentDescription("Next field").performClick()
        assertEquals("a", c.focusId.value)
        compose.onNodeWithContentDescription("Next field").performClick()
        compose.onNodeWithContentDescription("Next field").performClick()
        assertEquals("c", c.focusId.value); assertEquals(1, c.jump.value.first)
        compose.onNodeWithContentDescription("Next field").performClick()
        assertEquals("a", c.focusId.value) // wraps
        compose.onNodeWithContentDescription("Previous field").performClick()
        assertEquals("c", c.focusId.value)
    }

    @Test fun toolbarHintsForPdfsWithoutFormsOrWithXfa() {
        val none = FillSignController({ 0.7f }, { 0 }, { null }, {})
        compose.setContent { ForgeTheme { FillSignToolbar(none, null) } }
        compose.onNodeWithText("No form fields", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next field").assertDoesNotExist()
    }

    @Test fun xfaMessageIsShown() {
        val xfa = FillSignController({ 0.7f }, { 0 }, { null }, {}).also { it.setForm(FormInfo(hasAcroForm = true, xfaOnly = true)) }
        compose.setContent { ForgeTheme { FillSignToolbar(xfa, null) } }
        compose.onNodeWithText("XFA", substring = true).assertIsDisplayed()
    }

    @Test fun addTextToolPlacesABoxOnTapAndQuickStampsWork() {
        val c = controller()
        compose.setContent { ForgeTheme { FillSignToolbar(c, null) } }
        compose.onNodeWithContentDescription("Add text").performClick()
        compose.onNodeWithContentDescription("Add today's date").assertIsDisplayed()
        assertTrue(c.onTap(PageHit(0, 0.4f, 0.4f, 0.707f)))
        assertEquals(ItemKind.TEXT, c.session.current.items.single().kind)
        assertNotNull(c.editingItem.value)
        c.closeItemEdit("Hello")
        assertEquals("Hello", c.session.current.items.single().text)
        c.select(null)
        compose.onNodeWithContentDescription("Add a check mark").performClick(); c.select(null); compose.waitForIdle()
        compose.onNodeWithContentDescription("Add a cross").performClick(); c.select(null); compose.waitForIdle()
        compose.onNodeWithContentDescription("Add today's date").performClick()
        assertEquals(listOf(ItemKind.TEXT, ItemKind.CHECK, ItemKind.CROSS, ItemKind.TEXT), c.session.current.items.map { it.kind })
        c.session.undo(); c.session.undo(); c.session.undo()
        assertEquals(1, c.session.current.items.size)
    }

    @Test fun emptyTextBoxIsDiscardedAndSizeAndColourApplyToTheSelectedBox() {
        val c = controller()
        c.setTool(com.namanbansal.forge.tools.pdf.FillTool.TEXT)
        c.onTap(PageHit(0, 0.4f, 0.4f, 0.707f)); c.closeItemEdit("")
        assertTrue(c.session.current.items.isEmpty()); assertFalse(c.session.canUndo.value) // nothing left to undo
        c.onTap(PageHit(0, 0.4f, 0.4f, 0.707f)); c.closeItemEdit("Hi")
        compose.setContent { ForgeTheme { FillSignToolbar(c, null) } }
        compose.onNodeWithContentDescription("Text size L").performClick()
        compose.onNodeWithContentDescription("Blue text").performClick()
        val it = c.session.current.items.single()
        assertEquals(2, it.size); assertEquals(com.namanbansal.forge.core.pdf.fill.FillPalette.BLUE, it.color)
    }

    @Test fun placementHandlesMoveResizeRotateAndDelete() {
        val c = controller()
        c.session.addItem(FillItem("s", 0, ItemKind.SIGNATURE, NormRect(0.3f, 0.4f, 0.6f, 0.4f + 0.3f * 0.707f / 3f), sigId = "x"))
        c.select("s")
        compose.setContent { ForgeTheme { FillSignToolbar(c, null) } }
        // drag inside the box moves it; only that drag is claimed
        assertTrue(c.claimsAt(PageHit(0, 0.4f, 0.45f, 0.707f))); assertFalse(c.claimsAt(PageHit(0, 0.9f, 0.9f, 0.707f)))
        c.onStart(PageHit(0, 0.4f, 0.45f, 0.707f)); c.onMove(0.5f, 0.55f); c.onEnd()
        assertEquals(0.4f, c.session.current.items.single().rect.left, 1e-4f)
        // dragging the corner dot resizes and keeps the shape
        val r0 = c.session.current.items.single().rect
        c.onStart(PageHit(0, r0.right, r0.bottom, 0.707f)); c.onMove(r0.right + 0.2f, r0.bottom + 0.05f); c.onEnd()
        val r1 = c.session.current.items.single().rect
        assertTrue(r1.right - r1.left > r0.right - r0.left)
        assertEquals((r0.bottom - r0.top) / (r0.right - r0.left), (r1.bottom - r1.top) / (r1.right - r1.left), 1e-3f)
        compose.onNodeWithContentDescription("Rotate a quarter turn").performClick()
        assertEquals(1, c.session.current.items.single().turns)
        compose.onNodeWithContentDescription("Make smaller").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertTrue(c.session.current.items.isEmpty()); assertNull(c.selectedId.value)
        c.session.undo(); c.session.undo(); c.session.undo() // all of it is undoable
        assertEquals(0, c.session.current.items.single().turns)
    }

    @Test fun tappingASignatureFieldFitsTheSelectedSignatureIntoIt() {
        val sigField = FormField("sg", "signature", FieldKind.SIGNATURE, 0, NormRect(0.1f, 0.7f, 0.5f, 0.76f))
        val c = controller(sigField)
        c.session.addItem(FillItem("s", 0, ItemKind.SIGNATURE, NormRect(0.3f, 0.2f, 0.6f, 0.3f), sigId = "x")); c.select("s")
        c.onTap(PageHit(0, 0.2f, 0.73f, 0.707f))
        val r = c.session.current.items.single().rect
        assertTrue(r.left >= 0.1f && r.right <= 0.5f && r.top >= 0.7f && r.bottom <= 0.76f)
        // with nothing placed the library opens for that field
        val c2 = controller(sigField)
        c2.onTap(PageHit(0, 0.2f, 0.73f, 0.707f))
        assertEquals("sg", c2.signTarget.value)
    }

    @Test fun togglesFlipOnTapAndReadOnlyFieldsSayWhy() {
        val msgs = ArrayList<String>()
        val cb = FormField("c", "agree", FieldKind.CHECKBOX, 0, NormRect(0.1f, 0.1f, 0.14f, 0.12f), onValue = "Yes", value = "Off")
        val ro = FormField("r", "locked", FieldKind.TEXT, 0, NormRect(0.1f, 0.3f, 0.5f, 0.33f), readOnly = true)
        val c = FillSignController({ 0.7f }, { 0 }, { null }, { msgs += it }).also { it.setForm(FormInfo(listOf(cb, ro), hasAcroForm = true)) }
        c.onTap(PageHit(0, 0.11f, 0.11f, 0.7f)); assertEquals("Yes", c.session.current.overrides["agree"])
        c.onTap(PageHit(0, 0.11f, 0.11f, 0.7f)); assertTrue(c.session.current.overrides.isEmpty())
        c.onTap(PageHit(0, 0.2f, 0.31f, 0.7f)); assertNull(c.fieldEdit.value); assertTrue(msgs.single().contains("read-only"))
    }

    // ---- signatures ----
    private fun bmp(): androidx.compose.ui.graphics.ImageBitmap = Bitmap.createBitmap(60, 20, Bitmap.Config.ARGB_8888).also { it.eraseColor(AColor.BLACK) }.asImageBitmap()

    @Test fun libraryListsPicksDeletesAndEnforcesTheLimit() {
        val sigs = (1..5).map { SigEntry("s$it", SigKind.SIGNATURE, "Sig $it", 3f, it.toLong()) }
        var picked: String? = null; var deleted: String? = null; var added: SigKind? = null
        compose.setContent { ForgeTheme { SignatureLibraryContent(sigs, { bmp() }, canAddSignature = false, { picked = it.id }, { added = it }, { deleted = it.id }, {}) } }
        compose.onNodeWithText("You can keep up to 5 signatures", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Use Sig 2").performClick(); assertEquals("s2", picked)
        compose.onNodeWithContentDescription("Delete Sig 3").performClick(); assertEquals("s3", deleted)
        compose.onNodeWithContentDescription("New signature").performClick(); assertNull(added) // disabled at five
        compose.onNodeWithContentDescription("Add initials").performClick(); assertEquals(SigKind.INITIALS, added)
    }

    @Test fun emptyLibraryExplainsHowToStartAndTheHelpTextIsHonest() {
        compose.setContent { ForgeTheme { SignatureLibraryContent(emptyList(), { null }, true, {}, {}, {}, {}) } }
        compose.onNodeWithText("No signatures yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("handwritten signature", substring = true).assertIsDisplayed()
        compose.onNodeWithText("not a certificate-based digital signature", substring = true).assertIsDisplayed()
    }

    @Test fun addMethodOffersDrawTypeImport() {
        val got = ArrayList<String>()
        compose.setContent { ForgeTheme { AddMethodContent(SigKind.SIGNATURE, { got += "draw" }, { got += "type" }, { got += "import" }, {}) } }
        compose.onNodeWithContentDescription("Draw").performClick(); compose.onNodeWithContentDescription("Type").performClick(); compose.onNodeWithContentDescription("Import a photo").performClick()
        assertEquals(listOf("draw", "type", "import"), got)
    }

    @Test fun padDrawsUndoesClearsAndSaves() {
        var saved: Bitmap? = null
        compose.setContent { ForgeTheme { SignaturePadContent(SigKind.SIGNATURE, { saved = it }, {}) } }
        compose.onNodeWithText("Sign here").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drawing area").performTouchInput { swipe(Offset(100f, 300f), Offset(500f, 420f), 200) }
        compose.waitForIdle()
        compose.onNodeWithText("Sign here").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drawing area").performTouchInput { swipe(Offset(120f, 500f), Offset(520f, 380f), 200) }
        compose.onNodeWithContentDescription("Undo stroke").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Sign here").assertDoesNotExist() // one stroke left
        compose.onNodeWithContentDescription("Dark blue ink").performClick()
        compose.onNodeWithContentDescription("Pen thickness 3").performClick()
        compose.onNodeWithText("Save").performClick()
        assertNotNull(saved); assertTrue(saved!!.width > 50)
        compose.onNodeWithText("Clear").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Sign here").assertIsDisplayed()
    }

    @Test fun typeSignatureNeedsANameAndOffersThreeFonts() {
        var saved: Bitmap? = null
        compose.setContent { ForgeTheme { TypeSignatureContent(SigKind.SIGNATURE, { saved = it }, {}) } }
        listOf("Dancing Script style", "Caveat style", "Sacramento style").forEach { compose.onNodeWithContentDescription(it).assertExists() }
        compose.onNodeWithText("Save").performClick(); assertNull(saved) // disabled without a name
        compose.onNodeWithContentDescription("Name to sign with").performTextInput("Alice Example")
        compose.onNodeWithContentDescription("Caveat style").performClick()
        compose.onNodeWithText("Save").performClick()
        assertNotNull(saved)
    }

    @Test fun saveOptionsDefaultToOffAndOnlyShowRelevantChoices() {
        var got: FillSaveOptions? = null
        compose.setContent { ForgeTheme { SaveOptionsContent(showForm = true, showSignatures = false, copyOnlyReason = null, onSave = { got = it }, onCancel = {}) } }
        compose.onNodeWithText("Flatten form fields").assertIsDisplayed()
        compose.onNodeWithText("Flatten signatures into page").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        assertEquals(FillSaveOptions(false, false), got)
        compose.onNodeWithText("Flatten form fields").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(FillSaveOptions(true, false), got)
    }

    @Test fun saveOptionsExplainACopyOnlySave() {
        compose.setContent { ForgeTheme { SaveOptionsContent(showForm = true, showSignatures = true, copyOnlyReason = "The author doesn't allow filling in forms.", onSave = {}, onCancel = {}) } }
        compose.onNodeWithText("doesn't allow filling in forms", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save a copy").assertIsDisplayed()
        compose.onNodeWithText("Flatten signatures into page").assertIsDisplayed()
    }
}
