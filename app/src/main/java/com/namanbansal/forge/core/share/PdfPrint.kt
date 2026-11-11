package com.namanbansal.forge.core.share

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Feeds an existing PDF file to Android's print service (which also offers "Save as PDF"). */
class PdfPrintAdapter(private val file: File, private val jobName: String, private val pageCount: Int = PrintDocumentInfo.PAGE_COUNT_UNKNOWN) : PrintDocumentAdapter() {

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel?.isCanceled == true) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, old != new)
    }

    override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancel?.isCanceled == true) { callback.onWriteCancelled(); return }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message ?: "Could not read the PDF")
        }
    }

    companion object {
        /** Opens the system print dialog; false when printing is not available. */
        fun print(context: Context, file: File, jobName: String, pageCount: Int): Boolean = try {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
            pm.print(jobName, PdfPrintAdapter(file, jobName, pageCount), null)
            true
        } catch (_: Exception) {
            false
        }
    }
}
