package com.example.dexoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var mapView: MapView? = null
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
        // Retrieve display-bound WindowManager (targets DeX / XREAL secondary display)
        windowManager = DeXDisplayHelper.getDeXWindowManager(this)

        // Window Layout Parameters for Overlay
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
            gravity = Gravity.TOP or Gravity.END
            x = 40 // Safe area margin from right
            y = 40 // Safe area margin from top
        }

        // Programmatically build overlay view (Clock + Semi-Transparent Map)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            // Semi-transparent glassmorphism background (#99000000 = 60% transparent black)
            setBackgroundColor(Color.parseColor("#99000000"))
        }

        // 1. Digital Clock TextView
        clockTextView = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.parseColor("#00E5FF")) // Cyan highlights for XREAL micro-OLED
            textStyleBold()
            setPadding(0, 0, 0, 12)
        }
        container.addView(clockTextView)

        // 2. Semi-Transparent Google Maps View
        try {
            MapsInitializer.initialize(this)
            val mapLayoutParams = LinearLayout.LayoutParams(400, 300)
            mapView = MapView(this).apply {
                this.layoutParams = mapLayoutParams
                alpha = 0.70f // 70% opacity semi-transparent overlay
                onCreate(null)
                onResume()
                getMapAsync { googleMap ->
                    val defaultLocation = LatLng(37.7749, -122.4194)
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 13f))
                    googleMap.uiSettings.isMapToolbarEnabled = false
                }
            }
            container.addView(mapView)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        overlayView = container
        windowManager.addView(overlayView, windowLayoutParams)

        // Start Clock Update loop
        handler.post(clockRunnable)
    }

    private fun updateClock() {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTime = timeFormat.format(Date())
        clockTextView?.text = "🕒 $currentTime"
    }

    private fun TextView.textStyleBold() {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
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
            .setContentTitle("DeX XREAL Overlay Running")
            .setContentText("Floating clock & map widget active on DeX screen")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        mapView?.onDestroy()
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
