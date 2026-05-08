package com.agbill.tvlock

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object SettingsManager {

    private const val TAG = "SettingsManager"
    private const val PREFS_NAME = "agbill_tv_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CUSTOM_WELCOME = "custom_welcome"
    private const val KEY_CUSTOM_PAUSED = "custom_paused"
    private const val KEY_CUSTOM_PAY_NOW = "custom_pay_now"
    private const val KEY_TIMER_END_TIME = "timer_end_time"
    private const val KEY_SPLASH_ACTIVE = "splash_active"
    private const val DEFAULT_SERVER_IP = "10.10.10.1"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getServerIp(context: Context): String {
        return prefs(context).getString(KEY_SERVER_IP, DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
    }

    fun setServerIp(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER_IP, value).apply()
    }

    fun getDeviceId(context: Context): String {
        val saved = prefs(context).getString(KEY_DEVICE_ID, null)
        if (saved != null) return saved
        val generated = generateDeviceId(context)
        prefs(context).edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun setDeviceId(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEVICE_ID, value).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getServerIp(context).isNotBlank() && getDeviceId(context).isNotBlank()
    }

    fun getWebSocketUrl(context: Context): String {
        return "ws://${getServerIp(context)}:5000/${getDeviceId(context)}"
    }

    fun hasCustomImage(context: Context, imageType: String): Boolean {
        val key = when (imageType) {
            "welcome.png" -> KEY_CUSTOM_WELCOME
            "paused.png" -> KEY_CUSTOM_PAUSED
            "pay_now.png" -> KEY_CUSTOM_PAY_NOW
            else -> return false
        }
        return prefs(context).getBoolean(key, false)
    }

    fun saveCustomImage(context: Context, imageType: String, bitmap: Bitmap): Boolean {
        val key = when (imageType) {
            "welcome.png" -> KEY_CUSTOM_WELCOME
            "paused.png" -> KEY_CUSTOM_PAUSED
            "pay_now.png" -> KEY_CUSTOM_PAY_NOW
            else -> return false
        }
        val fileName = "custom_$imageType"
        try {
            val file = File(context.filesDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            prefs(context).edit().putBoolean(key, true).apply()
            Log.d(TAG, "Saved custom image: $fileName (${file.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom image: ${e.message}")
            return false
        }
    }

    fun removeCustomImage(context: Context, imageType: String) {
        val key = when (imageType) {
            "welcome.png" -> KEY_CUSTOM_WELCOME
            "paused.png" -> KEY_CUSTOM_PAUSED
            "pay_now.png" -> KEY_CUSTOM_PAY_NOW
            else -> return
        }
        val file = File(context.filesDir, "custom_$imageType")
        if (file.exists()) file.delete()
        prefs(context).edit().putBoolean(key, false).apply()
        Log.d(TAG, "Removed custom image: custom_$imageType")
    }

    fun getCustomImagePath(context: Context, imageType: String): String? {
        if (!hasCustomImage(context, imageType)) return null
        val file = File(context.filesDir, "custom_$imageType")
        return if (file.exists()) file.absolutePath else null
    }

    // Timer persistence — survives service restart
    fun saveTimerState(context: Context, endTimeMs: Long) {
        prefs(context).edit().putLong(KEY_TIMER_END_TIME, endTimeMs).apply()
    }

    fun getTimerState(context: Context): Long {
        return prefs(context).getLong(KEY_TIMER_END_TIME, 0L)
    }

    fun clearTimerState(context: Context) {
        prefs(context).edit().putLong(KEY_TIMER_END_TIME, 0L).apply()
    }

    private fun generateDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(androidId.toByteArray())
        val hex = hashBytes.joinToString("") { "%02X".format(it) }
        val shortHash = hex.substring(0, 6)
        return "tv$shortHash"
    }
}
