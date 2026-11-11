package com.namanbansal.forge.tools.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.namanbansal.forge.MainActivity
import com.namanbansal.forge.R
import com.namanbansal.forge.core.util.Phase
import com.namanbansal.forge.core.util.PlanClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the running countdown looks like; shared between [TimerService] and the Timers screen. */
data class TimerRun(val phases: List<Phase>, val startedAt: Long, val ringing: Boolean = false)

object TimerEngine {
    private val _run = MutableStateFlow<TimerRun?>(null)
    val run: StateFlow<TimerRun?> = _run.asStateFlow()
    internal fun set(r: TimerRun?) { _run.value = r }

    fun start(context: Context, phases: List<Phase>) {
        val i = Intent(context, TimerService::class.java).setAction(TimerService.ACTION_START)
            .putExtra("labels", phases.map { it.label }.toTypedArray()).putExtra("seconds", phases.map { it.seconds }.toIntArray())
        ContextCompat.startForegroundService(context, i)
    }

    fun stop(context: Context) { context.startService(Intent(context, TimerService::class.java).setAction(TimerService.ACTION_STOP)) }
}

/** Foreground service that keeps the countdown alive in the background and rings (alarm sound + vibration) when it ends. */
class TimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var run: TimerRun? = null
    private var lastPhase = -1
    private var ringtone: Ringtone? = null
    private var wake: PowerManager.WakeLock? = null
    private val tick = object : Runnable { override fun run() { onTick(); handler.postDelayed(this, 500) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val labels = intent.getStringArrayExtra("labels") ?: emptyArray()
                val secs = intent.getIntArrayExtra("seconds") ?: IntArray(0)
                val phases = labels.zip(secs.toList()).map { Phase(it.first, it.second) }.filter { it.seconds > 0 }
                if (phases.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                stopRinging()
                channels()
                val r = TimerRun(phases, System.currentTimeMillis()); run = r; lastPhase = 0; TimerEngine.set(r)
                startInForeground(ongoing(r, 0))
                runCatching {
                    wake?.takeIf { it.isHeld }?.release()
                    wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forge:timer").apply { acquire(PlanClock.totalMs(phases) + 60_000) }
                }
                handler.removeCallbacks(tick); handler.post(tick)
            }
            ACTION_STOP -> { finishAll() }
        }
        return START_NOT_STICKY
    }

    private fun onTick() {
        val r = run ?: return
        if (r.ringing) return
        val pos = PlanClock.at(r.phases, r.startedAt, System.currentTimeMillis())
        if (pos.finished) { ring(r); return }
        if (pos.index != lastPhase) {
            lastPhase = pos.index
            beep(); notifyOngoing(r, pos.index)
        }
    }

    private fun ring(r: TimerRun) {
        val done = r.copy(ringing = true); run = done; TimerEngine.set(done)
        handler.removeCallbacks(tick)
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TimerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, ALARM_CHANNEL).setSmallIcon(R.drawable.ic_tile_torch).setContentTitle("Timer finished")
            .setContentText(r.phases.last().label.ifBlank { "Time's up" }).setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open).addAction(0, "Stop", stop).setOngoing(true).build()
        getSystemService(NotificationManager::class.java).notify(ID, n)
        startRinging()
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                isLooping = true; play()
            }
        }
        runCatching { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) }
        handler.postDelayed({ finishAll() }, 5 * 60_000L) // never ring forever
    }

    private fun beep() {
        runCatching { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
        runCatching { (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel() }
    }

    private fun finishAll() {
        handler.removeCallbacksAndMessages(null); stopRinging()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        run = null; TimerEngine.set(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        stopSelf()
    }

    private fun startInForeground(n: Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
    }

    private fun notifyOngoing(r: TimerRun, index: Int) { getSystemService(NotificationManager::class.java).notify(ID, ongoing(r, index)) }

    private fun ongoing(r: TimerRun, index: Int): Notification {
        val end = PlanClock.phaseEnd(r.phases, r.startedAt, index)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TimerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile_torch).setContentTitle(r.phases[index].label.ifBlank { "Timer" })
            .setContentText("Counting down").setUsesChronometer(true).setChronometerCountDown(true).setWhen(end).setShowWhen(true)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentIntent(open).addAction(0, "Cancel", stop).build()
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.timer_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, getString(R.string.timer_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); stopRinging(); runCatching { wake?.takeIf { it.isHeld }?.release() }; super.onDestroy() }

    companion object {
        const val ACTION_START = "com.namanbansal.forge.timer.START"
        const val ACTION_STOP = "com.namanbansal.forge.timer.STOP"
        private const val CHANNEL = "forge_timer"
        private const val ALARM_CHANNEL = "forge_timer_alarm"
        private const val ID = 4207
    }
}
