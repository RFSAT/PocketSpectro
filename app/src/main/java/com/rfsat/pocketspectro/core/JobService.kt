package com.rfsat.pocketspectro.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.SpectroApp
import com.rfsat.pocketspectro.ui.MainActivity
import com.rfsat.pocketspectro.ui.background
import com.rfsat.pocketspectro.ui.jobsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps loggers, recorders and alarms running with the screen off: a foreground service with an
 * ongoing notification listing what runs (with Stop all), and a partial wake lock so the CPU keeps
 * reading the spectrometer. It stops by itself when nothing runs in the background any more.
 */
class JobService : Service() {

    companion object {
        private const val CHANNEL = "jobs"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP_ALL = "com.rfsat.pocketspectro.action.STOP_ALL"
        private const val WAKE_MS = 10 * 60_000L

        @Volatile var running = false
            private set

        /**
         * Called from the app while it is in front (Android allows starting foreground services only then);
         * only with a real spectrometer connected.
         */
        fun start(context: Context, engine: AcquisitionEngine) {
            if (running || engine.simulator != null || engine.connection.value !is Connection.Ready) return
            try { ContextCompat.startForegroundService(context, Intent(context, JobService::class.java)) } catch (_: Exception) {}
        }

        fun stop(context: Context) { try { context.stopService(Intent(context, JobService::class.java)) } catch (_: Exception) {} }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private val engine get() = (application as SpectroApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.notif_channel_help); setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) engine.stopAll()
        // Only for a real USB spectrometer (the simulator's demo loggers run while the app is open).
        // connectedDevice has no time limit; location is added for GPS-tagged surveys when access is granted.
        // (Constants of API 29 are inlined; ServiceCompat ignores the type on older Android.)
        if (engine.simulator != null) { stopSelf(); return START_NOT_STICKY }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketSpectro:jobs").apply { setReferenceCounted(false) }
        if (scope.coroutineContext[kotlinx.coroutines.Job]?.children?.any() != true) scope.launch {
            while (isActive) {
                if (!jobsSnapshot(this@JobService, engine).background()) { stopSelf(); break }
                wake?.acquire(WAKE_MS)   // renewed every few seconds while jobs run; lapses by itself if the app dies
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                delay(5_000)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val jobs = jobsSnapshot(this, engine).filter { it.running }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, JobService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lines = jobs.map { "${it.shortName}: ${it.status}" }
        val style = NotificationCompat.InboxStyle().also { st -> lines.forEach { st.addLine(it) } }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spectro)
            .setContentTitle(getString(R.string.notif_title, jobs.size))
            .setContentText(lines.joinToString(" · ").ifEmpty { getString(R.string.job_idle) })
            .setStyle(style)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.jobs_stop_all), stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
