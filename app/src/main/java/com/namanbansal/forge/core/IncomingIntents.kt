package com.namanbansal.forge.core

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Extracts the files another app handed to Forge via ACTION_SEND, ACTION_SEND_MULTIPLE or ACTION_VIEW. */
object IncomingIntents {

    @Suppress("DEPRECATION")
    fun uris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { found += it }
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                stream?.let { found += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.let { found += it }
            }
            else -> return emptyList()
        }
        // Some senders only populate ClipData.
        if (found.isEmpty() && intent.action != Intent.ACTION_VIEW) {
            val clip = intent.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { found += it }
        }
        return found.filter { it.scheme == "content" || it.scheme == "file" }
    }

    /** Only PDFs and images are accepted from other apps for now. */
    fun accept(files: List<PickedFile>): List<PickedFile> = files.filter { it.kind == FileKind.PDF || it.kind == FileKind.IMAGE }
}
