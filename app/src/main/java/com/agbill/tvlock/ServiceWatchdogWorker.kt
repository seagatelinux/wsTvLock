package com.agbill.tvlock

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class ServiceWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val WORK_NAME = "agbill_tv_watchdog"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            Log.d(TAG, "Watchdog scheduled every 15 minutes")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override fun doWork(): Result {
        val context = applicationContext

        // Check if service is running
        val isRunning = isServiceRunning(context)
        Log.d(TAG, "Watchdog check — service running: $isRunning")

        if (!isRunning && SettingsManager.isConfigured(context)) {
            Log.d(TAG, "Service not running, restarting...")
            try {
                WebSocketService.start(context)
                Log.d(TAG, "Service restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service: ${e.message}")
            }
        }

        return Result.success()
    }

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (WebSocketService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}