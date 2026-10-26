package com.yara.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Keeps Yara's process alive so she can finish answers (and speak them) while you are in other apps. */
class YaraForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("yara", "Yara background", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "yara")
            .setContentTitle("Yara is ready")
            .setContentText("Background mode is on")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        startForeground(1001, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
