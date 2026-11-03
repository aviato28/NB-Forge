package com.namanbansal.forge

import android.app.Application
import android.content.Context
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.PendingFiles
import com.namanbansal.forge.core.RecentFilesStore
import com.namanbansal.forge.data.SettingsRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import com.namanbansal.forge.update.UpdateManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** App-wide singletons (no DI framework by design). */
class ForgeApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Forge/${BuildConfig.VERSION_NAME} (Android)").build())
            }
            .build()
    }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val updates: UpdateManager by lazy { UpdateManager(this, settings, http) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val outputSaver: OutputSaver by lazy { OutputSaver(this) }
    val pendingFiles = PendingFiles()
    val recents: RecentFilesStore by lazy { RecentFilesStore(File(filesDir, "recent_files.json")) }

    val viewerPositions: com.namanbansal.forge.core.pdf.viewer.ViewerPositionStore by lazy {
        com.namanbansal.forge.core.pdf.viewer.ViewerPositionStore(File(filesDir, "viewer_positions.json"), File(filesDir, "viewer_covers"))
    }
    val documentWriter: com.namanbansal.forge.core.DocumentWriter by lazy { com.namanbansal.forge.core.DocumentWriter.create(this, outputSaver) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext) // once; PDFBox needs it for its bundled resources
        appScope.launch {
            recents.load()
            viewerPositions.load()
            // Working copies from a previous run are never reused.
            File(cacheDir, com.namanbansal.forge.core.pdf.PdfLoader.WORK_DIR).deleteRecursively()
        }
    }

    companion object {
        fun from(context: Context): ForgeApp = context.applicationContext as ForgeApp
    }
}
