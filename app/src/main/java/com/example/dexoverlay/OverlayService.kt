package com.example.dexoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.BatteryManager
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
    private var batteryTextView: TextView? = null
    private var navCardView: LinearLayout? = null
    private var navTitleTextView: TextView? = null
    private var navSubTextView: TextView? = null

    private var isNavActive = false
    private var mapMode = MAP_MODE_AUTO_NAV // Default: Auto-show when nav active

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    // Receiver for Phone Battery Status
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                if (level >= 0 && scale > 0) {
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()
                    val icon = if (isCharging) "⚡" else "🔋"
                    batteryTextView?.text = "$icon $batteryPct%"
                    batteryTextView?.setTextColor(
                        when {
                            isCharging -> Color.parseColor("#00FF66") // Neon Green charging
                            batteryPct <= 20 -> Color.parseColor("#FF3366") // Neon Red low
                            else -> Color.parseColor("#FFE600") // Cyberpunk Yellow
                        }
                    )
                }
            }
        }
    }

    // Receiver for Google Maps Turn-by-Turn Navigation Updates
    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MapsNavListenerService.ACTION_NAV_UPDATE) {
                val active = intent.getBooleanExtra(MapsNavListenerService.EXTRA_IS_NAV_ACTIVE, false)
                val title = intent.getStringExtra(MapsNavListenerService.EXTRA_NAV_TITLE) ?: ""
                val text = intent.getStringExtra(MapsNavListenerService.EXTRA_NAV_TEXT) ?: ""

                isNavActive = active
                if (active && (title.isNotEmpty() || text.isNotEmpty())) {
                    navTitleTextView?.text = "🪧 $title"
                    navSubTextView?.text = text
                    updateMapAndNavVisibility()
                } else {
                    isNavActive = false
                    updateMapAndNavVisibility()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        registerReceivers()
        setupOverlayWindow()
    }

    private fun registerReceivers() {
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(navReceiver, IntentFilter(MapsNavListenerService.ACTION_NAV_UPDATE))
    }

    private fun setupOverlayWindow() {
        windowManager = DeXDisplayHelper.getDeXWindowManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val position = prefs.getString(KEY_POSITION, POS_TOP_RIGHT) ?: POS_TOP_RIGHT
        mapMode = prefs.getString(KEY_MAP_MODE, MAP_MODE_AUTO_NAV) ?: MAP_MODE_AUTO_NAV

        // Determine Gravity based on User Position Preference
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
            x = 40 // Safe area margin
            y = 40 // Safe area margin
        }

        // Cyberpunk Minimal Root Container (Transparent Background for XREAL Micro-OLED)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            // Transparent background for true-black AR projection
            setBackgroundColor(Color.TRANSPARENT)
        }

        // --- Cyberpunk HUD Header Row (Clock + Battery) ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            // Subtle Cyberpunk semi-transparent pill container (#80000000 = 50% dark)
            setBackgroundColor(Color.parseColor("#99000000"))
        }

        // 1. Digital Clock TextView (Cyberpunk Cyan #00E5FF)
        clockTextView = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        headerRow.addView(clockTextView)

        // Divider Space
        val divider = TextView(this).apply {
            text = "  │  "
            textSize = 14f
            setTextColor(Color.parseColor("#445566"))
        }
        headerRow.addView(divider)

        // 2. Battery Indicator TextView (Cyberpunk Yellow #FFE600)
        batteryTextView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            text = "🔋 --%"
        }
        headerRow.addView(batteryTextView)

        container.addView(headerRow)

        // --- 3. Google Maps Turn-by-Turn Navigation Pill (Neon Pink Accent #FF0055) ---
        navCardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.parseColor("#CC110022")) // Dark Cyberpunk Violet background
            visibility = View.GONE
        }

        navTitleTextView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FF0055")) // Neon Pink Accent
            setTypeface(typeface, Typeface.BOLD)
        }
        navCardView?.addView(navTitleTextView)

        navSubTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        navCardView?.addView(navSubTextView)

        container.addView(navCardView)

        // --- 4. Google Maps View Widget ---
        try {
            MapsInitializer.initialize(this)
            val mapLayoutParams = LinearLayout.LayoutParams(360, 240).apply {
                topMargin = 8
            }
            mapView = MapView(this).apply {
                this.layoutParams = mapLayoutParams
                alpha = 0.70f // 70% opacity semi-transparent overlay
                visibility = View.GONE
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

        updateMapAndNavVisibility()
        handler.post(clockRunnable)
    }

    private fun updateMapAndNavVisibility() {
        val showNav = isNavActive && (mapMode == MAP_MODE_AUTO_NAV || mapMode == MAP_MODE_ALWAYS_ON)
        val showMap = mapMode == MAP_MODE_ALWAYS_ON || (mapMode == MAP_MODE_AUTO_NAV && isNavActive)

        navCardView?.visibility = if (showNav) View.VISIBLE else View.GONE
        mapView?.visibility = if (showMap) View.VISIBLE else View.GONE
    }

    private fun updateClock() {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTime = timeFormat.format(Date())
        clockTextView?.text = "🕒 $currentTime"
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
            .setContentTitle("Cyberpunk HUD Running")
            .setContentText("Minimal Cyberpunk HUD & Nav active on DeX")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(navReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mapView?.onDestroy()
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS_NAME = "dex_hud_prefs"
        const val KEY_POSITION = "hud_position"
        const val KEY_MAP_MODE = "map_mode"

        const val POS_TOP_RIGHT = "TOP_RIGHT"
        const val POS_TOP_LEFT = "TOP_LEFT"

        const val MAP_MODE_ALWAYS_ON = "ALWAYS_ON"
        const val MAP_MODE_AUTO_NAV = "AUTO_NAV"
        const val MAP_MODE_OFF = "OFF"
    }
}
