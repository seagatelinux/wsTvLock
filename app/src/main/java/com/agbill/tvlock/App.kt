package com.agbill.tvlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // WorkManager watchdog will be scheduled from MainActivity after user opens app
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AGBill TV WebSocket service status"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "agbill_tv"
    }
}