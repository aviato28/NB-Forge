package com.namanbansal.forge.core.pdf.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.namanbansal.forge.core.pdf.PageRasterizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A rendered tile; [image] is created once so drawing allocates nothing. */
class Tile(val bitmap: Bitmap, val width: Int, val height: Int) {
    val image: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) { bitmap.asImageBitmap() }
}

/**
 * Renders hi-res tiles of the visible region on one background thread (a `PdfRenderer` allows one open page at a time).
 * The UI says what it wants with [setWanted]; tiles that scroll away are simply dropped from that list, so they are never
 * started. Finished tiles live in a byte-bounded [TileCache]; evicted bitmaps are recycled through a small pool.
 * [revision] changes whenever a tile lands so canvases redraw without recomposing.
 */
class TileRenderer(
    private val rasterizer: PageRasterizer,
    budgetBytes: Long = defaultBudget(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val focusPage: () -> Int = { 0 },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pool = ArrayDeque<Bitmap>()
    private val cache = TileCache<Tile>(budgetBytes, { it.bitmap.allocationByteCount.toLong() }) { t -> recycle(t.bitmap) }
    private val wanted = LinkedHashMap<Int, List<TileKey>>()
    private var pinned: Set<TileKey> = emptySet()
    private val failed = HashSet<TileKey>()
    private val pageSizes = HashMap<Int, Pair<Int, Int>?>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false

    val revision = mutableIntStateOf(0)
    /** Tiles rendered so far (tests, diagnostics). */
    @Volatile var rendered = 0
        private set

    private val worker: Job = scope.launch { loop() }

    fun peek(key: TileKey): Tile? = cache.get(key)
    fun cachedBytes(): Long = cache.totalBytes()
    fun cachedCount(): Int = cache.size()

    /** Replaces the tiles page [page] wants (nearest first). An empty list means the page is off screen. */
    fun setWanted(page: Int, keys: List<TileKey>) {
        synchronized(wanted) {
            if (keys.isEmpty()) wanted.remove(page) else wanted[page] = keys
            pinned = wanted.values.flatten().toHashSet()
        }
        wake.trySend(Unit)
    }

    private fun next(): TileKey? = synchronized(wanted) {
        val focus = focusPage()
        for (owner in wanted.keys.sortedBy { kotlin.math.abs(it - focus) }) {
            for (k in wanted.getValue(owner)) if (!cache.contains(k) && k !in failed) return k
        }
        null
    }

    private suspend fun loop() {
        while (scope.isActive && !closed) {
            val key = next()
            if (key == null) { wake.receive(); continue }
            try {
                renderOne(key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                cache.trim(emptySet()); synchronized(pool) { pool.clear() }
                failed += key
            } catch (_: Exception) {
                failed += key // an unrenderable region must not spin the loop
            }
        }
    }

    private fun renderOne(key: TileKey) {
        val size = pageSizes.getOrPut(key.page) { rasterizer.pageSize(key.page) } ?: run { failed += key; return }
        val ptsW = size.first.coerceAtLeast(1)
        val pageHpx = (key.pageWidthPx.toLong() * size.second / ptsW).toInt().coerceAtLeast(1)
        val r = TileMath.tileRect(key, key.pageWidthPx, pageHpx)
        if (r.width <= 0 || r.height <= 0) { failed += key; return }
        val bmp = synchronized(pool) { pool.removeFirstOrNull() } ?: Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        rasterizer.renderTile(key.page, bmp, key.pageWidthPx, r.left, r.top, r.width, r.height)
        cache.put(key, Tile(bmp, r.width, r.height), synchronized(wanted) { pinned })
        rendered++
        revision.intValue++
    }

    private fun recycle(b: Bitmap) {
        synchronized(pool) { if (pool.size < POOL_MAX && b.isMutable) pool.addLast(b) }
    }

    fun close() {
        closed = true
        wake.trySend(Unit)
        scope.launch {
            worker.cancelAndJoin()
            runCatching { rasterizer.close() }
            cache.clear(); synchronized(pool) { pool.clear() }
        }.invokeOnCompletion { scope.coroutineContext[Job]?.cancel() }
    }

    companion object {
        const val POOL_MAX = 6
        /** A tenth of the heap, 24..96 MB: room for the ~25 tiles a phone shows at 8x plus a margin around them. */
        fun defaultBudget(): Long = (Runtime.getRuntime().maxMemory() / 10).coerceIn(24L shl 20, 96L shl 20)
    }
}
