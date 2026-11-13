package com.namanbansal.forge.video

import com.namanbansal.forge.core.video.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/** An independent GIF reader (spec LZW decoder) so the encoder is checked by decoding its own output back. */
object GifDecode {
    class Frame(val palette: IntArray, val indices: ByteArray, val delayCs: Int)
    class Gif(val width: Int, val height: Int, val frames: List<Frame>, val loop: Int?)

    fun decode(b: ByteArray): Gif {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        check(String(b, 0, 6, Charsets.US_ASCII) == "GIF89a"); p = 6
        val w = u16(); val h = u16(); val flags = u8(); u8(); u8()
        var global: IntArray? = null
        if (flags and 0x80 != 0) global = IntArray(1 shl ((flags and 7) + 1)) { (u8() shl 16) or (u8() shl 8) or u8() }
        val frames = ArrayList<Frame>(); var loop: Int? = null; var delay = 0
        while (true) {
            when (val t = u8()) {
                0x3B -> return Gif(w, h, frames, loop)
                0x21 -> {
                    val label = u8()
                    val data = ArrayList<Byte>()
                    while (true) { val n = u8(); if (n == 0) break; repeat(n) { data += b[p++] } }
                    if (label == 0xF9) delay = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                    if (label == 0xFF && String(data.take(11).toByteArray()) == "NETSCAPE2.0") loop = (data[12].toInt() and 0xFF) or ((data[13].toInt() and 0xFF) shl 8)
                }
                0x2C -> {
                    u16(); u16(); val iw = u16(); val ih = u16(); val f = u8()
                    val table = if (f and 0x80 != 0) IntArray(1 shl ((f and 7) + 1)) { (u8() shl 16) or (u8() shl 8) or u8() } else global!!
                    val min = u8()
                    val buf = ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; buf.write(b, p, n); p += n }
                    frames += Frame(table, lzwDecode(buf.toByteArray(), min, iw * ih), delay)
                    check(iw == w && ih == h)
                }
                else -> error("bad block $t")
            }
        }
    }

    fun lzwDecode(data: ByteArray, minCode: Int, expected: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096)
        val out = ByteArrayOutputStream(expected)
        var codeSize = minCode + 1; var next = eoi + 1; var prev = -1
        var bitPos = 0
        fun read(): Int { var v = 0; for (i in 0 until codeSize) { val byte = data[bitPos shr 3].toInt() and 0xFF; v = v or (((byte shr (bitPos and 7)) and 1) shl i); bitPos++ }; return v }
        fun expand(code: Int): ByteArray { val tmp = ArrayList<Byte>(); var c = code; while (c >= clear + 2) { tmp += suffix[c].toByte(); c = prefix[c] }; tmp += c.toByte(); return tmp.reversed().toByteArray() }
        while (bitPos + codeSize <= data.size * 8) {
            val code = read()
            if (code == clear) { codeSize = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) break
            if (prev == -1) { out.write(code); prev = code; continue }
            val entry = if (code < next) expand(code) else expand(prev) + expand(prev)[0]
            out.write(entry)
            if (next < 4096) {
                prefix[next] = prev; suffix[next] = entry[0].toInt() and 0xFF; next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = code
        }
        return out.toByteArray()
    }
}

class GifEncoderTest {
    private fun scene(w: Int, h: Int, shift: Int = 0) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val r = ((x + shift) * 255 / w).coerceIn(0, 255); val g = (y * 255 / h); val b = (((x + y) * 3) and 0xFF)
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    @Test fun lzwRoundTripsEveryCodeSizeAndTableReset() {
        val rnd = Random(7)
        for (bits in 2..8) {
            val n = 1 shl bits
            // Random (forces table fill, clear codes and 12-bit codes) and runs (long matches).
            val random = ByteArray(40_000) { rnd.nextInt(n).toByte() }
            val runs = ByteArray(40_000) { ((it / 37) % n).toByte() }
            for (data in listOf(random, runs, ByteArray(0).let { byteArrayOf(1) }, ByteArray(500) { 0 })) {
                val enc = GifLzw.encode(data, maxOf(2, bits))
                assertArrayEquals("bits=$bits", data, GifDecode.lzwDecode(enc, maxOf(2, bits), data.size))
            }
        }
    }

    @Test fun animatedGifDecodesBackWithRightFrameCountSizeAndPalette() {
        val w = 64; val h = 48
        val out = ByteArrayOutputStream()
        val gw = GifWriter(out, w, h)
        val frames = (0 until 5).map { Quantizer.quantize(scene(w, h, it * 6), w, h) }
        frames.forEach { gw.addFrame(it, 8) }
        gw.finish()
        assertEquals(out.size().toLong(), gw.bytesWritten); assertEquals(5, gw.frames)
        val gif = GifDecode.decode(out.toByteArray())
        assertEquals(w, gif.width); assertEquals(h, gif.height); assertEquals(5, gif.frames.size); assertEquals(0, gif.loop)
        gif.frames.forEachIndexed { i, f ->
            assertTrue(f.palette.size <= 256); assertEquals(8, f.delayCs)
            assertArrayEquals(frames[i].indices, f.indices) // LZW payload survives untouched
            // And the decoded colours equal what the quantiser promised.
            for (px in listOf(0, 100, w * h - 1)) assertEquals(frames[i].palette[frames[i].indices[px].toInt() and 0xFF], f.palette[f.indices[px].toInt() and 0xFF])
        }
    }

    @Test fun twoColourFrameUsesTinyPalette() {
        val w = 10; val h = 10
        val px = IntArray(w * h) { if (it % 2 == 0) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val f = Quantizer.quantize(px, w, h, dither = false)
        assertEquals(2, f.palette.size)
        val out = ByteArrayOutputStream(); GifWriter(out, w, h).also { it.addFrame(f, 5); it.finish() }
        val g = GifDecode.decode(out.toByteArray())
        assertEquals(2, g.frames[0].palette.size)
        assertArrayEquals(f.indices, g.frames[0].indices)
    }

    @Test fun quantiserIsDeterministicAndLimitedTo256Colours() {
        val px = scene(80, 60)
        val a = Quantizer.quantize(px, 80, 60); val b = Quantizer.quantize(px.clone(), 80, 60)
        assertArrayEquals(a.palette, b.palette); assertArrayEquals(a.indices, b.indices)
        assertTrue(a.palette.size in 2..256)
        val limited = Quantizer.quantize(px, 80, 60, maxColors = 16)
        assertTrue(limited.palette.size <= 16)
        assertTrue(limited.indices.all { (it.toInt() and 0xFF) < limited.palette.size })
    }

    @Test fun ditheringKeepsAverageColourCloserThanFlatMapping() {
        // A smooth gradient squeezed into 8 colours: dithering must preserve the mean colour better than nearest-colour mapping.
        val w = 128; val h = 16
        val px = IntArray(w * h) { val v = (it % w) * 255 / (w - 1); 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v }
        fun meanError(f: IndexedFrame): Double {
            val argb = f.toArgb(); var e = 0.0
            for (x in 0 until w) { // average each column vs the source (error diffusion works over neighbours)
                e += kotlin.math.abs(((argb[x] shr 8) and 0xFF) - ((px[x] shr 8) and 0xFF)).toDouble()
            }
            return e / w
        }
        val dith = Quantizer.quantize(px, w, h, 8, dither = true)
        val flat = Quantizer.quantize(px, w, h, 8, dither = false)
        val meanSrc = px.map { (it shr 8) and 0xFF }.average()
        assertTrue(kotlin.math.abs(dith.toArgb().map { (it shr 8) and 0xFF }.average() - meanSrc) <= kotlin.math.abs(flat.toArgb().map { (it shr 8) and 0xFF }.average() - meanSrc) + 1.0)
        assertTrue(dith.palette.size <= 8)
    }

    @Test fun builderWritesFramesFromASourceAndEstimatesSize() {
        val src = object : FrameSource { override fun frame(timeUs: Long, w: Int, h: Int) = scene(w, h, (timeUs / 100_000).toInt()) }
        val out = ByteArrayOutputStream()
        val res = kotlinx.coroutines.runBlocking { GifBuilder.build(src, 1920, 1080, 0, 2_000, 10_000, 10, 240, out) }
        assertEquals(20, res.frames)
        val gif = GifDecode.decode(out.toByteArray())
        assertEquals(240, gif.width); assertEquals(135, gif.height); assertEquals(20, gif.frames.size); assertEquals(10, gif.frames[0].delayCs)
        val prev = kotlinx.coroutines.runBlocking { GifBuilder.preview(src, 1920, 1080, 0, 2_000, 10_000, 10, 240) }
        assertEquals(10, prev.frames.size)
        val est = GifMath.extrapolate(prev.bytes, prev.frames.size, 20)
        assertEquals(out.size().toDouble(), est.toDouble(), out.size() * 0.25) // measured estimate within 25 % of the real file
    }

    @Test fun builderFailsClearlyWhenNoFrameCanBeRead() {
        val src = object : FrameSource { override fun frame(timeUs: Long, w: Int, h: Int): IntArray? = null }
        try { kotlinx.coroutines.runBlocking { GifBuilder.build(src, 640, 360, 0, 1_000, 5_000, 10, 240, ByteArrayOutputStream()) }; fail() } catch (e: com.namanbansal.forge.core.ToolException) { assertTrue(e.message!!.contains("read frames")) }
    }
}
