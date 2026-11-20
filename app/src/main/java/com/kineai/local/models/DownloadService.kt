package com.kineai.local.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the process alive while a model downloads; stops itself when none is active. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = intent?.getIntExtra("active", 0) ?: 0
        if (active > 0) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("downloads", "Model downloads", NotificationManager.IMPORTANCE_LOW))
            val n = NotificationCompat.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("KineAi").setContentText("Downloading $active model file(s) from Hugging Face").setOngoing(true).build()
            startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
