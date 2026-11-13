package com.namanbansal.forge.core.video

import android.content.Context
import com.namanbansal.forge.core.MediaTarget
import com.namanbansal.forge.core.SavedOutput
import java.io.File

/** Shared plumbing for the video tools' background jobs. */
object VideoOps {
    const val WORK_DIR = "video-work"

    fun workDir(context: Context): File = File(context.cacheDir, WORK_DIR).also { it.mkdirs() }

    /** Removes temp files left behind by a previous run that was killed. */
    fun cleanWorkDir(context: Context) { runCatching { File(context.cacheDir, WORK_DIR).deleteRecursively() } }

    /**
     * Runs [exporter] into a temp file, then streams the finished file into the media store (so nothing half-written ever shows up
     * in the gallery). The temp file is always removed.
     */
    suspend fun JobContext.exportAndSave(
        exporter: MediaExporter,
        workDir: File,
        request: ExportRequest,
        action: String,
        ext: String,
        mime: String,
        target: MediaTarget = MediaTarget.VIDEO,
    ): SavedOutput {
        workDir.mkdirs()
        val tmp = File(workDir, "job-${System.nanoTime()}.$ext")
        try {
            exporter.export(request, tmp) { progress(it * 0.97f) }
            if (!tmp.exists() || tmp.length() == 0L) throw com.namanbansal.forge.core.ToolException("The export produced no output. This file may use a format Forge can't process.")
            return save(action, ext, mime, target) { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally {
            tmp.delete()
        }
    }
}
