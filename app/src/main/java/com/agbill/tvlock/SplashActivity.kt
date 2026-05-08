package com.agbill.tvlock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.VideoView

class SplashActivity : Activity() {

    companion object {
        private const val TAG = "SplashActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val videoView = findViewById<VideoView>(R.id.vv_splash)
        val videoUri = Uri.parse("android.resource://$packageName/raw/agbill_opening")
        Log.d(TAG, "Loading video: $videoUri")
        videoView.setVideoURI(videoUri)

        videoView.setOnCompletionListener {
            Log.d(TAG, "Video finished, proceeding to main")
            proceedToMain()
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Video error: what=$what extra=$extra, proceeding anyway")
            proceedToMain()
            true
        }

        videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoView.start()
        }
    }

    private fun proceedToMain() {
        if (SettingsManager.isConfigured(this)) {
            WebSocketService.start(this)
            // Give service time to show overlay before closing splash
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1500)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true
    override fun onBackPressed() {}
}
