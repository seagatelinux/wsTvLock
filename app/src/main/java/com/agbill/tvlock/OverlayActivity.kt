package com.agbill.tvlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView

class OverlayActivity : Activity() {

    companion object {
        private const val TAG = "OverlayActivity"
        const val EXTRA_IMAGE = "overlay_image"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WebSocketService.ACTION_ON -> {
                    Log.d(TAG, "ON received — dismissing overlay")
                    finish()
                }
                WebSocketService.ACTION_BLOCK -> {
                    val image = intent.getStringExtra(WebSocketService.EXTRA_IMAGE) ?: "welcome.png"
                    Log.d(TAG, "BLOCK received — switching to $image")
                    loadOverlayImage(image)
                }
            }
        }
    }

    private var ivImage: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val image = intent.getStringExtra(EXTRA_IMAGE) ?: "welcome.png"

        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        setContentView(R.layout.activity_overlay)

        ivImage = findViewById(R.id.iv_overlay)
        loadOverlayImage(image)

        val filter = IntentFilter().apply {
            addAction(WebSocketService.ACTION_ON)
            addAction(WebSocketService.ACTION_BLOCK)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun loadOverlayImage(imageName: String) {
        val iv = ivImage ?: return

        // Try custom image from internal storage first
        val customFile = java.io.File(filesDir, "custom_$imageName")
        if (customFile.exists()) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(customFile.absolutePath)
                iv.setImageBitmap(bitmap)
                Log.d(TAG, "Loaded custom image: ${customFile.name}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load custom image: ${e.message}")
            }
        }

        // Fallback to assets
        try {
            val inputStream = assets.open(imageName)
            val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
            iv.setImageDrawable(drawable)
            inputStream.close()
            Log.d(TAG, "Loaded asset image: $imageName")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $imageName from assets: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val image = intent.getStringExtra(EXTRA_IMAGE) ?: return
        Log.d(TAG, "onNewIntent — switching to $image")
        loadOverlayImage(image)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = true
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = true
    override fun onBackPressed() {}
}
