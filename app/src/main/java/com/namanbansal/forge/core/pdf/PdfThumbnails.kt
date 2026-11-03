package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Draws pages of one PDF into bitmaps. The production engine is [AndroidPageRasterizer]; tests can swap it via [PageRasterizers]. */
interface PageRasterizer : Closeable {
    val pageCount: Int
    /** Page size in PDF points (width, height) as it is displayed (rotation applied); null if unreadable. */
    fun pageSize(page: Int): Pair<Int, Int>?
    /** Renders 0-based [page] scaled to fill the whole [bitmap] (already white-filled by the caller). */
    fun render(page: Int, bitmap: Bitmap)
    /**
     * Renders one tile: the page drawn [pageWidthPx] pixels wide, of which the region starting at ([left], [top]) of size
     * [w] x [h] goes to the top-left of [bitmap] (white-filled by the caller; pixels outside w x h are left alone).
     */
    fun renderTile(page: Int, bitmap: Bitmap, pageWidthPx: Int, left: Int, top: Int, w: Int, h: Int)
}

/** [PdfRenderer]-backed rasterizer (one open page at a time; callers serialise access). */
class AndroidPageRasterizer(file: File) : PageRasterizer {
    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (t: Throwable) {
        fd.close()
        throw t
    }

    override val pageCount: Int = renderer.pageCount

    override fun pageSize(page: Int): Pair<Int, Int>? = renderer.openPage(page).use { it.width to it.height }

    override fun render(page: Int, bitmap: Bitmap) {
        renderer.openPage(page).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    override fun renderTile(page: Int, bitmap: Bitmap, pageWidthPx: Int, left: Int, top: Int, w: Int, h: Int) {
        renderer.openPage(page).use { p ->
            val s = pageWidthPx.toFloat() / p.width.coerceAtLeast(1)
            val m = android.graphics.Matrix().apply { setScale(s, s); postTranslate(-left.toFloat(), -top.toFloat()) }
            p.render(bitmap, android.graphics.Rect(0, 0, w, h), m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        try { renderer.close() } finally { fd.close() }
    }
}

/** Where [PdfThumbnails] gets its rasterizer. Tests replace [factory] (Robolectric's PdfRenderer draws nothing). */
object PageRasterizers {
    @Volatile var factory: (File) -> PageRasterizer = { AndroidPageRasterizer(it) }
}

/**
 * Page bitmaps for grids and the viewer: one rasterizer, pages rendered one at a time off the main thread,
 * held in a byte-bounded LRU cache so a 500-page document stays small.
 * [get] makes a thumbnail whose longer side is [maxSidePx]; [render] makes a page of a given pixel width.
 * Bitmaps in the cache are shared with the UI: callers must not recycle them.
 */
class PdfThumbnails(
    file: File,
    private val maxSidePx: Int = 360,
    cacheBytes: Int = defaultCacheBytes(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val rasterizer: PageRasterizer = PageRasterizers.factory(file)
    private val renderLock = Mutex() // a PdfRenderer allows a single open page at a time
    @Volatile private var closed = false

    val pageCount: Int = rasterizer.pageCount

    private val cache = object : LruCache<Long, Bitmap>(cacheBytes.coerceAtLeast(1 shl 20)) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    private fun key(page: Int, width: Int) = (page.toLong() shl 32) or width.toLong()

    /** Cached thumbnail if already rendered (cheap, main-thread safe). */
    fun peek(page: Int): Bitmap? = synchronized(cache) { cache.get(key(page, 0)) }

    /** Cached page at exactly [widthPx], if rendered. */
    fun peek(page: Int, widthPx: Int): Bitmap? = synchronized(cache) { cache.get(key(page, widthPx)) }

    /** Renders (or returns cached) thumbnail of 0-based [page]; null if it cannot be rendered. */
    suspend fun get(page: Int): Bitmap? = renderCached(page, 0) { w, h ->
        val scale = maxSidePx.toFloat() / maxOf(w, h).coerceAtLeast(1)
        (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Renders (or returns cached) [page] [widthPx] pixels wide (height follows the page's aspect ratio, capped at [maxPixels]
     * in total by reducing the size), for the viewer. Null if it cannot be rendered.
     */
    suspend fun render(page: Int, widthPx: Int, maxPixels: Int = 6_000_000): Bitmap? {
        val requested = widthPx.coerceAtLeast(16)
        return renderCached(page, requested) { w, h ->
            var tw = requested
            var th = (h.toFloat() / w.coerceAtLeast(1) * tw).toInt().coerceAtLeast(1)
            if (tw.toLong() * th > maxPixels) {
                val f = Math.sqrt(maxPixels.toDouble() / (tw.toLong() * th)).toFloat()
                tw = (tw * f).toInt().coerceAtLeast(1); th = (th * f).toInt().coerceAtLeast(1)
            }
            tw to th
        }
    }

    private suspend fun renderCached(page: Int, widthKey: Int, sizeFor: (Int, Int) -> Pair<Int, Int>): Bitmap? {
        synchronized(cache) { cache.get(key(page, widthKey)) }?.let { return it }
        if (page !in 0 until pageCount) return null
        return withContext(dispatcher) {
            renderLock.withLock {
                synchronized(cache) { cache.get(key(page, widthKey)) }?.let { return@withLock it }
                if (closed) return@withLock null
                try {
                    val (pw, ph) = rasterizer.pageSize(page) ?: return@withLock null
                    val (w, h) = sizeFor(pw, ph)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    rasterizer.render(page, bmp)
                    synchronized(cache) { cache.put(key(page, widthKey), bmp) }
                    bmp
                } catch (_: Exception) {
                    null // one unrenderable page must not break the grid; the cell shows a placeholder
                } catch (_: OutOfMemoryError) {
                    synchronized(cache) { cache.evictAll() }
                    null
                }
            }
        }
    }

    /** Page size in PDF points (width, height), for placeholders before the bitmap exists. */
    suspend fun pageSize(page: Int): Pair<Int, Int>? = withContext(dispatcher) {
        renderLock.withLock {
            if (closed || page !in 0 until pageCount) return@withLock null
            try { rasterizer.pageSize(page) } catch (_: Exception) { null }
        }
    }

    fun close() {
        closed = true
        // Take the lock so we never close the renderer under an in-flight render.
        if (renderLock.tryLock()) {
            try { rasterizer.close() } catch (_: Exception) {} finally { renderLock.unlock() }
        } else {
            // A render is in flight; it will finish shortly. Close on a background thread once it releases.
            Thread {
                kotlinx.coroutines.runBlocking {
                    renderLock.withLock { try { rasterizer.close() } catch (_: Exception) {} }
                }
            }.start()
        }
        synchronized(cache) { cache.evictAll() }
    }

    companion object {
        /** About 1/10 of the heap, capped to 48 MB. */
        fun defaultCacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 10).coerceIn(8L shl 20, 48L shl 20).toInt()
    }
}
