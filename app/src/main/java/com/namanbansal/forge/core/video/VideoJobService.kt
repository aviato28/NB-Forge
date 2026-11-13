package com.namanbansal.forge.core.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.MainActivity
import com.namanbansal.forge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground service that shows progress + Cancel for the running video job and keeps the process alive. Stops itself when the job ends. */
class VideoJobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = ForgeApp.from(this).videoJobs
        if (intent?.action == ACTION_CANCEL) {
            jobs.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        val record = jobs.current.value
        if (record == null || record.state.value.terminal) {
            // Nothing to do (a late start): satisfy the foreground contract and leave.
            startForegroundCompat(build("Video job", NotificationSpec("Video job", "Nothing running", null, false, false)))
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(build(record.label, JobNotifications.spec(record.label, record.state.value)))
        watcher?.cancel()
        watcher = scope.launch {
            var last: NotificationSpec? = null
            record.state.collectLatest { st ->
                val spec = JobNotifications.spec(record.label, st)
                if (st.terminal) {
                    stopForegroundCompat()
                    getSystemService(NotificationManager::class.java).notify(DONE_ID, build(record.label, spec))
                    stopSelf()
                } else if (JobNotifications.shouldUpdate(last, spec)) {
                    getSystemService(NotificationManager::class.java).notify(ID, build(record.label, spec))
                    last = spec
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING) else startForeground(ID, n)
    }

    private fun stopForegroundCompat() { stopForeground(STOP_FOREGROUND_REMOVE) }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun build(label: String, spec: NotificationSpec): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_video)
            .setContentTitle(spec.title).setContentText(spec.text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (spec.ongoing) b.setProgress(100, spec.percent ?: 0, spec.percent == null)
        if (spec.showCancel) {
            val cancel = PendingIntent.getService(this, 1, Intent(this, VideoJobService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.video_cancel), cancel)
        }
        return b.build()
    }

    companion object {
        const val CHANNEL = "forge_video_jobs"
        const val ID = 4201
        const val DONE_ID = 4202
        const val ACTION_CANCEL = "com.namanbansal.forge.video.CANCEL"

        fun launcher(context: Context) = ServiceLauncher {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VideoJobService::class.java)) }
        }
    }
}
