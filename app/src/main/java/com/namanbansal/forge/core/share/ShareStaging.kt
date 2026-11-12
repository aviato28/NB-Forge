package com.namanbansal.forge.core.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.namanbansal.forge.core.OutputNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts a file where other apps may read it: `cache/share/<n>/<real name>` behind Forge's FileProvider, so the receiving app sees
 * the true file name (not "render-3f2a.pdf") and a read grant that works even when the original came from a private provider.
 */
object ShareStaging {
    const val DIR = "share"

    /** The staged file's path for [name]; each call gets its own folder so equal names never clash. */
    fun target(cacheDir: File, name: String, slot: Long = System.nanoTime()): File {
        val safe = OutputNaming.keep(name) { false }
        return File(File(File(cacheDir, DIR), slot.toString()), safe)
    }

    suspend fun stage(context: Context, source: File, name: String): Uri = withContext(Dispatchers.IO) {
        val dst = target(context.cacheDir, name)
        dst.parentFile?.mkdirs()
        source.copyTo(dst, overwrite = true)
        FileProvider.getUriForFile(context, "${context.packageName}.files", dst)
    }

    /** Clears staged files (called on start; sharing is long finished by then). */
    fun clean(cacheDir: File) { File(cacheDir, DIR).deleteRecursively() }
}
