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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var clockTextView: TextView? = null
    private var batteryTextView: TextView? = null
    private var navTraceTextView: TextView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var cursorView: TextView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorX = 960f
    private var cursorY = 540f

    private var tcpImuDriver: XrealOneTcpImuDriver? = null
    private var udpImuReceiver: UdpImuReceiver? = null
    private var isNavActive = false
    private var isHudVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    // Battery Monitor Receiver
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
                    val segments = (batteryPct / 20).coerceIn(0, 5)

                    val bars = StringBuilder()
                    for (i in 1..5) {
                        if (i <= segments) bars.append("▮") else bars.append("▯")
                    }

                    val prefix = if (isCharging) "⚡" else ""
                    batteryTextView?.text = "$prefix $bars"
                    batteryTextView?.setTextColor(
                        when {
                            isCharging -> Color.parseColor("#00FF66")
                            batteryPct <= 20 -> Color.parseColor("#FF0055")
                            else -> Color.parseColor("#00E5FF")
                        }
                    )
                }
            }
        }
    }

    // Position Update Receiver
    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_POSITION) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hudScale = prefs.getFloat(KEY_SCALE, 1.0f).coerceIn(0.25f, 1.5f)
                val xOffset = prefs.getInt(KEY_X_OFFSET, 40)
                val yOffset = prefs.getInt(KEY_Y_OFFSET, 40)

                windowLayoutParams?.let { params ->
                    params.x = (xOffset * hudScale).toInt()
                    params.y = (yOffset * hudScale).toInt()
                    overlayView?.let { view ->
                        windowManager.updateViewLayout(view, params)
                    }
                }
            }
        }
    }

    // Navigation Listener Receiver
    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MapsNavListenerService.ACTION_NAV_UPDATE) {
                val active = intent.getBooleanExtra(MapsNavListenerService.EXTRA_IS_NAV_ACTIVE, false)
                val arrow = intent.getStringExtra(MapsNavListenerService.EXTRA_NAV_ARROW) ?: "🠹"
                val title = intent.getStringExtra(MapsNavListenerService.EXTRA_NAV_TITLE) ?: ""
                val text = intent.getStringExtra(MapsNavListenerService.EXTRA_NAV_TEXT) ?: ""

                isNavActive = active
                if (active && (title.isNotEmpty() || text.isNotEmpty())) {
                    navTraceTextView?.text = "$arrow $title • $text"
                    navTraceTextView?.visibility = View.VISIBLE
                } else {
                    navTraceTextView?.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
            registerReceiver(navReceiver, IntentFilter(MapsNavListenerService.ACTION_NAV_UPDATE), RECEIVER_NOT_EXPORTED)
            registerReceiver(positionReceiver, IntentFilter(ACTION_UPDATE_POSITION), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(navReceiver, IntentFilter(MapsNavListenerService.ACTION_NAV_UPDATE))
            registerReceiver(positionReceiver, IntentFilter(ACTION_UPDATE_POSITION))
        }

        setupOverlayWindow()
        setupHeadTrackedCursor()
        initImuDrivers()
    }

    private fun setupOverlayWindow() {
        windowManager = DeXDisplayHelper.getDeXWindowManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val position = prefs.getString(KEY_POSITION, POS_TOP_RIGHT) ?: POS_TOP_RIGHT
        val hudScale = prefs.getFloat(KEY_SCALE, 1.0f).coerceIn(0.25f, 1.5f)
        
        val xOffset = prefs.getInt(KEY_X_OFFSET, 40)
        val yOffset = prefs.getInt(KEY_Y_OFFSET, 40)

        val gravityCorner = if (position == POS_TOP_LEFT) {
            Gravity.TOP or Gravity.START
        } else {
            Gravity.TOP or Gravity.END
        }

        val params = WindowManager.LayoutParams(
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
            x = (xOffset * hudScale).toInt()
            y = (yOffset * hudScale).toInt()
        }
        windowLayoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (position == POS_TOP_LEFT) Gravity.START else Gravity.END
            setPadding((8 * hudScale).toInt(), (4 * hudScale).toInt(), (8 * hudScale).toInt(), (4 * hudScale).toInt())
            setBackgroundColor(Color.TRANSPARENT)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        clockTextView = TextView(this).apply {
            textSize = 24f * hudScale
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(8f * hudScale, 0f, 0f, Color.parseColor("#88FFE600"))
        }
        headerRow.addView(clockTextView)

        val spacer = TextView(this).apply { text = "   " }
        headerRow.addView(spacer)

        batteryTextView = TextView(this).apply {
            textSize = 12f * hudScale
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            text = "▮▮▮▮▮"
        }
        headerRow.addView(batteryTextView)

        container.addView(headerRow)

        navTraceTextView = TextView(this).apply {
            textSize = 14f * hudScale
            setTextColor(Color.parseColor("#FF0055"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(6f * hudScale, 0f, 0f, Color.parseColor("#CCFF0055"))
            visibility = View.GONE
            setPadding(0, (6 * hudScale).toInt(), 0, 0)
        }
        container.addView(navTraceTextView)

        overlayView = container
        windowManager.addView(overlayView, windowLayoutParams)
        handler.post(clockRunnable)
    }

    // Glowing Cyberpunk Head-Tracked Cursor Reticle
    private fun setupHeadTrackedCursor() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enableHeadCursor = prefs.getBoolean(KEY_ENABLE_HEAD_CURSOR, true)
        if (!enableHeadCursor) return

        cursorView = TextView(this).apply {
            text = "✛"
            textSize = 28f
            setTextColor(Color.parseColor("#00E5FF"))
            setShadowLayer(10f, 0f, 0f, Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }
        cursorParams = params

        try {
            windowManager.addView(cursorView, cursorParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initImuDrivers() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enableHeadCursor = prefs.getBoolean(KEY_ENABLE_HEAD_CURSOR, true)
        val singleTapAction = prefs.getString(KEY_SINGLE_TAP_ACTION, SINGLE_TAP_ACTION_CLICK) ?: SINGLE_TAP_ACTION_CLICK
        if (!enableHeadCursor) return

        fun onHeadMove(deltaX: Float, deltaY: Float) {
            cursorX = (cursorX + deltaX * 15f).coerceIn(0f, 1920f)
            cursorY = (cursorY + deltaY * 15f).coerceIn(0f, 1080f)

            cursorParams?.let { params ->
                params.x = cursorX.toInt()
                params.y = cursorY.toInt()
                handler.post {
                    try {
                        cursorView?.let { v -> windowManager.updateViewLayout(v, params) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        fun onSingleTap() {
            handler.post {
                cursorView?.setTextColor(Color.parseColor("#FFE600"))
                handler.postDelayed({ cursorView?.setTextColor(Color.parseColor("#00E5FF")) }, 200)

                when (singleTapAction) {
                    SINGLE_TAP_ACTION_TOGGLE_HUD -> {
                        isHudVisible = !isHudVisible
                        overlayView?.visibility = if (isHudVisible) View.VISIBLE else View.GONE
                    }
                    SINGLE_TAP_ACTION_RECENTER -> {
                        cursorX = 960f
                        cursorY = 540f
                    }
                    else -> {
                        val clickIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_CLICK).apply {
                            putExtra(HeadCursorAccessibilityService.EXTRA_X, cursorX)
                            putExtra(HeadCursorAccessibilityService.EXTRA_Y, cursorY)
                        }
                        sendBroadcast(clickIntent)
                    }
                }
            }
        }

        // 1. Direct XREAL 1s TCP Service Driver (Port 52998 on USB-Ethernet 169.254.2.1)
        tcpImuDriver = XrealOneTcpImuDriver().apply {
            onHeadMoveListener = { dx, dy -> onHeadMove(dx, dy) }
            startListening()
        }

        // 2. Ethernet / UDP Network Socket Receiver (Port 9090)
        udpImuReceiver = UdpImuReceiver(9090).apply {
            onHeadMoveListener = { dx, dy -> onHeadMove(dx, dy) }
            onGlassesSingleTapListener = { onSingleTap() }
            startListening()
        }
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
            .setContentTitle("Cyberpunk HUD & XREAL 1s Active")
            .setContentText("XREAL 1s TCP Service (52998) & Socket Active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        tcpImuDriver?.stopListening()
        udpImuReceiver?.stopListening()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(navReceiver)
            unregisterReceiver(positionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
        if (cursorView != null) {
            try { windowManager.removeView(cursorView) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS_NAME = "dex_hud_prefs"
        const val KEY_POSITION = "hud_position"
        const val KEY_SCALE = "hud_scale"
        const val KEY_X_OFFSET = "hud_x_offset"
        const val KEY_Y_OFFSET = "hud_y_offset"
        const val KEY_ENABLE_HEAD_CURSOR = "enable_head_cursor"
        const val KEY_SINGLE_TAP_ACTION = "single_tap_action"

        const val POS_TOP_RIGHT = "TOP_RIGHT"
        const val POS_TOP_LEFT = "TOP_LEFT"

        const val SINGLE_TAP_ACTION_CLICK = "CLICK"
        const val SINGLE_TAP_ACTION_TOGGLE_HUD = "TOGGLE_HUD"
        const val SINGLE_TAP_ACTION_RECENTER = "RECENTER"

        const val ACTION_UPDATE_POSITION = "com.example.dexoverlay.UPDATE_POSITION"
    }
}
