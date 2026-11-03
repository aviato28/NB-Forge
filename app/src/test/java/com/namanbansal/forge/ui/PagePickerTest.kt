package com.namanbansal.forge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.pdf.PageSelectionSync
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.PagePicker
import com.namanbansal.forge.ui.toolkit.pickerColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider
import java.io.File

/** The shared PagePicker: tap, drag-select, range field in two-way sync, All / None / Invert, long-press peek. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PagePickerTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun thumbs(pages: Int) = PdfThumbnails(PdfFixtures.withText(File(tmp.root, "p$pages.pdf"), pages, uniqueHeights = false))

    private var selection = emptySet<Int>()

    private fun show(pages: Int, withText: Boolean = true) {
        val t = thumbs(pages)
        compose.setContent {
            ForgeTheme {
                var sel by remember { mutableStateOf(emptySet<Int>()) }
                var text by remember { mutableStateOf("") }
                selection = sel
                PagePicker(
                    pages, t, sel, { sel = it; text = PageSelectionSync.toText(it) }, Modifier,
                    rangeText = if (withText) text else null,
                    onRangeTextChange = if (withText) ({ v -> text = v; sel = PageSelectionSync.fromText(v, pages, sel).selection }) else null,
                )
            }
        }
    }

    private fun isSelected(page: Int) = compose.onNodeWithContentDescription("Page $page").fetchSemanticsNode().config
        .let { c -> if (SemanticsProperties.StateDescription in c) c[SemanticsProperties.StateDescription] else null } == "Selected"

    @Test fun tapTogglesAPageAndUpdatesTheCount() {
        show(12)
        compose.onNodeWithText("0 of 12 selected").assertExists()
        compose.onNodeWithContentDescription("Page 2").performClick()
        compose.onNodeWithText("1 of 12 selected").assertExists()
        assertTrue(isSelected(2))
        compose.onNodeWithContentDescription("Page 2").performClick()
        compose.onNodeWithText("0 of 12 selected").assertExists()
    }

    @Test fun tappingRewritesTheRangeText() {
        show(12)
        listOf(1, 2, 3, 5).forEach { compose.onNodeWithContentDescription("Page $it").performClick() }
        compose.onNodeWithText("1-3, 5").assertExists()
    }

    @Test fun typingARangeSelectsThoseThumbnails() {
        show(12)
        compose.onNode(hasSetTextAction()).performTextInput("2-4, 7")
        compose.waitForIdle()
        compose.onNodeWithText("4 of 12 selected").assertExists()
        assertEquals(setOf(1, 2, 3, 6), selection)
        assertTrue(isSelected(3)); assertTrue(!isSelected(5))
    }

    @Test fun invalidTextShowsAnInlineErrorAndLeavesTheGridAlone() {
        show(12)
        compose.onNode(hasSetTextAction()).performTextInput("1-3")
        compose.onNode(hasSetTextAction()).performTextReplacement("1-3, 99")
        compose.waitForIdle()
        compose.onNodeWithText("Page 99 doesn't exist. This PDF has 12 pages.").assertExists()
        compose.onNodeWithText("3 of 12 selected").assertExists()
        assertEquals(setOf(0, 1, 2), selection)
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("0 of 12 selected").assertExists()
    }

    @Test fun allNoneAndInvert() {
        show(12)
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("12 of 12 selected").assertExists()
        compose.onNodeWithText("Invert").performClick()
        compose.onNodeWithText("0 of 12 selected").assertExists()
        compose.onNodeWithContentDescription("Page 1").performClick()
        compose.onNodeWithText("Invert").performClick()
        compose.onNodeWithText("11 of 12 selected").assertExists()
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("0 of 12 selected").assertExists()
    }

    @Test fun draggingAcrossThumbnailsSelectsARun() {
        show(12, withText = false)
        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            swipe(center, Offset(center.x + width * 2.2f, center.y), durationMillis = 300)
        }
        compose.waitForIdle()
        assertEquals(setOf(0, 1, 2), selection)
        // dragging back over selected pages switches them off again
        compose.onNodeWithContentDescription("Page 3").performTouchInput {
            swipe(center, Offset(center.x - width * 1.2f, center.y), durationMillis = 300)
        }
        compose.waitForIdle()
        assertEquals(setOf(0), selection)
    }

    @Test fun longPressOpensThePeekAndCloseDismissesIt() {
        show(12)
        compose.onNodeWithContentDescription("Page 3").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close preview").assertExists()
        assertEquals(emptySet<Int>(), selection) // a long press peeks, it does not select
        compose.onNodeWithContentDescription("Close preview").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Close preview").assertCountEquals(0)
    }

    @Test fun fiveHundredPagesStayLazy() {
        show(500)
        compose.onNodeWithText("0 of 500 selected").assertExists()
        val composed = compose.onAllNodesWithContentDescription("Page", substring = true)
        assertTrue("only visible cells are composed", composed.fetchSemanticsNodes().size < 60)
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("500 of 500 selected").assertExists()
    }

    @Test fun columnsGrowOnWideScreens() {
        assertEquals(3, pickerColumns(411f))
        assertEquals(3, pickerColumns(599f))
        assertEquals(5, pickerColumns(700f))
        assertEquals(7, pickerColumns(1000f))
    }
}

