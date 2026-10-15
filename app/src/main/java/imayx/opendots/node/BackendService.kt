package imayx.opendots.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import imayx.opendots.MainActivity
import imayx.opendots.R

/**
 * Optional foreground service that keeps the app process (and therefore the
 * embedded Node backend + its task scheduler) alive while the user is away.
 *
 * Off by default. The user enables it in Settings ("Background tasks").
 * This is the only justified foreground-service use: scheduled agent tasks.
 * Without it, the backend stops when Android reclaims the process, and the
 * Settings screen says so honestly.
 */
class BackendService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        NodeManager.get(this).ensureRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Backend service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the OpenDots backend running for scheduled tasks" },
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenDots backend running")
            .setContentText("Scheduled tasks and the local API stay available.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "opendots_backend"
        const val NOTIFICATION_ID = 4310

        fun start(context: Context) {
            val intent = Intent(context, BackendService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            context.getSharedPreferences("opendots_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("background_mode", true).apply()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackendService::class.java))
            context.getSharedPreferences("opendots_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("background_mode", false).apply()
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("opendots_prefs", Context.MODE_PRIVATE)
                .getBoolean("background_mode", false)
    }
}
