package com.example.streaming

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
import com.example.MainActivity
import com.example.R

class LiveBroadcastService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Tech Live Broadcast"
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "CAMERA"
                ensureNotificationChannel()
                val notification = buildNotification(title, mode)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fgsType = if (mode == "SCREEN") {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tech Live Broadcast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps low-latency camera and screen streaming active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, mode: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LIVE • $title")
            .setContentText("Broadcasting ($mode) • Low-latency encoder active")
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tech_live_broadcast_channel"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.example.action.START_BROADCAST"
        const val ACTION_STOP = "com.example.action.STOP_BROADCAST"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MODE = "extra_mode"

        fun start(context: Context, title: String, mode: String) {
            val intent = Intent(context, LiveBroadcastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MODE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveBroadcastService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
