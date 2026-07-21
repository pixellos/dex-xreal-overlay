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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1€ (One Euro) Filter for adaptive low-pass jitter suppression.
 */
class OneEuroFilter(
    var minCutoff: Float = 0.8f,
    var beta: Float = 0.007f,
    var dCutoff: Float = 1.0f
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

        val dx = (x - xPrev!!) / dt
        val aD = alpha(dCutoff, dt)
        val edx = aD * dx + (1.0f - aD) * dxPrev
        dxPrev = edx

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

    // Screen dimensions (dynamically queried from target display metrics)
    private var screenWidth = 1920f
    private var screenHeight = 1080f

    // Cursor Movement & Strategy Transforms
    private var rawCursorX = 960f
    private var rawCursorY = 540f
    private var cursorX = 960f
    private var cursorY = 540f
    private var lastImuTime = 0L

    // Trackpad Joystick-Style Continuous Head Scroll (Volume Down Hold)
    private var isScrollModeActive = false
    private var scrollAnchorX = 960f
    private var scrollAnchorY = 540f
    private var pitchOffset = 0f

    // Drag Mode (Volume Up Hold)
    private var isDragModeActive = false

    // In-memory Preference Caching (avoid disk SharedPreferences reads per IMU frame)
    @Volatile private var cachedSensitivity = 0.45f
    @Volatile private var cachedStrategy = STRATEGY_LINEAR

    private val filterX = OneEuroFilter(minCutoff = 0.8f, beta = 0.007f)
    private val filterY = OneEuroFilter(minCutoff = 0.8f, beta = 0.007f)

    private var imuManager: XrealOneImuManager? = null
    private var isNavActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    // 30Hz Trackpad Joystick Continuous Scroll Loop
    private val continuousScrollRunnable = object : Runnable {
        override fun run() {
            if (isScrollModeActive) {
                val absOffset = Math.abs(pitchOffset)
                // Fire scroll whenever pitch tilt exceeds tiny deadzone (0.05 = very sensitive)
                if (absOffset > 0.05f) {
                    val scrollDelta = pitchOffset * 20f  // amplify pitch tilt to scroll delta
                    val acc = HeadCursorAccessibilityService.instance
                    if (acc != null) {
                        acc.performDirectScroll(scrollAnchorX, scrollAnchorY, scrollDelta)
                    } else {
                        val scrollIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_SCROLL).apply {
                            setPackage(packageName)
                            putExtra(HeadCursorAccessibilityService.EXTRA_X, scrollAnchorX)
                            putExtra(HeadCursorAccessibilityService.EXTRA_Y, scrollAnchorY)
                            putExtra(HeadCursorAccessibilityService.EXTRA_SCROLL_DELTA_Y, scrollDelta)
                        }
                        sendBroadcast(scrollIntent)
                    }
                }
                handler.postDelayed(this, 33) // ~30Hz smooth continuous scroll pulse
            }
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
                reloadPreferencesCache()
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
                    val newScrollState = intent.getBooleanExtra(HeadCursorAccessibilityService.EXTRA_IS_SCROLLING, false)
                    if (newScrollState && !isScrollModeActive) {
                        isScrollModeActive = true
                        scrollAnchorX = cursorX
                        scrollAnchorY = cursorY
                        pitchOffset = 0f
                        HeadCursorAccessibilityService.instance?.setScrollModeUI(true)
                        handler.removeCallbacks(continuousScrollRunnable)
                        handler.post(continuousScrollRunnable)
                        LogBuffer.add("OVERLAY: Middle Mouse Pan Scroll Mode ACTIVATED at anchor ($scrollAnchorX, $scrollAnchorY)")
                    } else if (!newScrollState && isScrollModeActive) {
                        isScrollModeActive = false
                        pitchOffset = 0f
                        HeadCursorAccessibilityService.instance?.setScrollModeUI(false)
                        handler.removeCallbacks(continuousScrollRunnable)
                        LogBuffer.add("OVERLAY: Middle Mouse Pan Scroll Mode DEACTIVATED")
                    }
                }
                HeadCursorAccessibilityService.ACTION_DRAG_MODE_CHANGED -> {
                    isDragModeActive = intent.getBooleanExtra(HeadCursorAccessibilityService.EXTRA_IS_DRAGGING, false)
                    LogBuffer.add("OVERLAY: Drag mode changed → active=$isDragModeActive")
                }
            }
        }
    }

    private fun reloadPreferencesCache() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cachedSensitivity = prefs.getFloat(KEY_HEAD_SENSITIVITY, 0.45f).coerceIn(0.1f, 5.0f)
        cachedStrategy = prefs.getString(KEY_MOVEMENT_STRATEGY, STRATEGY_LINEAR) ?: STRATEGY_LINEAR
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        reloadPreferencesCache()
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
        // NOTE: Do NOT call setupCrosshairOverlay() here.
        // HeadCursorAccessibilityService.onServiceConnected() calls it itself.
        // Calling it again from here creates duplicate crosshair windows.
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

    private fun toggleMouseMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_MOUSE_MODE_ENABLED, true)
        val next = !current
        prefs.edit().putBoolean(KEY_MOUSE_MODE_ENABLED, next).apply()

        HeadCursorAccessibilityService.instance?.setCursorVisible(next)
        sendBroadcast(Intent("com.example.dexoverlay.REFRESH_UI").apply { setPackage(packageName) })
    }

    private fun executeAction(actionName: String) {
        handler.post {
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
                ACTION_VAL_TOUCH_TAP -> {
                    val clickIntent = Intent(HeadCursorAccessibilityService.ACTION_PERFORM_CLICK).apply {
                        setPackage(packageName)
                        putExtra(HeadCursorAccessibilityService.EXTRA_X, cursorX)
                        putExtra(HeadCursorAccessibilityService.EXTRA_Y, cursorY)
                        putExtra(HeadCursorAccessibilityService.EXTRA_IS_RIGHT, false)
                        putExtra(HeadCursorAccessibilityService.EXTRA_FORCE_TOUCH, true)
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
        lastImuTime = 0L

        imuManager = XrealOneImuManager(this).apply {
            onHeadMoveListener = { dx, dy ->
                handler.post { onHeadMove(dx, dy) }
            }
            start()
        }
    }

    private fun transformDelta(delta: Float, dt: Float, strategy: String, sensitivity: Float): Float {
        val absDelta = Math.abs(delta)
        val sign = if (delta >= 0) 1f else -1f

        return when (strategy) {
            STRATEGY_LOG -> {
                val logVal = Math.log1p(absDelta * 10.0).toFloat()
                sign * logVal * 15f * sensitivity
            }
            STRATEGY_NLOG -> {
                val powVal = Math.pow(absDelta.toDouble(), 1.5).toFloat()
                sign * powVal * 25f * sensitivity
            }
            STRATEGY_DERIVATIVE -> {
                val velocity = absDelta / dt.coerceAtLeast(0.001f)
                val gain = 1.0f + (velocity * 0.05f).coerceAtMost(3.0f)
                sign * absDelta * gain * 15f * sensitivity
            }
            else -> {
                sign * absDelta * 15f * sensitivity
            }
        }
    }

    /** Must be called on Main UI Thread. Uses monotonic SystemClock.elapsedRealtime() for physics dt. */
    private fun onHeadMove(deltaX: Float, deltaY: Float) {
        if (deltaX.isNaN() || deltaY.isNaN() || !deltaX.isFinite() || !deltaY.isFinite()) return

        val sensitivity = cachedSensitivity
        val strategy = cachedStrategy

        // Trackpad Joystick-Style Continuous Head Scroll (Volume Down Hold)
        if (isScrollModeActive) {
            // Freeze cursor at anchor - head tilt drives scroll speed, not cursor movement
            cursorX = scrollAnchorX
            cursorY = scrollAnchorY

            // pitchOffset = current tilt deflection from neutral (like a joystick axis).
            // We blend the new delta in so it feels like a spring-loaded joystick:
            // tilting further increases scroll speed, returning to level stops it.
            // We accumulate and then DECAY toward the fresh delta (joystick feel).
            pitchOffset = pitchOffset * 0.6f + deltaY * sensitivity * 3.0f

            // Keep crosshair locked at scroll anchor
            HeadCursorAccessibilityService.instance?.updateCursorPosition(scrollAnchorX, scrollAnchorY)
            return
        }

        val prevX = cursorX
        val prevY = cursorY

        val nowRealtime = SystemClock.elapsedRealtime()
        val dt = if (lastImuTime != 0L) ((nowRealtime - lastImuTime) / 1000f).coerceIn(0.001f, 0.1f) else 0.016f
        lastImuTime = nowRealtime

        val stepX = transformDelta(deltaX, dt, strategy, sensitivity)
        val stepY = transformDelta(deltaY, dt, strategy, sensitivity)

        rawCursorX = (rawCursorX + stepX).coerceIn(0f, screenWidth)
        rawCursorY = (rawCursorY + stepY).coerceIn(0f, screenHeight)

        if (strategy == STRATEGY_FILTERED) {
            cursorX = filterX.filter(rawCursorX, nowRealtime)
            cursorY = filterY.filter(rawCursorY, nowRealtime)
        } else {
            cursorX = rawCursorX
            cursorY = rawCursorY
        }

        // Update single top-most TYPE_ACCESSIBILITY_OVERLAY crosshair via AccessibilityService (0ms in-process)
        HeadCursorAccessibilityService.instance?.updateCursorPosition(cursorX, cursorY)

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
        handler.removeCallbacksAndMessages(null)
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
        const val KEY_VOL_DOWN_QUAD_ACTION = "vol_down_quad_action"
        const val KEY_MOUSE_MODE_ENABLED = "mouse_mode_enabled"

        const val KEY_HEAD_SENSITIVITY = "head_sensitivity"

        const val KEY_MOVEMENT_STRATEGY = "movement_strategy"
        const val STRATEGY_LINEAR     = "LINEAR"
        const val STRATEGY_LOG        = "LOG"
        const val STRATEGY_NLOG       = "NLOG"
        const val STRATEGY_FILTERED   = "FILTERED"
        const val STRATEGY_DERIVATIVE = "DERIVATIVE"

        const val KEY_CLICK_ENGINE   = "click_engine"
        const val CLICK_ENGINE_TOUCH = "TOUCH_GESTURE"
        const val CLICK_ENGINE_NODE  = "NODE_CLICK"

        const val ACTION_VAL_LEFT_CLICK = "LEFT_CLICK"
        const val ACTION_VAL_TOUCH_TAP = "TOUCH_TAP"
        const val ACTION_VAL_RIGHT_CLICK = "RIGHT_CLICK"
        const val ACTION_VAL_SCROLL = "SCROLL"
        const val ACTION_VAL_HOME = "HOME"
        const val ACTION_VAL_TOGGLE_MOUSE = "TOGGLE_MOUSE"
        const val ACTION_VAL_NONE = "NONE"

        const val POS_TOP_RIGHT = "TOP_RIGHT"
        const val POS_TOP_LEFT = "TOP_LEFT"

        const val ACTION_UPDATE_POSITION = "com.example.dexoverlay.UPDATE_POSITION"
    }
}
