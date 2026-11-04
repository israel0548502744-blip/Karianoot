package com.karianoot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object Notify {
    const val PROGRESS = "progress"
    const val DONE = "done"
    const val PROGRESS_ID = 1
    const val DONE_ID = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(PROGRESS, "קריינות בהכנה", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DONE, "קריינות מוכנה", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun progress(ctx: Context, s: JobState): Notification = NotificationCompat.Builder(ctx, PROGRESS)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("מכין קריינות…")
        .setContentText(s.stage)
        .setProgress(100, (s.progress * 100).toInt(), s.progress <= 0f)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(openApp(ctx))
        .build()
}

/** Keeps the app alive (and the CPU awake) while a narration is being made in the background. */
class NarrationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as KarianootApp
        ServiceCompat.startForeground(this, Notify.PROGRESS_ID, Notify.progress(this, app.job.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "karianoot:narration").apply { acquire(60 * 60 * 1000L) }
        if (watcher == null) watcher = scope.launch {
            var last = 0L
            app.job.collectLatest { s ->
                val nm = getSystemService(NotificationManager::class.java)
                if (s.busy) {
                    val now = System.currentTimeMillis()
                    if (now - last > 500) { nm.notify(Notify.PROGRESS_ID, Notify.progress(this@NarrationService, s)); last = now }
                } else {
                    val done = NotificationCompat.Builder(this@NarrationService, Notify.DONE)
                        .setSmallIcon(R.drawable.ic_notify)
                        .setContentTitle(if (s.error == null) "הקריינות מוכנה ✨" else "הקריינות נכשלה")
                        .setContentText(s.error ?: s.result?.let { "${it.voice} · ${"%.1f".format(it.seconds)} שניות. לחצו לשמיעה." } ?: "")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(Notify.openApp(this@NarrationService))
                        .build()
                    nm.notify(Notify.DONE_ID, done)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
