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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Industry-standard 1€ (One Euro) Filter for adaptive head-tracking jitter suppression.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.2f,   // Cutoff frequency at rest (Hz) — lower = smoother/zero jitter
    var beta: Float = 0.007f,      // Velocity response slope — higher = zero lag on quick movement
    var dCutoff: Float = 1.0f      // Derivative cutoff frequency (Hz)
) {
    private var xPrev: Float? = null
    private var dxPrev: Float = 0.0f
    private var tPrev: Long? = null

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1.0f / (2.0f * Math.PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    fun filter(x: Float, timestampMs: Long): Float {
        if (tPrev == null || xPrev == null) {
            xPrev = x
            tPrev = timestampMs
            dxPrev = 0.0f
            return x
        }

        val dt = ((timestampMs - tPrev!!) / 1000.0f).coerceIn(0.001f, 0.1f)
        tPrev = timestampMs

        // Estimate velocity (derivative)
        val dx = (x - xPrev!!) / dt
        val aD = alpha(dCutoff, dt)
        val edx = aD * dx + (1.0f - aD) * dxPrev
        dxPrev = edx

        // Dynamic cutoff frequency based on velocity magnitude
        val cutoff = minCutoff + beta * Math.abs(edx)
        val a = alpha(cutoff, dt)

        val xHat = a * x + (1.0f - a) * xPrev!!
        xPrev = xHat
        return xHat
    }

    fun reset() {
        xPrev = null
        tPrev = null
        dxPrev = 0.0f
    }
}

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var clockTextView: TextView? = null
    private var batteryTextView: TextView? = null
    private var navTraceTextView: TextView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    // Cursor Components (Fallback Window)
    private var cursorRootFrame: FrameLayout? = null
    private var cursorLayout: LinearLayout? = null
    private var cursorIconView: TextView? = null
    private var cursorModeLabel: TextView? = null
    private var cursorParams: WindowManager.LayoutParams? = null

    // Screen dimensions (dynamically queried from target display metrics)
    private var screenWidth = 1920f
    private var screenHeight = 1080f

    // Cursor Movement & 1Euro Adaptive Filtering
    private var rawCursorX = 960f
    private var rawCursorY = 540f
    private var cursorX = 960f
    private var cursorY = 540f
    private var cursorMeasuredWidth = 30
    private var cursorMeasuredHeight = 30

    private val filterX = OneEuroFilter(minCutoff = 1.2f, beta = 0.007f)
    private val filterY = OneEuroFilter(minCutoff = 1.2f, beta = 0.007f)

    // Scroll mode (holding Volume Down + tilting head)
    private var isScrollModeActive = false
    private var scrollAccumulatorY = 0f

    // Drag mode (holding Volume Up + tilting head)
    private var isDragModeActive = false

    private var imuManager: XrealOneImuManager? = null
    private var isNavActive = false
    private var isHudVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    private val hideModeLabelRunnable = Runnable {
        cursorModeLabel?.visibility = View.GONE
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

    // Dynamic Action Trigger Receiver
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HeadCursorAccessibilityService.ACTION_TRIGGER_ACTION -> {
                    val actionName = intent.getStringExtra(HeadCursorAccessibilityService.EXTRA_ACTION_NAME) ?: "NONE"
                    executeAction(actionName)
                }
                HeadCursorAccessibilityService.ACTION_TOGGLE_MOUSE_MODE -> {
                    toggleMouseMode()
                }
                HeadCursorAccessibilityService.ACTION_SCROLL_MODE_CHANGED -> {
                    isScrollModeActive = intent.getBooleanExtra(HeadCursorAccessibilityService.EXTRA_IS_SCROLLING, false)
                    if (!isScrollModeActive) scrollAccumulatorY = 0f
                }
                HeadCursorAccessibilityService.ACTION_DRAG_MODE_CHANGED -> {
                    isDragModeActive = intent.getBooleanExtra(HeadCursorAccessibilityService.EXTRA_IS_DRAGGING, false)
                    LogBuffer.add("OVERLAY: Drag mode changed → active=$isDragModeActive")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        updateDisplayBounds()

        fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }

        register(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        register(navReceiver, IntentFilter(MapsNavListenerService.ACTION_NAV_UPDATE))
        register(positionReceiver, IntentFilter(ACTION_UPDATE_POSITION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, IntentFilter().apply {
                addAction(HeadCursorAccessibilityService.ACTION_TRIGGER_ACTION)
                addAction(HeadCursorAccessibilityService.ACTION_TOGGLE_MOUSE_MODE)
                addAction(HeadCursorAccessibilityService.ACTION_SCROLL_MODE_CHANGED)
                addAction(HeadCursorAccessibilityService.ACTION_DRAG_MODE_CHANGED)
            }, RECEIVER_EXPORTED)
        } else {
            registerReceiver(actionReceiver, IntentFilter().apply {
                addAction(HeadCursorAccessibilityService.ACTION_TRIGGER_ACTION)
                addAction(HeadCursorAccessibilityService.ACTION_TOGGLE_MOUSE_MODE)
                addAction(HeadCursorAccessibilityService.ACTION_SCROLL_MODE_CHANGED)
                addAction(HeadCursorAccessibilityService.ACTION_DRAG_MODE_CHANGED)
            })
        }

        setupOverlayWindow()
        
        // Setup accessibility top-most TYPE_ACCESSIBILITY_OVERLAY window if service is connected
        HeadCursorAccessibilityService.instance?.setupCrosshairOverlay()
        if (HeadCursorAccessibilityService.instance == null) {
            setupHeadTrackedCursor()
        }
        initImuDrivers()
    }

    private fun updateDisplayBounds() {
        try {
            val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            targetDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.toFloat()
            screenHeight = metrics.heightPixels.toFloat()
            rawCursorX = screenWidth / 2f
            rawCursorY = screenHeight / 2f
            cursorX = rawCursorX
            cursorY = rawCursorY
            LogBuffer.add("OVERLAY: Target display metrics=${screenWidth.toInt()}x${screenHeight.toInt()}")
        } catch (e: Exception) {
            screenWidth = 1920f
            screenHeight = 1080f
        }
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

    private fun setupHeadTrackedCursor() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enableHeadCursor = prefs.getBoolean(KEY_ENABLE_HEAD_CURSOR, true)
        val mouseModeEnabled = prefs.getBoolean(KEY_MOUSE_MODE_ENABLED, true)
        if (!enableHeadCursor) return

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = if (mouseModeEnabled) View.VISIBLE else View.GONE
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Empty Outline Crosshair Icon (1/2 size = 14f)
        cursorIconView = TextView(this).apply {
            text = "⌖"
            textSize = 14f
            setTextColor(getCursorColorForCurrentMode())
            setShadowLayer(4f, 0f, 0f, getCursorColorForCurrentMode())
            gravity = Gravity.CENTER
        }
        container.addView(cursorIconView)

        cursorModeLabel = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(6f, 0f, 0f, Color.parseColor("#88FFE600"))
            visibility = View.GONE
            setPadding(0, 4, 0, 0)
        }
        container.addView(cursorModeLabel)

        container.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0) {
                cursorMeasuredWidth = w
                cursorMeasuredHeight = h
                updateCursorViewPosition()
            }
        }

        val childParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        rootFrame.addView(container, childParams)

        cursorLayout = container
        cursorRootFrame = rootFrame

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
            x = 0
            y = 0
        }
        cursorParams = params

        try {
            windowManager.addView(cursorRootFrame, cursorParams)
            updateCursorViewPosition()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Fast GPU hardware-accelerated translation update across 100% full screen area. */
    private fun updateCursorViewPosition() {
        val view = cursorLayout ?: return
        val halfW = if (cursorMeasuredWidth > 0) cursorMeasuredWidth / 2f else 15f
        val halfH = if (cursorMeasuredHeight > 0) cursorMeasuredHeight / 2f else 15f
        view.translationX = cursorX - halfW
        view.translationY = cursorY - halfH
    }

    private fun getCursorIconForCurrentMode(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mouseModeEnabled = prefs.getBoolean(KEY_MOUSE_MODE_ENABLED, true)
        if (!mouseModeEnabled) return "❌"
        return "⌖"
    }

    private fun getCursorColorForCurrentMode(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mouseModeEnabled = prefs.getBoolean(KEY_MOUSE_MODE_ENABLED, true)
        if (!mouseModeEnabled) return Color.parseColor("#FF0055")
        return Color.parseColor("#00E5FF")
    }

    private fun updateCursorAppearance() {
        handler.post {
            cursorIconView?.text = getCursorIconForCurrentMode()
            val color = getCursorColorForCurrentMode()
            cursorIconView?.setTextColor(color)
            cursorIconView?.setShadowLayer(4f, 0f, 0f, color)
        }
    }

    private fun toggleMouseMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_MOUSE_MODE_ENABLED, true)
        val next = !current
        prefs.edit().putBoolean(KEY_MOUSE_MODE_ENABLED, next).apply()

        handler.post {
            cursorModeLabel?.visibility = View.VISIBLE
            handler.removeCallbacks(hideModeLabelRunnable)

            HeadCursorAccessibilityService.instance?.setCursorVisible(next)

            if (next) {
                cursorRootFrame?.visibility = View.VISIBLE
                cursorModeLabel?.text = "🖱️"
                cursorModeLabel?.setTextColor(Color.parseColor("#00FF66"))
                updateCursorAppearance()
                handler.postDelayed(hideModeLabelRunnable, 2000)
            } else {
                cursorModeLabel?.text = "❌"
                cursorModeLabel?.setTextColor(Color.parseColor("#FF0055"))
                cursorIconView?.text = "❌"
                cursorIconView?.setTextColor(Color.parseColor("#FF0055"))
                cursorIconView?.setShadowLayer(4f, 0f, 0f, Color.parseColor("#FF0055"))
                handler.postDelayed({
                    cursorRootFrame?.visibility = View.GONE
                }, 2000)
            }
        }

        sendBroadcast(Intent("com.example.dexoverlay.REFRESH_UI"))
    }

    private fun executeAction(actionName: String) {
        handler.post {
            cursorIconView?.setTextColor(Color.parseColor("#FFFFFF"))
            handler.postDelayed({ cursorIconView?.setTextColor(getCursorColorForCurrentMode()) }, 150)

            when (actionName) {
                ACTION_VAL_LEFT_CLICK -> {
                    val clickIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_CLICK).apply {
                        setPackage(packageName)
                        putExtra(HeadCursorAccessibilityService.EXTRA_X, cursorX)
                        putExtra(HeadCursorAccessibilityService.EXTRA_Y, cursorY)
                        putExtra(HeadCursorAccessibilityService.EXTRA_IS_RIGHT, false)
                    }
                    sendBroadcast(clickIntent)
                }
                ACTION_VAL_RIGHT_CLICK -> {
                    val clickIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_CLICK).apply {
                        setPackage(packageName)
                        putExtra(HeadCursorAccessibilityService.EXTRA_X, cursorX)
                        putExtra(HeadCursorAccessibilityService.EXTRA_Y, cursorY)
                        putExtra(HeadCursorAccessibilityService.EXTRA_IS_RIGHT, true)
                    }
                    sendBroadcast(clickIntent)
                }
            }
        }
    }

    private fun initImuDrivers() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enableHeadCursor = prefs.getBoolean(KEY_ENABLE_HEAD_CURSOR, true)
        if (!enableHeadCursor) return

        filterX.reset()
        filterY.reset()

        imuManager = XrealOneImuManager(this).apply {
            onHeadMoveListener = { dx, dy -> onHeadMove(dx, dy) }
            start()
        }
    }

    private fun onHeadMove(deltaX: Float, deltaY: Float) {
        if (deltaX.isNaN() || deltaY.isNaN() || !deltaX.isFinite() || !deltaY.isFinite()) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sensitivity = prefs.getFloat(KEY_HEAD_SENSITIVITY, 0.45f).coerceIn(0.1f, 5.0f)

        if (isScrollModeActive) {
            scrollAccumulatorY += deltaY * 120f * sensitivity
            if (Math.abs(scrollAccumulatorY) >= 20f) {
                val amountY = scrollAccumulatorY
                scrollAccumulatorY = 0f
                val scrollIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_SCROLL).apply {
                    setPackage(packageName)
                    putExtra(HeadCursorAccessibilityService.EXTRA_X, cursorX)
                    putExtra(HeadCursorAccessibilityService.EXTRA_Y, cursorY)
                    putExtra(HeadCursorAccessibilityService.EXTRA_SCROLL_DELTA_Y, amountY)
                }
                sendBroadcast(scrollIntent)
            }
            return
        }

        val prevX = cursorX
        val prevY = cursorY

        rawCursorX = (rawCursorX + deltaX * 15f * sensitivity).coerceIn(0f, screenWidth)
        rawCursorY = (rawCursorY + deltaY * 15f * sensitivity).coerceIn(0f, screenHeight)

        val now = System.currentTimeMillis()
        cursorX = filterX.filter(rawCursorX, now)
        cursorY = filterY.filter(rawCursorY, now)

        // Update top-most TYPE_ACCESSIBILITY_OVERLAY crosshair via AccessibilityService (0ms in-process)
        val accService = HeadCursorAccessibilityService.instance
        if (accService != null) {
            accService.updateCursorPosition(cursorX, cursorY)
        } else {
            updateCursorViewPosition()
        }

        if (isDragModeActive) {
            val dragIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_DRAG).apply {
                setPackage(packageName)
                putExtra(HeadCursorAccessibilityService.EXTRA_FROM_X, prevX)
                putExtra(HeadCursorAccessibilityService.EXTRA_FROM_Y, prevY)
                putExtra(HeadCursorAccessibilityService.EXTRA_TO_X, cursorX)
                putExtra(HeadCursorAccessibilityService.EXTRA_TO_Y, cursorY)
            }
            sendBroadcast(dragIntent)
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
            .setContentText("Unified XREAL 1s Tracking Active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(hideModeLabelRunnable)
        imuManager?.stop()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(navReceiver)
            unregisterReceiver(positionReceiver)
            unregisterReceiver(actionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }
        if (cursorRootFrame != null) {
            try { windowManager.removeView(cursorRootFrame) } catch (e: Exception) {}
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

        const val KEY_VOL_UP_ACTION = "vol_up_action"
        const val KEY_VOL_DOWN_ACTION = "vol_down_action"
        const val KEY_VOL_DOWN_HOLD_ACTION = "vol_down_hold_action"
        const val KEY_VOL_DOWN_TRIPLE_ACTION = "vol_down_triple_action"
        const val KEY_MOUSE_MODE_ENABLED = "mouse_mode_enabled"

        const val KEY_HEAD_SENSITIVITY = "head_sensitivity"

        const val KEY_CLICK_ENGINE   = "click_engine"
        const val CLICK_ENGINE_TOUCH = "TOUCH_GESTURE"
        const val CLICK_ENGINE_NODE  = "NODE_CLICK"

        const val ACTION_VAL_LEFT_CLICK = "LEFT_CLICK"
        const val ACTION_VAL_RIGHT_CLICK = "RIGHT_CLICK"
        const val ACTION_VAL_SCROLL = "SCROLL"
        const val ACTION_VAL_HOME = "HOME"
        const val ACTION_VAL_NONE = "NONE"

        const val POS_TOP_RIGHT = "TOP_RIGHT"
        const val POS_TOP_LEFT = "TOP_LEFT"

        const val ACTION_UPDATE_POSITION = "com.example.dexoverlay.UPDATE_POSITION"
    }
}
