package com.namanbansal.forge.core.pdf.sign

import android.graphics.Bitmap
import android.graphics.Color
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.NormPt
import com.namanbansal.forge.core.pdf.fill.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignatureCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun png(w: Int = 60, h: Int = 30): ByteArray = SignatureImage.toPng(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) })

    // ---- library ----
    @Test fun libraryCrudAndPersistence() {
        val dir = tmp.newFolder("sigs")
        val lib = SignatureLibrary(dir)
        assertTrue(lib.list().isEmpty())
        val a = lib.add(SigKind.SIGNATURE, png(60, 30), "Work")
        assertEquals(2f, a.aspect, 0.01f)
        val i = lib.add(SigKind.INITIALS, png(20, 20))
        assertEquals("Initials", i.label)
        // a new library object on the same folder sees everything (persistence), PNGs live in the private folder
        val again = SignatureLibrary(dir)
        assertEquals(listOf("Work", "Initials"), again.list().map { it.label })
        assertNotNull(again.png(a.id)); assertTrue(java.io.File(dir, "${a.id}.png").exists())
        again.rename(a.id, "Contracts"); assertEquals("Contracts", SignatureLibrary(dir).signatures.single().label)
        // initials are replaced, not duplicated
        val i2 = again.add(SigKind.INITIALS, png(20, 20)); assertEquals(i2.id, again.initials!!.id); assertEquals(2, again.list().size)
        again.delete(a.id)
        assertNull(again.png(a.id)); assertEquals(listOf(SigKind.INITIALS), again.list().map { it.kind })
    }

    @Test fun libraryKeepsAtMostFiveSignaturesAndRejectsBrokenPictures() {
        val lib = SignatureLibrary(tmp.newFolder("sigs"))
        repeat(5) { lib.add(SigKind.SIGNATURE, png()) }
        assertFalse(lib.canAdd(SigKind.SIGNATURE))
        try { lib.add(SigKind.SIGNATURE, png()); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("5")) }
        lib.add(SigKind.INITIALS, png()) // initials are separate from the five
        try { lib.add(SigKind.INITIALS, byteArrayOf(1, 2, 3)); fail() } catch (_: ToolException) { }
    }

    // ---- image processing ----
    private fun scene(): IntArray { // 20x10 paper (light grey 235) with a dark 4x2 ink block at x 6..9, y 3..4
        val px = IntArray(200) { Color.rgb(235, 235, 235) }
        for (y in 3..4) for (x in 6..9) px[y * 20 + x] = Color.rgb(20, 20, 40)
        return px
    }

    @Test fun cropBoundsFollowTheInk() {
        val b = SignatureImage.inkBounds(scene(), 20, 10, 150)!!
        assertEquals(PixelBox(6, 3, 10, 5), b)
        assertNull(SignatureImage.inkBounds(IntArray(200) { Color.WHITE }, 20, 10, 150))
    }

    @Test fun thresholdDecidesWhatBecomesTransparent() {
        val px = scene()
        val cut = SignatureImage.makeTransparent(px, 150)
        assertEquals(0, cut[0] ushr 24)                       // paper: fully transparent
        assertEquals(255, cut[3 * 20 + 6] ushr 24)            // ink: opaque
        assertEquals(Color.rgb(20, 20, 40), cut[3 * 20 + 6] and 0xFFFFFF or Color.BLACK) // ink keeps its colour
        // a very low threshold removes the ink too, a high one keeps the grey paper
        assertEquals(0, SignatureImage.makeTransparent(px, 10)[3 * 20 + 6] ushr 24)
        assertTrue(SignatureImage.makeTransparent(px, 250)[0] ushr 24 > 0)
        // anti-aliased edge: mid grey is only partly opaque
        val soft = SignatureImage.makeTransparent(intArrayOf(Color.rgb(110, 110, 110)), 150)[0] ushr 24
        assertTrue(soft in 1..254)
        val auto = SignatureImage.autoThreshold(px)
        assertTrue(auto in 21..234)
    }

    @Test fun processCropsAndClearsThePaper() {
        val bmp = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        bmp.setPixels(scene(), 0, 20, 0, 0, 20, 10)
        val out = SignatureImage.process(bmp, 150)!!
        assertTrue(out.width < 20 && out.height <= 10)
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        var opaque = 0
        for (y in 0 until out.height) for (x in 0 until out.width) if (Color.alpha(out.getPixel(x, y)) == 255) opaque++
        assertEquals(8, opaque)
        assertNull(SignatureImage.process(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }, 150))
    }

    @Test fun aPhoneLikePhotoWithAGreyPaperAndThinInkGetsAUsableAutoThreshold() {
        val w = 300; val h = 120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.rgb(226, 224, 218))
        val c = android.graphics.Canvas(bmp)
        c.drawLine(40f, 80f, 260f, 40f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 32, 90); strokeWidth = 3f })
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val t = SignatureImage.autoThreshold(px)
        assertTrue("threshold $t keeps the ink and drops the paper", t > 100 && t < 224)
        val out = SignatureImage.process(bmp, t)
        assertNotNull(out)
        assertTrue(out!!.width in 200..260)
    }

    @Test fun drawnStrokesAreRenderedTightlyOnTransparency() {
        val s = listOf(listOf(NormPt(10f, 10f), NormPt(60f, 40f), NormPt(110f, 12f)), listOf(NormPt(30f, 60f)))
        val b = SignatureImage.renderStrokes(s, 6f, Color.BLACK)!!
        assertTrue(b.width in 100..130 && b.height in 60..80)
        assertEquals(0, Color.alpha(b.getPixel(0, 0)))
        assertNull(SignatureImage.renderStrokes(emptyList(), 6f, Color.BLACK))
        assertNotNull(SignatureImage.renderTyped("Alice", null, Color.BLUE))
        assertNull(SignatureImage.renderTyped("  ", null, Color.BLUE))
    }

    // ---- placement math ----
    private val a4 = 595.28f / 841.89f

    @Test fun newSignatureIsCenteredAndKeepsItsShape() {
        val r = Placement.centered(3f, a4)
        assertEquals(0.5f, (r.left + r.right) / 2, 1e-4f); assertEquals(0.5f, (r.top + r.bottom) / 2, 1e-4f)
        val realAspect = ((r.right - r.left) * a4) / (r.bottom - r.top)
        assertEquals(3f, realAspect, 0.01f)
        val tall = Placement.centered(0.2f, a4) // a very tall picture is shrunk to fit the page
        assertTrue(tall.bottom - tall.top <= 0.5f + 1e-4f)
    }

    @Test fun signatureFitsInsideAFieldWithoutLeavingIt() {
        val field = NormRect(0.1f, 0.6f, 0.45f, 0.66f)
        val r = Placement.fitInto(field, 3f, a4)
        assertTrue(r.left >= field.left && r.right <= field.right && r.top >= field.top && r.bottom <= field.bottom)
        assertEquals(3f, ((r.right - r.left) * a4) / (r.bottom - r.top), 0.01f)
        assertEquals((field.left + field.right) / 2, (r.left + r.right) / 2, 1e-4f)
        // a field much taller than wide limits the width instead
        val narrow = Placement.fitInto(NormRect(0.1f, 0.1f, 0.15f, 0.4f), 3f, a4)
        assertTrue(narrow.right <= 0.15f && narrow.left >= 0.1f)
    }

    @Test fun fitIntoWorksOnTurnedPages() {
        // on a landscape display (page rotated 90/270) the page aspect is > 1
        val land = 841.89f / 595.28f
        val field = NormRect(0.2f, 0.3f, 0.5f, 0.4f)
        val r = Placement.fitInto(field, 2.5f, land)
        assertTrue(r.left >= field.left - 1e-5f && r.right <= field.right + 1e-5f && r.top >= field.top - 1e-5f && r.bottom <= field.bottom + 1e-5f)
        assertEquals(2.5f, ((r.right - r.left) * land) / (r.bottom - r.top), 0.01f)
    }

    @Test fun rotateResizeAndMoveStayOnThePage() {
        val r = Placement.centered(3f, a4)
        val t = Placement.rotated(r, a4)
        // a quarter turn swaps real width and height
        assertEquals((r.right - r.left) * a4, t.bottom - t.top, 1e-3f)
        assertEquals((r.bottom - r.top), (t.right - t.left) * a4, 1e-3f)
        var x = r; repeat(4) { x = Placement.rotated(x, a4) }
        assertEquals(r.left, x.left, 1e-3f); assertEquals(r.top, x.top, 1e-3f)
        val big = Placement.scaled(r, 10f); assertTrue(big.right <= 1f + 1e-5f && big.bottom <= 1f + 1e-5f)
        assertEquals((r.bottom - r.top) / (r.right - r.left), (big.bottom - big.top) / (big.right - big.left), 1e-3f)
        val small = Placement.scaled(r, 0.001f); assertTrue(small.right - small.left >= Placement.MIN_WIDTH - 1e-5f)
        val m = Placement.moved(r, 5f, -5f); assertTrue(m.right <= 1f + 1e-5f && m.top >= 0f)
        val handle = Placement.resizeTo(r, r.right + 0.1f, r.bottom + 0.01f, a4)
        assertTrue(handle.right - handle.left > r.right - r.left)
    }
}
