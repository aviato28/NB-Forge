package com.namanbansal.forge.core.pdf

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Handed to a transform: cooperative cancel + progress (0..1). */
class TransformCtx(val checkCancelled: () -> Unit, val onProgress: (Float) -> Unit)

/**
 * Base for the "one PDF in, one PDF out" tools. The document is opened with temp-file memory (streams big files), [transform]
 * edits it in place and returns the one-line result detail, then it is saved (keeping the input's protection unless
 * [protectionFor] says otherwise) through [RunScope.save].
 */
abstract class PdfTransformViewModel(application: Application, saved: SavedStateHandle) : PdfSelectionViewModel(application, saved) {

    /** Suffix of the output file name: `<name>_<action>.pdf`. */
    protected abstract val action: String
    protected open val headline: String get() = "Done"

    /** Throw a [ToolException] when the options aren't usable yet. */
    protected open fun validate(source: PdfSource) {}

    /** Edit [doc] in place; return the result detail. Runs off the main thread. */
    protected abstract fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String?

    protected open fun protectionFor(source: PdfSource): PdfProtection? = source.protection

    /** Loads a document leniently with temp-file memory. */
    protected fun load(file: File): PDDocument = PdfOps.load(file)

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val src = source.value ?: throw ToolException("Open a PDF first.")
        validate(src)
        var detail: String? = null
        val saved = scope.save(action, "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                try {
                    PdfOps.load(src.file).use { doc ->
                        detail = transform(doc, src, TransformCtx({ ctx.ensureActive() }, { scope.progress(it) }))
                        ctx.ensureActive()
                        protectionFor(src)?.applyTo(doc)
                        doc.save(out)
                    }
                } catch (e: IOException) {
                    throw ToolException("Could not process this PDF: ${e.message ?: "the file could not be processed"}.", e)
                }
            }
            scope.progress(1f)
        }
        return ToolOutcome(saved, headline = headline, detail = detail)
    }
}

object PdfOps {
    fun load(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES))
}

/** Renders one edited page for live previews: apply [edit] to the whole document, keep only the page, rasterise it. */
object PdfPreview {
    suspend fun render(source: File, page: Int, widthPx: Int, dir: File, edit: (PDDocument) -> Unit): Bitmap? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File.createTempFile("pv-", ".pdf", dir)
        try {
            PdfOps.load(source).use { doc ->
                if (page !in 0 until doc.numberOfPages) return@withContext null
                edit(doc)
                for (i in doc.numberOfPages - 1 downTo 0) if (i != page) doc.removePage(i)
                doc.save(tmp)
            }
            renderFirstPage(tmp, widthPx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** First page of [file], [widthPx] wide, on white. */
    fun renderFirstPage(file: File, widthPx: Int): Bitmap? {
        val r = PageRasterizers.factory(file)
        return try {
            val size = r.pageSize(0) ?: return null
            val h = (widthPx.toFloat() * size.second / size.first.coerceAtLeast(1)).toInt().coerceIn(1, 4000)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            r.render(0, bmp)
            bmp
        } finally { r.close() }
    }
}
