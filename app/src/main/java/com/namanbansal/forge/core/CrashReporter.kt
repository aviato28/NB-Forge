package com.namanbansal.forge.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.namanbansal.forge.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the stack trace of an uncaught exception to a file so the next launch can show it. Local only:
 * no network, no analytics; the owner shares the report by hand.
 */
class CrashReporter(private val dir: File) {
    private val report = File(dir, "last_crash.txt")
    /** Present while the latest crash has not been looked at yet (drives the "closed unexpectedly" card). */
    private val unseen = File(dir, "last_crash.unseen")

    fun install(previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Never throws: a failing reporter must not hide the original crash. */
    fun record(thread: Thread, error: Throwable, now: Long = System.currentTimeMillis()) {
        try {
            dir.mkdirs()
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))
            report.writeText(
                "Forge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Time: $stamp\nThread: ${thread.name}\n\n$trace"
            )
            unseen.writeText("1")
        } catch (_: Throwable) { }
    }

    fun read(): String? = try { report.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    fun hasUnseen(): Boolean = unseen.exists() && read() != null

    fun markSeen() { runCatching { unseen.delete() } }

    fun clear() { markSeen(); runCatching { report.delete() } }

    companion object {
        fun share(context: Context, text: String): Boolean = try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Forge crash report")
            context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) { false }

        fun copy(context: Context, text: String) {
            runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Forge crash report", text)) }
        }
    }
}
