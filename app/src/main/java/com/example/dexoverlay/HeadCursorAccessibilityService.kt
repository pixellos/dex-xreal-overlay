package com.example.dexoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HeadCursorAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVolDownLongPressedTriggered = false
    private var isVolDownHeld = false
    private var hasScrolledDuringVolDown = false
    private var lastClickTimestamp = 0L

    private val volDownLongPressRunnable = Runnable {
        isVolDownLongPressedTriggered = true
        log("KEY INTERCEPT: Volume Down long press (5s) → toggling mouse mode...")
        sendBroadcast(Intent(ACTION_TOGGLE_MOUSE_MODE).apply { setPackage(packageName) })
    }

    private val clickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PERFORM_CLICK -> {
                    val x = intent.getFloatExtra(EXTRA_X, 960f)
                    val y = intent.getFloatExtra(EXTRA_Y, 540f)
                    val isRight = intent.getBooleanExtra(EXTRA_IS_RIGHT, false)
                    log("RECEIVER_CLICK: Executing mapped click at coordinate ($x, $y) isRight=$isRight")
                    performSystemClick(x, y, isRight)
                }
                ACTION_PERFORM_SCROLL -> {
                    val x = intent.getFloatExtra(EXTRA_X, 960f)
                    val y = intent.getFloatExtra(EXTRA_Y, 540f)
                    val deltaY = intent.getFloatExtra(EXTRA_SCROLL_DELTA_Y, 0f)
                    hasScrolledDuringVolDown = true
                    log("RECEIVER_SCROLL: Vertical scroll at ($x, $y), deltaY=$deltaY")
                    performSystemScroll(x, y, deltaY)
                }
            }
        }
    }

    private fun log(msg: String) {
        Log.d("HeadCursorService", msg)
        LogBuffer.add(msg)
        sendBroadcast(Intent(MainActivity.ACTION_LOG_UPDATE).apply {
            putExtra(MainActivity.EXTRA_LOG_MSG, msg)
        })
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_PERFORM_CLICK)
            addAction(ACTION_PERFORM_SCROLL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(clickReceiver, filter)
        }
        log("HeadCursorAccessibilityService created and initialized.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(clickReceiver) } catch (e: Exception) {}
        mainHandler.removeCallbacks(volDownLongPressRunnable)
        log("HeadCursorAccessibilityService destroyed.")
    }

    // ── Click & Scroll strategies ─────────────────────────────────────────────

    private fun performSystemClick(x: Float, y: Float, isRightClick: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        // 200ms Click Debouncer: prevents rapid button bounces from creating accidental double-clicks
        val now = System.currentTimeMillis()
        if (now - lastClickTimestamp < 200L) {
            log("CLICK DEBOUNCE: Suppressed rapid click within 200ms (prevents double-click semantic)")
            return
        }
        lastClickTimestamp = now

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val clickEngine = prefs.getString(OverlayService.KEY_CLICK_ENGINE, OverlayService.CLICK_ENGINE_TOUCH)
            ?: OverlayService.CLICK_ENGINE_TOUCH

        if (!isRightClick && clickEngine == OverlayService.CLICK_ENGINE_NODE && tryNodeClick(x, y)) {
            return
        }

        // Real Touch Gesture Simulation
        val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
        val targetDisplayId = targetDisplay.displayId

        val path = Path().apply { moveTo(x, y) }
        val duration = if (isRightClick) 1000L else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(targetDisplayId)
        }.build()

        val typeStr = if (isRightClick) "RIGHT CLICK (touch gesture)" else "LEFT CLICK (simulated touch gesture)"
        log("ACCESSIBILITY GESTURE: $typeStr at ($x, $y) on DisplayId $targetDisplayId")

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                log("ACCESSIBILITY GESTURE: ✓ Touch completed at ($x, $y) DisplayId $targetDisplayId")
            }
            override fun onCancelled(g: GestureDescription?) {
                log("ACCESSIBILITY GESTURE: ✗ Touch CANCELLED/BLOCKED at ($x, $y) DisplayId $targetDisplayId")
            }
        }, null)
    }

    /** Dispatches a vertical drag gesture at (x, y) to scroll content up/down. */
    private fun performSystemScroll(x: Float, y: Float, deltaY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
        val targetDisplayId = targetDisplay.displayId

        val startY = y
        val endY = (y - deltaY).coerceIn(50f, 2500f)

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 120L)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(targetDisplayId)
        }.build()

        dispatchGesture(gesture, null, null)
    }

    /** Walk the accessibility window tree to find a clickable node at (x, y). */
    private fun tryNodeClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val xi = x.toInt(); val yi = y.toInt()
            for (window in windows) {
                val wBounds = Rect()
                window.getBoundsInScreen(wBounds)
                if (!wBounds.contains(xi, yi)) continue
                val root = window.root ?: continue
                val node = findClickableNodeAt(root, xi, yi)
                if (node != null) {
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    log("NODE CLICK: ACTION_CLICK on '${node.className}' at ($x,$y) → success=$success window='${window.title}'")
                    return success
                }
                log("NODE CLICK: window '${window.title}' bounds=$wBounds contains ($x,$y) but no clickable node found")
            }
            log("NODE CLICK: No window contains ($x, $y) — windows=${windows.size}")
            false
        } catch (e: Exception) {
            log("NODE CLICK: Exception: ${e.message}")
            false
        }
    }

    private fun findClickableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeAt(child, x, y)
            if (result != null) return result
        }

        return if (node.isClickable) node else null
    }

    // ── Key event handling ────────────────────────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val actionStr = if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"

        log("KEY INTERCEPT: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}), action=$actionStr")

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val mouseModeEnabled = prefs.getBoolean(OverlayService.KEY_MOUSE_MODE_ENABLED, true)
        val volUpAction = prefs.getString(OverlayService.KEY_VOL_UP_ACTION, OverlayService.ACTION_VAL_LEFT_CLICK)
            ?: OverlayService.ACTION_VAL_LEFT_CLICK
        val volDownAction = prefs.getString(OverlayService.KEY_VOL_DOWN_ACTION, OverlayService.ACTION_VAL_RIGHT_CLICK)
            ?: OverlayService.ACTION_VAL_RIGHT_CLICK

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mouseModeEnabled) {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    log("KEY INTERCEPT: Vol Up DOWN → action: $volUpAction (instant)")
                    triggerAction(volUpAction)
                }
                return true
            }
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isVolDownHeld = true
                    hasScrolledDuringVolDown = false
                    isVolDownLongPressedTriggered = false
                    mainHandler.postDelayed(volDownLongPressRunnable, 5000)
                    notifyScrollMode(true)
                    log("KEY INTERCEPT: Vol Down DOWN → hold to scroll vertical by head pitch")
                }
                if (mouseModeEnabled) return true
            } else if (action == KeyEvent.ACTION_UP) {
                isVolDownHeld = false
                mainHandler.removeCallbacks(volDownLongPressRunnable)
                notifyScrollMode(false)

                // Only execute click on release IF the user didn't scroll or trigger 5s long press
                if (mouseModeEnabled && !isVolDownLongPressedTriggered && !hasScrolledDuringVolDown) {
                    log("KEY INTERCEPT: Vol Down UP → action: $volDownAction")
                    triggerAction(volDownAction)
                }

                val consumed = mouseModeEnabled || isVolDownLongPressedTriggered || hasScrolledDuringVolDown
                hasScrolledDuringVolDown = false
                isVolDownLongPressedTriggered = false
                if (consumed) return true
            }
            return false
        }

        if (keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (mouseModeEnabled && action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                log("KEY INTERCEPT: Bluetooth clicker $keyCode → LEFT_CLICK (instant)")
                triggerAction(OverlayService.ACTION_VAL_LEFT_CLICK)
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    private fun notifyScrollMode(active: Boolean) {
        sendBroadcast(Intent(ACTION_SCROLL_MODE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_SCROLLING, active)
        })
    }

    private fun triggerAction(actionName: String) {
        sendBroadcast(Intent(ACTION_TRIGGER_ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTION_NAME, actionName)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val ACTION_PERFORM_CLICK      = "com.example.dexoverlay.PERFORM_CLICK"
        const val ACTION_PERFORM_SCROLL     = "com.example.dexoverlay.PERFORM_SCROLL"
        const val ACTION_TOGGLE_MOUSE_MODE  = "com.example.dexoverlay.TOGGLE_MOUSE_MODE"
        const val ACTION_TRIGGER_ACTION     = "com.example.dexoverlay.TRIGGER_ACTION"
        const val ACTION_SCROLL_MODE_CHANGED = "com.example.dexoverlay.SCROLL_MODE_CHANGED"

        const val EXTRA_ACTION_NAME     = "action_name"
        const val EXTRA_X               = "click_x"
        const val EXTRA_Y               = "click_y"
        const val EXTRA_IS_RIGHT        = "is_right"
        const val EXTRA_SCROLL_DELTA_Y  = "scroll_delta_y"
        const val EXTRA_IS_SCROLLING    = "is_scrolling"
    }
}
