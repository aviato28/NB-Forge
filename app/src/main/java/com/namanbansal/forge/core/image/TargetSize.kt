package com.namanbansal.forge.core.image

/** Finds the highest JPEG/WebP quality whose output still fits a byte budget (binary search; size is monotonic in quality). */
object TargetSize {
    data class Result(val quality: Int, val size: Long, val fits: Boolean, val steps: Int)

    /**
     * Searches [lo]..[hi]. [sizeAt] encodes at a quality and returns the byte size. If even [lo] is too big, returns [lo] with fits = false.
     * [checkCancelled] runs before every encode.
     */
    fun search(target: Long, lo: Int = 5, hi: Int = 95, checkCancelled: () -> Unit = {}, sizeAt: (Int) -> Long): Result {
        var steps = 0
        fun probe(q: Int): Long { checkCancelled(); steps++; return sizeAt(q) }
        val hiSize = probe(hi)
        if (hiSize <= target) return Result(hi, hiSize, true, steps)
        val loSize = probe(lo)
        if (loSize > target) return Result(lo, loSize, false, steps)
        var a = lo
        var b = hi
        var best = lo
        var bestSize = loSize
        while (b - a > 1) {
            val mid = (a + b) / 2
            val s = probe(mid)
            if (s <= target) { a = mid; best = mid; bestSize = s } else b = mid
        }
        return Result(best, bestSize, true, steps)
    }
}
