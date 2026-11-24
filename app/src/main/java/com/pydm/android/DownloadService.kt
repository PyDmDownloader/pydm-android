package com.pydm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Downloads chalte rahein jab app background mein ho. */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!Manager.hasActive()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pydm:dl").also { it.acquire(6 * 3600 * 1000L) }
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1500)
        return START_STICKY
    }

    private fun build(): Notification {
        val n = Manager.activeCount()
        val running = Manager.tasks.filter { it.isRunning }
        val speed = running.sumOf { it.speed }
        val text = if (running.isEmpty()) "$n queued" else
            "${running.size} active · " + Fmt.speed(speed)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PyDM")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try { wake?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "pydm_downloads"
        private const val NOTIF_ID = 1
        @Volatile private var lastStart = 0L

        fun ensureRunning(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - lastStart < 5000) return
            lastStart = now
            try { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) } catch (_: Throwable) {}
        }
    }
}
