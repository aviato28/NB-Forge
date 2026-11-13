package com.namanbansal.forge.core.video

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** A quantised frame: up to 256 palette colours (0xRRGGBB) and one palette index per pixel. */
class IndexedFrame(val width: Int, val height: Int, val palette: IntArray, val indices: ByteArray) {
    /** Back to opaque ARGB (for the live preview and for tests). */
    fun toArgb(): IntArray = IntArray(indices.size) { 0xFF000000.toInt() or palette[indices[it].toInt() and 0xFF] }
}

/**
 * Median-cut colour quantiser with optional Floyd-Steinberg dithering. Fully deterministic: the same pixels always give the same
 * palette and indices (no randomness, stable tie-breaks), which the tests rely on.
 *
 * Colours are first reduced to 5 bits per channel and counted in a 32768-bin histogram, so cost is independent of picture size.
 */
object Quantizer {
    fun quantize(argb: IntArray, width: Int, height: Int, maxColors: Int = 256, dither: Boolean = true): IndexedFrame {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad frame" }
        val n = width * height
        val counts = IntArray(32768)
        for (i in 0 until n) counts[key(argb[i])]++
        val palette = medianCut(counts, maxColors.coerceIn(2, 256))
        val lookup = IntArray(32768) { -1 }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val k = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val cached = lookup[k]
            if (cached >= 0) return cached
            // Match against the centre of the histogram cell so the cache is exact for every colour that shares it.
            val cr = (r and 0xF8) or 4; val cg = (g and 0xF8) or 4; val cb = (b and 0xF8) or 4
            var best = 0; var bestD = Int.MAX_VALUE
            for (p in palette.indices) {
                val c = palette[p]
                val dr = ((c shr 16) and 0xFF) - cr; val dg = ((c shr 8) and 0xFF) - cg; val db = (c and 0xFF) - cb
                // Perceptual-ish weights.
                val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
                if (d < bestD) { bestD = d; best = p }
            }
            lookup[k] = best
            return best
        }
        val idx = ByteArray(n)
        if (!dither) {
            for (i in 0 until n) {
                val c = argb[i]
                idx[i] = nearest((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).toByte()
            }
        } else {
            // Two rows of error, serpentine scan.
            var cur = IntArray((width + 2) * 3)
            var nxt = IntArray((width + 2) * 3)
            for (y in 0 until height) {
                val ltr = y % 2 == 0
                var x = if (ltr) 0 else width - 1
                val step = if (ltr) 1 else -1
                java.util.Arrays.fill(nxt, 0)
                while (x in 0 until width) {
                    val c = argb[y * width + x]
                    val e = (x + 1) * 3
                    val r = (((c shr 16) and 0xFF) + cur[e] / 16).coerceIn(0, 255)
                    val g = (((c shr 8) and 0xFF) + cur[e + 1] / 16).coerceIn(0, 255)
                    val b = ((c and 0xFF) + cur[e + 2] / 16).coerceIn(0, 255)
                    val p = nearest(r, g, b)
                    idx[y * width + x] = p.toByte()
                    val pc = palette[p]
                    val er = r - ((pc shr 16) and 0xFF); val eg = g - ((pc shr 8) and 0xFF); val eb = b - (pc and 0xFF)
                    // Floyd-Steinberg weights 7/16 (ahead), 3/16 (behind below), 5/16 (below), 1/16 (ahead below); errors kept x16.
                    val ahead = (x + step + 1) * 3
                    val behind = (x - step + 1) * 3
                    val here = e
                    cur[ahead] += er * 7; cur[ahead + 1] += eg * 7; cur[ahead + 2] += eb * 7
                    nxt[behind] += er * 3; nxt[behind + 1] += eg * 3; nxt[behind + 2] += eb * 3
                    nxt[here] += er * 5; nxt[here + 1] += eg * 5; nxt[here + 2] += eb * 5
                    nxt[ahead] += er; nxt[ahead + 1] += eg; nxt[ahead + 2] += eb
                    x += step
                }
                val t = cur; cur = nxt; nxt = t
            }
        }
        return IndexedFrame(width, height, palette, idx)
    }

    private fun key(c: Int): Int = (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)

    private class Box(val colors: IntArray /* histogram keys */, var from: Int, var to: Int) {
        var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0
        var population = 0L
        fun measure(counts: IntArray) {
            rMin = 31; rMax = 0; gMin = 31; gMax = 0; bMin = 31; bMax = 0; population = 0
            for (i in from until to) {
                val k = colors[i]
                val r = k shr 10; val g = (k shr 5) and 31; val b = k and 31
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
                population += counts[k]
            }
        }
        val splittable: Boolean get() = to - from > 1
        /** Scaled so green (most visible) counts a little more. */
        val score: Long get() = maxOf((rMax - rMin) * 3L, (gMax - gMin) * 4L, (bMax - bMin) * 2L) * 1_000_000L + minOf(population, 999_999L)
    }

    private fun medianCut(counts: IntArray, maxColors: Int): IntArray {
        var distinct = 0
        for (c in counts) if (c > 0) distinct++
        val keys = IntArray(distinct)
        var j = 0
        for (k in counts.indices) if (counts[k] > 0) keys[j++] = k
        if (distinct <= maxColors) {
            // Few enough colours: use them exactly (centre of each 5-bit cell).
            return IntArray(maxOf(distinct, 1)) { i -> if (distinct == 0) 0 else cellColor(keys[i]) }
        }
        val boxes = ArrayList<Box>()
        boxes += Box(keys, 0, distinct).also { it.measure(counts) }
        while (boxes.size < maxColors) {
            var pick: Box? = null
            for (b in boxes) if (b.splittable && (pick == null || b.score > pick.score)) pick = b
            val box = pick ?: break
            val axis = run {
                val dr = (box.rMax - box.rMin) * 3; val dg = (box.gMax - box.gMin) * 4; val db = (box.bMax - box.bMin) * 2
                if (dg >= dr && dg >= db) 1 else if (dr >= db) 0 else 2
            }
            // Sort the box's colours along the axis (stable, ties broken by key), split at the weighted median.
            val slice = box.colors.copyOfRange(box.from, box.to)
            val sorted = slice.sortedWith(compareBy<Int>({ comp(it, axis) }, { it }))
            for (i in sorted.indices) box.colors[box.from + i] = sorted[i]
            var acc = 0L
            val half = box.population / 2
            var split = box.from + 1
            for (i in box.from until box.to - 1) {
                acc += counts[box.colors[i]]
                split = i + 1
                if (acc >= half) break
            }
            val other = Box(box.colors, split, box.to).also { it.measure(counts) }
            box.to = split
            box.measure(counts)
            boxes += other
        }
        return IntArray(boxes.size) { i ->
            val b = boxes[i]
            var r = 0L; var g = 0L; var bl = 0L; var tot = 0L
            for (x in b.from until b.to) {
                val k = b.colors[x]; val c = counts[k].toLong()
                r += (k shr 10) * c; g += ((k shr 5) and 31) * c; bl += (k and 31) * c; tot += c
            }
            if (tot == 0L) 0 else {
                val rr = ((r.toDouble() / tot) * 8 + 4).toInt().coerceIn(0, 255)
                val gg = ((g.toDouble() / tot) * 8 + 4).toInt().coerceIn(0, 255)
                val bb = ((bl.toDouble() / tot) * 8 + 4).toInt().coerceIn(0, 255)
                (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    private fun comp(k: Int, axis: Int) = when (axis) { 0 -> k shr 10; 1 -> (k shr 5) and 31; else -> k and 31 }
    private fun cellColor(k: Int): Int = ((((k shr 10) shl 3) or 4) shl 16) or (((((k shr 5) and 31) shl 3) or 4) shl 8) or (((k and 31) shl 3) or 4)
}

/** Variable-width GIF LZW (codes 3..12 bits, clear / end codes, deferred clear when the table is full). */
object GifLzw {
    fun encode(indices: ByteArray, minCodeSize: Int): ByteArray {
        val out = ByteArrayOutputStream(indices.size / 2 + 16)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        var cur = 0; var curBits = 0
        fun emit(code: Int) {
            cur = cur or (code shl curBits); curBits += codeSize
            while (curBits >= 8) { out.write(cur and 0xFF); cur = cur ushr 8; curBits -= 8 }
        }
        // Open-addressing dictionary keyed by (prefix << 8 | byte).
        val size = 1 shl 14
        val keys = IntArray(size) { -1 }
        val vals = IntArray(size)
        fun reset() { java.util.Arrays.fill(keys, -1); next = eoi + 1; codeSize = minCodeSize + 1 }
        emit(clear)
        if (indices.isEmpty()) { emit(eoi); if (curBits > 0) out.write(cur and 0xFF); return out.toByteArray() }
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = (key * 0x9E3779B1.toInt()) ushr 18
            var found = -1
            while (keys[h] != -1) { if (keys[h] == key) { found = vals[h]; break }; h = (h + 1) and (size - 1) }
            if (found >= 0) {
                prefix = found
            } else {
                emit(prefix)
                if (next < 4096) {
                    keys[h] = key; vals[h] = next
                    if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                    next++
                } else {
                    emit(clear)
                    reset()
                }
                prefix = c
            }
        }
        emit(prefix)
        emit(eoi)
        if (curBits > 0) out.write(cur and 0xFF)
        return out.toByteArray()
    }
}

/**
 * Streaming animated-GIF writer: header, loop extension, then one frame at a time with its own local colour table.
 * Nothing is held in memory apart from the frame being written.
 */
class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int, private val loops: Int = 0) {
    private var started = false
    var frames = 0
        private set
    var bytesWritten = 0L
        private set

    private fun w(b: Int) { out.write(b); bytesWritten++ }
    private fun w16(v: Int) { w(v and 0xFF); w((v shr 8) and 0xFF) }
    private fun wb(b: ByteArray) { out.write(b); bytesWritten += b.size }

    private fun header() {
        wb("GIF89a".toByteArray(Charsets.US_ASCII))
        w16(width); w16(height)
        w(0x00) // no global colour table
        w(0); w(0)
        // Netscape loop extension (0 = forever).
        w(0x21); w(0xFF); w(11); wb("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); w(3); w(1); w16(loops); w(0)
        started = true
    }

    fun addFrame(frame: IndexedFrame, delayCs: Int) {
        require(frame.width == width && frame.height == height) { "frame size differs from the GIF" }
        if (!started) header()
        val n = frame.palette.size
        var bits = 1
        while ((1 shl bits) < n) bits++
        bits = bits.coerceIn(1, 8)
        val tableSize = 1 shl bits
        // Graphic control extension.
        w(0x21); w(0xF9); w(4); w(0x00); w16(delayCs.coerceAtLeast(1)); w(0); w(0)
        // Image descriptor with a local colour table.
        w(0x2C); w16(0); w16(0); w16(width); w16(height)
        w(0x80 or (bits - 1))
        for (i in 0 until tableSize) {
            val c = if (i < n) frame.palette[i] else 0
            w((c shr 16) and 0xFF); w((c shr 8) and 0xFF); w(c and 0xFF)
        }
        val minCode = maxOf(2, bits)
        w(minCode)
        val data = GifLzw.encode(frame.indices, minCode)
        var p = 0
        while (p < data.size) {
            val len = minOf(255, data.size - p)
            w(len); out.write(data, p, len); bytesWritten += len
            p += len
        }
        w(0)
        frames++
    }

    fun finish() {
        if (!started) header()
        w(0x3B)
        out.flush()
    }
}
