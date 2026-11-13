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
    /** Video / audio jobs run here (and in [com.namanbansal.forge.core.video.VideoJobService]) so they outlive the screen. */
    val videoJobs: com.namanbansal.forge.core.video.VideoJobs by lazy {
        com.namanbansal.forge.core.video.VideoJobs(outputSaver, CoroutineScope(SupervisorJob() + Dispatchers.Default), com.namanbansal.forge.core.video.VideoJobService.launcher(this))
    }
    /** Replaceable in tests (fake exporter / probe). */
    var mediaExporter: com.namanbansal.forge.core.video.MediaExporter = com.namanbansal.forge.core.video.TransformerExporter(this)
    var frameSourceFactory: (Context, android.net.Uri) -> com.namanbansal.forge.core.video.FrameSource = { c, u -> com.namanbansal.forge.core.video.RetrieverFrameSource(c, u) }
    val encoderCaps: com.namanbansal.forge.core.video.EncoderCapabilities by lazy { com.namanbansal.forge.core.video.EncoderCapabilities() }
    var mediaProbe: com.namanbansal.forge.core.video.MediaProbe = com.namanbansal.forge.core.video.ExtractorProbe(this)
    val recents: RecentFilesStore by lazy { RecentFilesStore(File(filesDir, "recent_files.json")) }

    val viewerPositions: com.namanbansal.forge.core.pdf.viewer.ViewerPositionStore by lazy {
        com.namanbansal.forge.core.pdf.viewer.ViewerPositionStore(File(filesDir, "viewer_positions.json"), File(filesDir, "viewer_covers"))
    }
    val documentWriter: com.namanbansal.forge.core.DocumentWriter by lazy { com.namanbansal.forge.core.DocumentWriter.create(this, outputSaver) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext) // once; PDFBox needs it for its bundled resources
        appScope.launch {
            // Working copies from a previous run are never reused (first, so it can never race with a file opened right after start).
            File(cacheDir, com.namanbansal.forge.core.pdf.PdfLoader.WORK_DIR).deleteRecursively()
            com.namanbansal.forge.core.share.ShareStaging.clean(cacheDir)
            com.namanbansal.forge.core.video.VideoOps.cleanWorkDir(this@ForgeApp)
            recents.load()
            viewerPositions.load()
        }
    }

    companion object {
        fun from(context: Context): ForgeApp = context.applicationContext as ForgeApp
    }
}
