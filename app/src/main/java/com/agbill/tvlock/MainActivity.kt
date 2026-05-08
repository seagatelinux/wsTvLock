package com.agbill.tvlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val IMAGE_PICK_WELCOME = 2001
        private const val IMAGE_PICK_PAUSED = 2002
        private const val IMAGE_PICK_PAY_NOW = 2003
    }

    private lateinit var etServerIp: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var statusIndicator: View
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnSaveStart: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnStopService: Button
    private lateinit var ivWelcome: ImageView
    private lateinit var ivPaused: ImageView
    private lateinit var ivPayNow: ImageView

    private var currentPickTarget: String = ""

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WebSocketService.ACTION_CONNECTION_STATE) {
                val connected = intent.getBooleanExtra(WebSocketService.EXTRA_CONNECTED, false)
                runOnUiThread { updateStatusUI(connected) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isLauncherStart = intent.action == Intent.ACTION_MAIN &&
                              intent.hasCategory(Intent.CATEGORY_HOME)

        // === AUTO-START ON TV BOOT ===
        if (isLauncherStart) {
            if (SettingsManager.isConfigured(this) && !isServiceRunning()) {
                Log.d(TAG, "Boot detected — playing splash then starting service")
                // Play opening video first, then start service
                startActivity(Intent(this, SplashActivity::class.java))
                finish()
                return
            }
            if (isServiceRunning()) {
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)

        etServerIp = findViewById(R.id.et_server_ip)
        etDeviceId = findViewById(R.id.et_device_id)
        statusIndicator = findViewById(R.id.status_indicator)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        btnSaveStart = findViewById(R.id.btn_save_start)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnStopService = findViewById(R.id.btn_stop_service)
        ivWelcome = findViewById(R.id.iv_custom_welcome)
        ivPaused = findViewById(R.id.iv_custom_paused)
        ivPayNow = findViewById(R.id.iv_custom_pay_now)

        etServerIp.setText(SettingsManager.getServerIp(this))
        etDeviceId.setText(SettingsManager.getDeviceId(this))
        etDeviceId.isEnabled = false

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }

        loadCustomImagePreviews()

        btnSaveStart.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            val id = etDeviceId.text.toString().trim()

            if (ip.isBlank() || id.isBlank()) {
                Toast.makeText(this, "IP dan ID Perangkat harus diisi!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            SettingsManager.setServerIp(this, ip)
            SettingsManager.setDeviceId(this, id)
            Toast.makeText(this, R.string.msg_save_success, Toast.LENGTH_SHORT).show()

            WebSocketService.start(this)
            finish()
        }

        btnTestConnection.setOnClickListener {
            testConnection()
        }

        btnStopService.setOnClickListener {
            WebSocketService.stop(this)
            updateStatusUI(false)
        }

        // Image picker buttons
        findViewById<LinearLayout>(R.id.btn_pick_welcome).setOnClickListener {
            pickImage(IMAGE_PICK_WELCOME, "welcome.png")
        }
        findViewById<LinearLayout>(R.id.btn_pick_paused).setOnClickListener {
            pickImage(IMAGE_PICK_PAUSED, "paused.png")
        }
        findViewById<LinearLayout>(R.id.btn_pick_pay_now).setOnClickListener {
            pickImage(IMAGE_PICK_PAY_NOW, "pay_now.png")
        }

        // Reset buttons
        findViewById<Button>(R.id.btn_reset_welcome).setOnClickListener {
            SettingsManager.removeCustomImage(this, "welcome.png")
            loadCustomImagePreviews()
            Toast.makeText(this, "Gambar Welcome direset ke default", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_reset_paused).setOnClickListener {
            SettingsManager.removeCustomImage(this, "paused.png")
            loadCustomImagePreviews()
            Toast.makeText(this, "Gambar Paused direset ke default", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_reset_pay_now).setOnClickListener {
            SettingsManager.removeCustomImage(this, "pay_now.png")
            loadCustomImagePreviews()
            Toast.makeText(this, "Gambar Pay Now direset ke default", Toast.LENGTH_SHORT).show()
        }

        val filter = IntentFilter(WebSocketService.ACTION_CONNECTION_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(connectionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, filter)
        }

        updateStatusUI(WebSocketService.getConnectionState())

        // Schedule watchdog for service persistence
        ServiceWatchdogWorker.schedule(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
    }

    private fun loadCustomImagePreviews() {
        loadImagePreview(ivWelcome, "welcome.png")
        loadImagePreview(ivPaused, "paused.png")
        loadImagePreview(ivPayNow, "pay_now.png")
    }

    private fun loadImagePreview(iv: ImageView, imageType: String) {
        val path = SettingsManager.getCustomImagePath(this, imageType)
        if (path != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                iv.setImageBitmap(bitmap)
                return
            } catch (_: Exception) {}
        }
        // Load default from assets
        try {
            val inputStream = assets.open(imageType)
            val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
            iv.setImageDrawable(drawable)
            inputStream.close()
        } catch (_: Exception) {}
    }

    private fun pickImage(requestCode: Int, imageType: String) {
        currentPickTarget = imageType
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, requestCode)
    }

    private fun updateStatusUI(connected: Boolean?) {
        when (connected) {
            true -> {
                statusIndicator.setBackgroundResource(R.drawable.status_circle_green)
                tvConnectionStatus.text = getString(R.string.status_connected)
            }
            false -> {
                statusIndicator.setBackgroundResource(R.drawable.status_circle_red)
                tvConnectionStatus.text = getString(R.string.status_disconnected)
            }
            null -> {
                statusIndicator.setBackgroundResource(R.drawable.status_circle_yellow)
                tvConnectionStatus.text = getString(R.string.status_connecting)
            }
        }
    }

    private fun testConnection() {
        val ip = etServerIp.text.toString().trim()
        val id = etDeviceId.text.toString().trim()

        if (ip.isBlank() || id.isBlank()) {
            Toast.makeText(this, "Isi IP dan ID dulu!", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestConnection.isEnabled = false
        updateStatusUI(null)

        val url = "ws://$ip:5000/$id"
        val client = okhttp3.OkHttpClient.Builder()
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder().url(url).build()
        val testWs = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.msg_test_success, Toast.LENGTH_SHORT).show()
                    updateStatusUI(true)
                    btnTestConnection.isEnabled = true
                }
                webSocket.close(1000, "Test complete")
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.msg_test_fail, t.message),
                        Toast.LENGTH_LONG
                    ).show()
                    updateStatusUI(false)
                    btnTestConnection.isEnabled = true
                }
            }
        })

        Handler(Looper.getMainLooper()).postDelayed({
            if (btnTestConnection.isEnabled == false) {
                btnTestConnection.isEnabled = true
                updateStatusUI(false)
                Toast.makeText(this, getString(R.string.msg_test_fail, "Timeout"), Toast.LENGTH_SHORT).show()
            }
        }, 6000)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (WebSocketService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Izin overlay diperlukan untuk memblokir layar TV", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (resultCode == RESULT_OK && data?.data != null) {
            try {
                val inputStream = contentResolver.openInputStream(data.data!!)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val imageType = when (requestCode) {
                    IMAGE_PICK_WELCOME -> "welcome.png"
                    IMAGE_PICK_PAUSED -> "paused.png"
                    IMAGE_PICK_PAY_NOW -> "pay_now.png"
                    else -> return
                }

                if (SettingsManager.saveCustomImage(this, imageType, bitmap)) {
                    Toast.makeText(this, "Gambar $imageType berhasil disimpan", Toast.LENGTH_SHORT).show()
                    loadCustomImagePreviews()
                } else {
                    Toast.makeText(this, "Gagal menyimpan gambar", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load picked image: ${e.message}")
                Toast.makeText(this, "Gagal memuat gambar", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
