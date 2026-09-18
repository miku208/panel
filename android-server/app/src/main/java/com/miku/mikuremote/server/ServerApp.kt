package com.miku.mikuremote.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ServerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVER,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_desc) }
        )
    }

    companion object {
        const val CHANNEL_SERVER = "mikuremote_server"
    }
}
