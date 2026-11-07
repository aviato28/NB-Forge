package com.namanbansal.forge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.ExistingKind
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.line
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.paragraph
import com.namanbansal.forge.core.pdf.edit.content.ExistingIndex
import com.namanbansal.forge.core.pdf.edit.content.FontOpener
import com.namanbansal.forge.tools.pdf.EditController
import com.namanbansal.forge.tools.pdf.EditMode
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.EditToolbar
import com.namanbansal.forge.tools.pdf.ExBlockRef
import com.namanbansal.forge.tools.pdf.ExImageRef
import com.namanbansal.forge.tools.pdf.PageHit
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ExistingUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val fonts = FontOpener { File("src/main/assets/$it").inputStream() }
    private var index: ExistingIndex? = null
    private val messages = ArrayList<String>()
    private var n = 0
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }
    @After fun done() { index?.close() }

    private fun controller(f: File): EditController {
        val ix = ExistingIndex(f, fonts).also { index = it }
        return EditController({ 0.77f }, { 0 }, { 1 }, CoroutineScope(Dispatchers.Unconfined), { null }, { _, _, _ -> null }, { messages += it },
            loadObjects = { p -> ix.page(p) },
            previewOf = { p, b, e -> ix.preview(p, b, e.text, e.sizePt, e.align, e.bold.takeIf { it >= 0 }?.let { it == 1 }, e.italic.takeIf { it >= 0 }?.let { it == 1 }) })
    }

    private val para = "The committee approved the budget after a long discussion about costs and about the plan for the coming year in the north office"

    private fun page(): File = ExistingFixtures.doc(tmp.newFile("p${n++}.pdf")) { d, _, cs ->
        cs.line(ExistingFixtures.HELV_B, 22f, 72f, 730f, "Budget Review")
        cs.paragraph(ExistingFixtures.TIMES, 12f, 72f, 690f, 420f, para)
        cs.paragraph(ExistingFixtures.TIMES, 12f, 72f, 560f, 420f, "Second paragraph that is clearly separate from the first one and wraps over a couple of lines as well")
    }

    private fun withPicture(): File {
        val f = tmp.newFile("pic.pdf")
        PDDocument().use { d ->
            val p = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER); d.addPage(p)
            val bmp = android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawColor(android.graphics.Color.MAGENTA) }
            PDPageContentStream(d, p).use { cs ->
                cs.drawImage(LosslessFactory.createFromImage(d, bmp), 100f, 400f, 240f, 160f)
                cs.setNonStrokingColor(0.8f, 0f, 0f); cs.addRect(380f, 420f, 100f, 80f); cs.fill()
                cs.setNonStrokingColor(0f, 0f, 0.8f); cs.addRect(380f, 300f, 100f, 60f); cs.fill()
            }
            d.save(f)
        }
        return f
    }

    private fun show(c: EditController) = compose.setContent { ForgeTheme { EditToolbar(c, false, {}, {}, {}) } }
    private fun settle(c: EditController, p: Int = 0) { c.existing.ensure(p); compose.waitForIdle() }

    @Test fun existingIsTheDefaultToolAndOffersSelectArea() {
        val c = controller(page()); show(c)
        assertEquals(EditTool.EXISTING, c.tool.value)
        compose.onNodeWithContentDescription("Existing").assertIsSelected()
        compose.onNodeWithContentDescription("Select several objects by dragging a box").assertIsDisplayed()
        compose.onNodeWithText("Tap a paragraph, picture or drawing in the PDF to select it. Tap a selected paragraph to edit its text.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Text").performClick()
        compose.onNodeWithContentDescription("Text").performClick()   // leaving a tool returns to Edit existing
        assertEquals(EditTool.EXISTING, c.tool.value)
    }

    @Test fun blocksAreOutlinedAndTappingSelectsThenOpensTheInlineEditor() {
        val c = controller(page())
        compose.setContent { ForgeTheme { androidx.compose.foundation.layout.Column {
            Box(Modifier.size(411.dp, 530.dp)) { EditMode(c, interactive = true, pageWidthPt = { 612f }).PageOverlay(0, 1233, 1596, false) }
            EditToolbar(c, false, {}, {}, {})
        } } }
        settle(c)
        compose.onNodeWithContentDescription("3 text blocks, 0 pictures, 0 drawings you can edit").assertExists()
        val o = c.existing.objects.value[0]!!
        assertEquals(3, o.blocks.size)
        val b = o.blocks.first { it.text.startsWith("The committee") }
        val hit = PageHit(0, (b.rect.left + b.rect.right) / 2, (b.rect.top + b.rect.bottom) / 2, 0.77f)
        assertTrue(c.onTap(hit)); assertEquals(listOf(ExBlockRef(0, b.key)), c.existing.selection.value)
        assertEquals(null, c.existing.editing.value)
        assertTrue(c.onTap(hit)); compose.waitForIdle()   // a second tap on the selected paragraph edits it
        assertEquals(ExBlockRef(0, b.key), c.existing.editing.value)
        compose.onNodeWithTag("existing-editor").assertIsDisplayed()
        compose.onNodeWithTag("existing-editor").performTextReplacement("The committee rejected the budget.")
        compose.onNodeWithContentDescription("Finish editing the text").performClick(); compose.waitForIdle()
        assertEquals(null, c.existing.editing.value)
        val e = c.session.current.existing.single()
        assertEquals("The committee rejected the budget.", e.text); assertEquals(para, e.origText)
        assertTrue(c.existing.previews.value.containsKey(e.id))   // the re-wrapped preview is ready
        compose.onNodeWithTag("existing-editor").assertDoesNotExist()
    }

    @Test fun cancellingTheEditorKeepsTheOriginal() {
        val c = controller(page()); settle(c)
        val b = c.existing.objects.value[0]!!.blocks.first { it.text.startsWith("The committee") }
        c.existing.openEditor(ExBlockRef(0, b.key)); c.existing.draft = "something else"; c.existing.closeEditor(false)
        assertTrue(c.session.current.existing.isEmpty())
        c.existing.openEditor(ExBlockRef(0, b.key)); c.existing.closeEditor(true) // unchanged text is not a change
        assertTrue(c.session.current.existing.isEmpty())
    }

    @Test fun toolbarChangesBoldSizeAlignAndColourOfTheSelectedParagraph() {
        val c = controller(page()); settle(c); show(c)
        val b = c.existing.objects.value[0]!!.blocks.first { it.text.startsWith("The committee") }
        c.existing.select(ExBlockRef(0, b.key)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Bold").performClick()
        compose.onNodeWithContentDescription("Italic").performClick()
        compose.onNodeWithContentDescription("Bigger text").performClick()
        compose.onNodeWithContentDescription("Align Center").performClick()
        compose.onNodeWithContentDescription("Red text").performClick()
        val e = c.session.current.existing.single()
        assertEquals(1, e.bold); assertEquals(1, e.italic); assertEquals(1, e.align); assertTrue(e.hasColor)
        assertEquals(13.2f, e.sizePt, 0.1f)
        compose.onNodeWithContentDescription("Bold").assertIsSelected()
        compose.onNodeWithContentDescription("Undo my changes to this paragraph").performClick()
        assertTrue(c.session.current.existing.isEmpty())
    }

    private fun embeddedSimple(): File {
        val f = tmp.newFile("simple${n++}.pdf")
        PDDocument().use { d ->
            val p = PDPage(); d.addPage(p)
            val font = fonts.open("fonts/NotoSerif-Regular.ttf").use { com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont.load(d, it, com.tom_roush.pdfbox.pdmodel.font.encoding.WinAnsiEncoding.INSTANCE) }
            PDPageContentStream(d, p).use { cs -> cs.beginText(); cs.setFont(font, 14f); cs.newLineAtOffset(72f, 700f); cs.showText("Hello embedded font"); cs.endText() }
            d.save(f)
        }
        return f
    }

    @Test fun anEmbeddedSimpleFontIsReusedWhenItHasTheGlyphsAndReplacedWhenItDoesNot() {
        val c = controller(embeddedSimple()); settle(c)
        val b = c.existing.objects.value[0]!!.blocks.single(); val r = ExBlockRef(0, b.key)
        c.existing.openEditor(r); c.existing.draft = "Hello café, still the same font"; c.existing.closeEditor(true)
        assertTrue(messages.toString(), messages.none { it.startsWith("Used") })
        c.existing.openEditor(r); c.existing.draft = "Hello \u015D\u0153"; c.existing.closeEditor(true) // not in WinAnsi / the encoding
        assertTrue(messages.toString(), messages.any { it.startsWith("Used Noto Serif") })
    }

    private fun subsetPage(): File {
        val f = tmp.newFile("subset.pdf")
        PDDocument().use { d ->
            val p = PDPage(); d.addPage(p)
            val font = fonts.open("fonts/NotoSerif-Regular.ttf").use { PDType0Font.load(d, it, true) }
            PDPageContentStream(d, p).use { cs -> cs.beginText(); cs.setFont(font, 14f); cs.newLineAtOffset(72f, 700f); cs.showText("Quick brown fox"); cs.endText() }
            d.save(f)
        }
        return f
    }

    @Test fun fontFallbackNoticeIsShownOncePerBlock() {
        val c = controller(subsetPage()); settle(c)
        val b = c.existing.objects.value[0]!!.blocks.single()
        val r = ExBlockRef(0, b.key)
        c.existing.openEditor(r); c.existing.draft = "Lazy zebra quiz"; c.existing.closeEditor(true)
        val notice = "Used Noto Serif — the original font doesn't include some of these letters."
        assertEquals(listOf(notice), messages.filter { it.startsWith("Used") })
        c.existing.openEditor(r); c.existing.draft = "Lazy zebra quiz again"; c.existing.closeEditor(true)
        assertEquals(1, messages.count { it.startsWith("Used") })   // not repeated for the same block
        c.existing.openEditor(r); c.existing.draft = "Quick fox"; c.existing.closeEditor(true)
        assertEquals(1, c.session.current.existing.size)
    }

    @Test fun growingIntoTheNextParagraphWarns() {
        val c = controller(page()); settle(c)
        val b = c.existing.objects.value[0]!!.blocks.first { it.text.startsWith("The committee") }
        c.existing.openEditor(ExBlockRef(0, b.key)); c.existing.draft = (1..8).joinToString(" ") { para }; c.existing.closeEditor(true)
        assertTrue(messages.toString(), messages.any { it.contains("runs into the next paragraph") })
    }

    @Test fun selectedPictureOffersMoveResizeRotateReplaceDelete() {
        val c = controller(withPicture()); settle(c); show(c)
        val img = c.existing.objects.value[0]!!.images.single()
        c.existing.select(ExImageRef(0, img.key)); compose.waitForIdle()
        listOf("Make picture smaller", "Make picture bigger", "Rotate a quarter turn", "Replace this picture", "Lock the aspect ratio", "Delete picture").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Rotate a quarter turn").performClick()
        assertEquals(1, c.session.current.existing.single().turns)
        // drag it to the right
        val hit = PageHit(0, (img.rect.left + img.rect.right) / 2, (img.rect.top + img.rect.bottom) / 2, 0.77f)
        assertTrue(c.claimsAt(hit)); c.onStart(hit); c.onMove(hit.x + 0.2f, hit.y); c.onEnd()
        val moved = c.session.current.existing.single().newRect!!
        assertTrue(moved.left > img.rect.left + 0.1f)
        compose.onNodeWithContentDescription("Delete picture").performClick()
        assertTrue(c.session.current.existing.single().delete)
        c.session.undo(); assertFalse(c.session.current.existing.single().delete)
    }

    @Test fun lassoSelectsSeveralObjectsAndDeletesThemInOneStep() {
        val c = controller(withPicture()); settle(c); show(c)
        compose.onNodeWithContentDescription("Select several objects by dragging a box").performClick()
        assertTrue(c.existing.lasso.value && c.claimsDrag)
        c.onStart(PageHit(0, 0.02f, 0.2f, 0.77f)); c.onMove(0.98f, 0.8f); c.onEnd(); compose.waitForIdle()
        assertEquals(3, c.existing.selection.value.size)   // the picture and the two rectangles
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete everything selected").performClick()
        assertEquals(3, c.session.current.existing.size); assertTrue(c.session.current.existing.all { it.delete })
        c.session.undo(); assertTrue(c.session.current.existing.isEmpty())   // one step
    }

    @Test fun pathAloneCanBeSelectedAndDeleted() {
        val c = controller(withPicture()); settle(c); show(c)
        val p = c.existing.objects.value[0]!!.paths.first()
        assertTrue(c.onTap(PageHit(0, (p.rect.left + p.rect.right) / 2, (p.rect.top + p.rect.bottom) / 2, 0.77f)))
        compose.waitForIdle(); compose.onNodeWithContentDescription("Delete drawing").performClick()
        assertEquals(ExistingKind.PATH, c.session.current.existing.single().kind)
    }

    @Test fun scannedPageShowsTheOcrHint() {
        val f = tmp.newFile("scan.pdf")
        PDDocument().use { d ->
            val p = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER); d.addPage(p)
            val bmp = android.graphics.Bitmap.createBitmap(30, 40, android.graphics.Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawColor(android.graphics.Color.GRAY) }
            PDPageContentStream(d, p).use { cs -> cs.drawImage(LosslessFactory.createFromImage(d, bmp), 0f, 0f, 612f, 792f) }
            d.save(f)
        }
        val c = controller(f); settle(c); show(c)
        compose.onNodeWithText("This page is a scanned image — text can't be edited yet (OCR is coming).").assertIsDisplayed()
    }

    @Test fun unsavedChangesPersistAsJsonAndComeBack() {
        val c = controller(page()); settle(c)
        val b = c.existing.objects.value[0]!!.blocks.first { it.text.startsWith("Budget") }
        c.existing.select(ExBlockRef(0, b.key)); c.existing.toggleBold()
        val json = com.namanbansal.forge.core.pdf.edit.EditJson.encode(c.session.current)
        val c2 = controller(page()); c2.reset(com.namanbansal.forge.core.pdf.edit.EditJson.decode(json))
        assertEquals(c.session.current, c2.session.current); assertTrue(c2.session.dirty.value)
        assertFalse(c2.session.canUndo.value) // history starts fresh after a restore
    }
}
