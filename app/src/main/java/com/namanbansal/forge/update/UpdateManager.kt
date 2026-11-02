package com.namanbansal.forge.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.namanbansal.forge.BuildConfig
import com.namanbansal.forge.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] in 0..1, or -1 when the size is unknown. */
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Download finished but "Install unknown apps" is not granted yet. */
    data class NeedsPermission(val info: UpdateInfo, val apk: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Check -> download (with progress, sha256 verified when published) -> install via PackageInstaller session.
 * Android always shows its own confirmation; no silent installs.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
    private val source: UpdateSource = UpdateConfig.source(client),
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** True while the sheet should be visible for a user-visible flow. */
    private val _sheetVisible = MutableStateFlow(false)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    private var job: Job? = null

    /** Silent check on app start: at most once per 24h, only if enabled. Only surfaces a found update. */
    fun autoCheckIfDue() {
        scope.launch {
            val s = settings.current()
            if (!s.autoCheckUpdates) return@launch
            if (System.currentTimeMillis() - s.lastUpdateCheck < DAY_MS) return@launch
            check(manual = false)
        }
    }

    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val s = settings.current()
            val channel = if (s.betaUpdates) Channel.Beta else Channel.Stable
            try {
                val info = source.latest(channel)
                settings.setLastUpdateCheck(System.currentTimeMillis())
                if (Versions.isNewer(info, currentVersionCode)) {
                    _state.value = UpdateState.Available(info!!)
                    _sheetVisible.value = true
                } else if (manual) {
                    _state.value = UpdateState.UpToDate
                } else {
                    _state.value = UpdateState.Idle
                }
            } catch (e: UpdateException) {
                _state.value = if (manual) UpdateState.Failed(e.message ?: "Update check failed.") else UpdateState.Idle
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _sheetVisible.value = false
        _state.value = UpdateState.Idle
    }

    fun download(info: UpdateInfo) {
        job?.cancel()
        job = scope.launch {
            _sheetVisible.value = true
            _state.value = UpdateState.Downloading(info, if (info.size > 0) 0f else -1f)
            try {
                val apk = downloadApk(info)
                proceedToInstall(info, apk)
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.message ?: "Download failed.", info)
            }
        }
    }

    /** Called when the user returns from the "Install unknown apps" settings screen. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && canInstall()) proceedToInstall(s.info, s.apk)
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun proceedToInstall(info: UpdateInfo, apk: File) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(info, apk)
            return
        }
        _state.value = UpdateState.Installing(info)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { installApk(apk) }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Couldn't start the installer: ${e.message}", info)
            }
        }
    }

    /** Result from [InstallResultReceiver]. */
    internal fun onInstallResult(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> dismiss()
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value =
                if (info != null) UpdateState.Available(info) else UpdateState.Idle
            else -> _state.value = UpdateState.Failed("Install failed${message?.let { ": $it" } ?: "."}", info)
        }
    }

    private suspend fun downloadApk(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "forge-${info.version}.apk")
        val req = Request.Builder().url(info.apkUrl).build()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw UpdateException(UpdateException.Kind.Download, "Download failed (HTTP ${resp.code}).")
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastEmit = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (total > 0 && read - lastEmit > 128 * 1024) {
                                lastEmit = read
                                _state.value = UpdateState.Downloading(info, (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            out.delete()
            throw UpdateException(UpdateException.Kind.Offline, "Download interrupted. Check your connection.", e)
        }
        val expected = info.sha256
        if (expected != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                out.delete()
                throw UpdateException(UpdateException.Kind.Checksum, "Downloaded file failed verification and was discarded.")
            }
        }
        out
    }

    private fun installApk(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // API 31+: lets Android skip the confirm tap for updates of this same app. Android 11 always asks.
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("forge.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
