package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfInsertTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun h(i: Int) = 300 + i

    @Test fun insertIndexForEveryPosition() {
        assertEquals(0, PdfInsert.insertIndex(InsertPosition.Start, 3, 10))
        assertEquals(10, PdfInsert.insertIndex(InsertPosition.End, 3, 10))
        assertEquals(2, PdfInsert.insertIndex(InsertPosition.BeforePage, 3, 10))
        assertEquals(3, PdfInsert.insertIndex(InsertPosition.AfterPage, 3, 10))
        assertEquals(0, PdfInsert.insertIndex(InsertPosition.BeforePage, 1, 10))
        assertEquals(10, PdfInsert.insertIndex(InsertPosition.AfterPage, 10, 10))
        assertEquals(10, PdfInsert.insertIndex(InsertPosition.AfterPage, 99, 10)) // out of range is clamped, never crashes
        assertEquals(0, PdfInsert.insertIndex(InsertPosition.BeforePage, -4, 10))
    }

    @Test fun blankSizes() {
        assertEquals(595f to 842f, PdfInsert.blankSize(BlankSize.A4, 100f to 100f))
        assertEquals(612f to 792f, PdfInsert.blankSize(BlankSize.Letter, null))
        assertEquals(300f to 400f, PdfInsert.blankSize(BlankSize.Neighbour, 300f to 400f))
        assertEquals(595f to 842f, PdfInsert.blankSize(BlankSize.Neighbour, null))
        assertEquals(0, PdfInsert.neighbourIndex(0, 5))
        assertEquals(2, PdfInsert.neighbourIndex(3, 5))
        assertEquals(4, PdfInsert.neighbourIndex(5, 5))
    }

    private fun run(base: File, other: File?, index: Int, inserted: (PdfAssembler) -> List<PageSpec>): File {
        val out = tmp.newFile("o${System.nanoTime()}.pdf")
        PdfAssembler(listOfNotNull(base, other)).use { asm ->
            out.outputStream().use { asm.write(PdfInsert.specs(asm.pageCount(0), index, inserted(asm)), it) }
        }
        return out
    }

    @Test fun insertsBlankPagesAtAllPositionsMatchingTheNeighbour() {
        val base = PdfFixtures.withText(tmp.newFile("b.pdf"), 4)
        for ((pos, page, expected) in listOf(
            Triple(InsertPosition.Start, 1, listOf(h(1), h(1), h(2), h(3), h(4))),
            Triple(InsertPosition.End, 1, listOf(h(1), h(2), h(3), h(4), h(4))),
            Triple(InsertPosition.BeforePage, 3, listOf(h(1), h(2), h(2), h(3), h(4))),
            Triple(InsertPosition.AfterPage, 3, listOf(h(1), h(2), h(3), h(3), h(4))),
        )) {
            val index = PdfInsert.insertIndex(pos, page, 4)
            val out = run(base, null, index) { asm ->
                val (w, hh) = PdfInsert.blankSize(BlankSize.Neighbour, asm.pageSize(0, PdfInsert.neighbourIndex(index, 4)))
                listOf(PageSpec.Blank(w, hh))
            }
            assertEquals("$pos", expected, PdfFixtures.pageHeights(out))
        }
    }

    @Test fun insertsPagesFromAnotherPdfAndSeveralBlanksInA4() {
        val base = PdfFixtures.withText(tmp.newFile("b2.pdf"), 3)
        val other = PdfFixtures.withText(tmp.newFile("o2.pdf"), 5)
        val out = run(base, other, 1) { listOf(4, 0).map { PageSpec.FromSource(1, it) } }
        assertEquals(listOf(h(1), h(5), h(1), h(2), h(3)), PdfFixtures.pageHeights(out))
        val blanks = run(base, null, 3) { List(2) { PageSpec.Blank(595f, 842f) } }
        assertEquals(listOf(h(1), h(2), h(3), 842, 842), PdfFixtures.pageHeights(blanks))
    }
}
