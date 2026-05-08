package com.agbill.tvlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "BootReceiver triggered: ${intent.action}")
        SettingsManager.getDeviceId(context)

        if (!SettingsManager.isConfigured(context)) return

        // Launch splash video activity on boot — video plays, then service starts
        val splashIntent = Intent(context, SplashActivity::class.java)
        splashIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        splashIntent.putExtra("from_boot", true)
        context.startActivity(splashIntent)
        Log.d(TAG, "Launched SplashActivity from ${intent.action}")

        ServiceWatchdogWorker.schedule(context)
    }
}