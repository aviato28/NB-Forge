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
import java.io.File

/**
 * Page thumbnails via [PdfRenderer]: one renderer, pages rendered one at a time off the main thread,
 * downsampled to [maxSidePx], held in a byte-bounded LRU cache so a 500-page document stays small.
 * Bitmaps in the cache are shared with the UI: callers must not recycle them.
 */
class PdfThumbnails(
    file: File,
    private val maxSidePx: Int = 360,
    cacheBytes: Int = defaultCacheBytes(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (t: Throwable) {
        fd.close()
        throw t
    }
    private val renderLock = Mutex() // PdfRenderer allows a single open page at a time
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    private val cache = object : LruCache<Int, Bitmap>(cacheBytes.coerceAtLeast(1 shl 20)) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    /** Cached bitmap if already rendered (cheap, main-thread safe). */
    fun peek(page: Int): Bitmap? = synchronized(cache) { cache.get(page) }

    /** Renders (or returns cached) thumbnail of 0-based [page]; null if it cannot be rendered. */
    suspend fun get(page: Int): Bitmap? {
        peek(page)?.let { return it }
        if (page !in 0 until pageCount) return null
        return withContext(dispatcher) {
            renderLock.withLock {
                peek(page)?.let { return@withLock it }
                if (closed) return@withLock null
                try {
                    renderer.openPage(page).use { p ->
                        val scale = maxSidePx.toFloat() / maxOf(p.width, p.height).coerceAtLeast(1)
                        val w = (p.width * scale).toInt().coerceAtLeast(1)
                        val h = (p.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        synchronized(cache) { cache.put(page, bmp) }
                        bmp
                    }
                } catch (_: Exception) {
                    null // one unrenderable page must not break the grid; the cell shows a placeholder
                }
            }
        }
    }

    /** Page size in PDF points (width, height), for placeholders before the bitmap exists. */
    suspend fun pageSize(page: Int): Pair<Int, Int>? = withContext(dispatcher) {
        renderLock.withLock {
            if (closed || page !in 0 until pageCount) return@withLock null
            try { renderer.openPage(page).use { it.width to it.height } } catch (_: Exception) { null }
        }
    }

    fun close() {
        closed = true
        // Take the lock so we never close the renderer under an in-flight render.
        if (renderLock.tryLock()) {
            try { renderer.close(); fd.close() } catch (_: Exception) {} finally { renderLock.unlock() }
        } else {
            // A render is in flight; it will finish shortly. Close on a background thread once it releases.
            Thread {
                kotlinx.coroutines.runBlocking {
                    renderLock.withLock { try { renderer.close(); fd.close() } catch (_: Exception) {} }
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
