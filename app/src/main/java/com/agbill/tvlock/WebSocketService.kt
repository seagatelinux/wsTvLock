package com.agbill.tvlock

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketService : android.app.Service() {

    companion object {
        private const val TAG = "WebSocketService"
        const val ACTION_ON = "com.agbill.tvlock.ACTION_ON"
        const val ACTION_OFF = "com.agbill.tvlock.ACTION_OFF"
        const val ACTION_BLOCK = "com.agbill.tvlock.ACTION_BLOCK"
        const val EXTRA_IMAGE = "overlay_image"
        const val EXTRA_CHANNEL = "channelNumber"
        const val EXTRA_CONNECTED = "connected"
        const val ACTION_CONNECTION_STATE = "com.agbill.tvlock.ACTION_CONNECTION_STATE"
        private const val NOTIFICATION_ID = 1001

        private var isConnected = false

        fun start(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Fallback for boot start where FGS might be restricted
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebSocketService::class.java))
        }

        fun getConnectionState(): Boolean = isConnected
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectDelay = 1000L
    private val maxReconnectDelay = 10000L
    private val gson = Gson()

    // Internal timer like ESP
    private var timerRunnable: Runnable? = null
    private var remainingSeconds = 0L
    private var timerEndTime = 0L
    // Auto-switch timer (pay_now → welcome after 60s)
    private var autoSwitchRunnable: Runnable? = null

    // Internal 30-second keepalive: reconnects if WebSocket drops but service is still alive
    private val keepaliveHandler = Handler(Looper.getMainLooper())
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (!isConnected && SettingsManager.isConfigured(this@WebSocketService)) {
                Log.d(TAG, "Keepalive check: disconnected, reconnecting...")
                connect()
            }
            keepaliveHandler.postDelayed(this, 30000)
        }
    }

    private val reconnectRunnable = Runnable {
        if (!isConnected && SettingsManager.isConfigured(this)) {
            Log.d(TAG, "Reconnecting... (delay=${reconnectDelay}ms)")
            connect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called, isConfigured=${SettingsManager.isConfigured(this)}")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(false), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(false))
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on boot — start as regular service
            Log.w(TAG, "Cannot start foreground, using background service: ${e.message}")
        }
        Log.d(TAG, "Service foreground started")

        // Show welcome.png on first start (like ESP starts with relay OFF)
        showOverlay("welcome.png")
        restoreTimerIfActive()

        // Start 30-second keepalive loop
        keepaliveHandler.postDelayed(keepaliveRunnable, 30000)

        if (SettingsManager.isConfigured(this)) {
            Log.d(TAG, "Calling connect() from onCreate")
            connect()
        } else {
            Log.w(TAG, "Service not configured, skipping connect")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reconnectRunnable)
        keepaliveHandler.removeCallbacks(keepaliveRunnable)
        cancelTimer()
        autoSwitchRunnable?.let { handler.removeCallbacks(it) }
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
        isConnected = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, isConfigured=${SettingsManager.isConfigured(this)}, isConnected=$isConnected")
        if (SettingsManager.isConfigured(this) && !isConnected) {
            Log.d(TAG, "onStartCommand: calling connect()")
            connect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun connect() {
        val url = SettingsManager.getWebSocketUrl(this)
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                reconnectDelay = 1000L
                broadcastConnectionState(true)
                updateNotification(true)

                val deviceId = SettingsManager.getDeviceId(this@WebSocketService)
                val handshake = """{"$deviceId":"0","stationSN":"$deviceId","message":"TV client connected!"}"""
                webSocket.send(handshake)
                Log.d(TAG, "Sent handshake: $handshake")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                if (text.equals("ping", ignoreCase = true)) {
                    webSocket.send("pong")
                    return
                }
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.send(ByteString.EMPTY)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        if (!isConnected) {
            scheduleReconnect()
            return
        }
        isConnected = false
        broadcastConnectionState(false)
        updateNotification(false)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelay)
    }

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, Map::class.java) as? Map<*, *> ?: return
            val command = json["command"]?.toString() ?: return

            // BLOCK command — show overlay with specified image (server explicit state)
            if (command == "BLOCK") {
                val image = json["image"]?.toString() ?: "welcome.png"
                Log.d(TAG, "BLOCK with image=$image")
                cancelTimer()
                showOverlay(image)
                return
            }

            // ON command — switch to HDMI, start internal timer
            if (command.startsWith("ON")) {
                val channelNumber = command.removePrefix("ON").toIntOrNull() ?: 1
                val duration = when (val d = json["duration"]) {
                    is Number -> d.toLong()
                    is String -> d.toLongOrNull() ?: 0L
                    else -> 0L
                }
                Log.d(TAG, "ON command, channel=$channelNumber, duration=$duration")

                cancelTimer()
                dismissOverlay()

                // Start internal timer if duration > 0 (like ESP relay timer)
                if (duration > 0) {
                    startTimer(duration)
                }
                return
            }

            // OFF command — ignored, server sends explicit BLOCK commands
            if (command.startsWith("OFF")) {
                Log.d(TAG, "OFF command received — ignored (state managed by BLOCK)")
                return
            }

            Log.d(TAG, "Unhandled command: $command")
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing message: ${e.message}")
        }
    }

    // ===== Internal Timer (like ESP) =====

    private fun startTimer(durationSeconds: Long) {
        remainingSeconds = durationSeconds
        timerEndTime = System.currentTimeMillis() + (durationSeconds * 1000)
        SettingsManager.saveTimerState(this, timerEndTime)
        Log.d(TAG, "Timer started: ${durationSeconds}s (endTime=$timerEndTime)")

        cancelTimer()
        timerRunnable = object : Runnable {
            override fun run() {
                remainingSeconds = ((timerEndTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                if (remainingSeconds <= 0) {
                    Log.d(TAG, "Timer expired — showing pay_now.png")
                    SettingsManager.clearTimerState(this@WebSocketService)
                    showOverlay("pay_now.png")
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(timerRunnable!!, 1000)
    }

    private fun cancelTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        remainingSeconds = 0
    }

    private fun restoreTimerIfActive() {
        val savedEndTime = SettingsManager.getTimerState(this)
        if (savedEndTime > System.currentTimeMillis()) {
            val remaining = (savedEndTime - System.currentTimeMillis()) / 1000
            Log.d(TAG, "Restoring timer: ${remaining}s remaining")
            startTimer(remaining)
        } else if (savedEndTime > 0) {
            // Timer already expired — lock screen
            Log.d(TAG, "Saved timer expired — showing pay_now.png")
            SettingsManager.clearTimerState(this)
            showOverlay("pay_now.png")
        }
    }

    // ===== Overlay Control =====

    private fun showOverlay(image: String) {
        // Cancel any pending auto-switch
        autoSwitchRunnable?.let { handler.removeCallbacks(it) }
        autoSwitchRunnable = null

        val overlayIntent = Intent(this, OverlayActivity::class.java)
        overlayIntent.putExtra(OverlayActivity.EXTRA_IMAGE, image)
        overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(overlayIntent)

        // Auto-switch: pay_now → welcome after 60s
        if (image == "pay_now.png") {
            autoSwitchRunnable = Runnable {
                Log.d(TAG, "Auto-switch pay_now → welcome after 60s")
                showOverlay("welcome.png")
            }
            handler.postDelayed(autoSwitchRunnable!!, 60000)
        }
    }

    private fun dismissOverlay() {
        val intent = Intent(ACTION_ON)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun broadcastConnectionState(connected: Boolean) {
        val intent = Intent(ACTION_CONNECTION_STATE)
        intent.putExtra(EXTRA_CONNECTED, connected)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildNotification(connected: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(if (connected) R.string.notification_connected else R.string.notification_disconnected))
            .setSmallIcon(R.drawable.ic_tv_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(connected: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(connected))
    }
}
