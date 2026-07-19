package com.example.dexoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var clockTextView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        setupOverlayWindow()
    }

    private fun setupOverlayWindow() {
        windowManager = DeXDisplayHelper.getDeXWindowManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val position = prefs.getString(KEY_POSITION, POS_TOP_RIGHT) ?: POS_TOP_RIGHT

        val gravityCorner = if (position == POS_TOP_LEFT) {
            Gravity.TOP or Gravity.START
        } else {
            Gravity.TOP or Gravity.END
        }

        val windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityCorner
            x = 40 // Safe area margin from corner
            y = 40 // Safe area margin from corner
        }

        // Ultra-Minimal Container (100% Pure Transparent Background for XREAL Micro-OLED)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Cyberpunk 2077 Signature Yellow Time Only
        clockTextView = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.parseColor("#FFE600")) // Cyberpunk 2077 Yellow
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            // Cyberpunk text shadow glow effect
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#88FFE600"))
        }
        container.addView(clockTextView)

        overlayView = container
        windowManager.addView(overlayView, windowLayoutParams)
        handler.post(clockRunnable)
    }

    private fun updateClock() {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTime = timeFormat.format(Date())
        clockTextView?.text = currentTime
    }

    private fun startForegroundServiceNotification() {
        val channelId = "dex_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DeX Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cyberpunk 2077 HUD Active")
            .setContentText("Minimal Time Overlay on DeX / XREAL")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS_NAME = "dex_hud_prefs"
        const val KEY_POSITION = "hud_position"

        const val POS_TOP_RIGHT = "TOP_RIGHT"
        const val POS_TOP_LEFT = "TOP_LEFT"
    }
}
