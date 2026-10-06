package com.mtgofa.carinfo.obd

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
import com.mtgofa.carinfo.MainActivity
import com.mtgofa.carinfo.R

/**
 * Keeps the process (and so the Bluetooth link and the recorder) alive while a trip is being
 * recorded, even with the screen off or another app in front. The recording itself lives in
 * [TripRecorder]; this only holds the foreground notification.
 */
class TripService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TripRecorder.stop(this)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Trip recording", NotificationManager.IMPORTANCE_LOW))
        }
        val n = build(this, "Starting…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // Without the foreground slot the recording still runs while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val ID = 42
        private const val ACTION_STOP = "com.mtgofa.carinfo.STOP_TRIP"

        /** Ongoing notification: live time / distance / problems and a Stop button. */
        fun build(context: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, TripService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
            @Suppress("DEPRECATION")
            return builder
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Recording trip")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Stop recording", stop).build())
                .build()
        }

        fun cancel(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
        }

        /** Refresh the text; cheap, and silent thanks to the low-importance channel. */
        fun update(context: Context, text: String) {
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, text)) }
        }
    }
}
