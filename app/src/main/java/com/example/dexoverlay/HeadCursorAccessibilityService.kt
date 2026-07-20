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

    private var volDownPressedTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVolDownLongPressedTriggered = false

    private val volDownLongPressRunnable = Runnable {
        isVolDownLongPressedTriggered = true
        log("KEY INTERCEPT: Volume Down long press (5s) → toggling mouse mode...")
        sendBroadcast(Intent(ACTION_TOGGLE_MOUSE_MODE))
    }

    private val clickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PERFORM_CLICK) {
                val x = intent.getFloatExtra(EXTRA_X, 960f)
                val y = intent.getFloatExtra(EXTRA_Y, 540f)
                val isRight = intent.getBooleanExtra(EXTRA_IS_RIGHT, false)
                log("RECEIVER_CLICK: Executing mapped click at coordinate ($x, $y) isRight=$isRight")
                performSystemClick(x, y, isRight)
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
        val filter = IntentFilter(ACTION_PERFORM_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickReceiver, filter, RECEIVER_NOT_EXPORTED)
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

    // ── Click strategies ──────────────────────────────────────────────────────

    private fun performSystemClick(x: Float, y: Float, isRightClick: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        // Strategy 1: Accessibility node tree click — works across all displays
        // including Samsung DeX virtual display (dispatchGesture setDisplayId does not).
        if (!isRightClick && tryNodeClick(x, y)) return

        // Strategy 2: dispatchGesture fallback (right-click long-press, or no node found)
        val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
        val targetDisplayId = targetDisplay.displayId

        val path = Path().apply { moveTo(x, y) }
        val duration = if (isRightClick) 1000L else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(targetDisplayId)
        }.build()

        val typeStr = if (isRightClick) "RIGHT CLICK (gesture)" else "LEFT CLICK (gesture fallback)"
        log("ACCESSIBILITY GESTURE: $typeStr at ($x, $y) on DisplayId $targetDisplayId")

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                log("ACCESSIBILITY GESTURE: ✓ completed at ($x, $y) DisplayId $targetDisplayId")
            }
            override fun onCancelled(g: GestureDescription?) {
                log("ACCESSIBILITY GESTURE: ✗ CANCELLED/BLOCKED at ($x, $y) DisplayId $targetDisplayId")
            }
        }, null)
    }

    /** Walk the accessibility window tree to find a clickable node at (x, y).
     *  This is display-agnostic and works on DeX virtual displays. */
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

    /** Depth-first search for deepest clickable node that contains (x, y). */
    private fun findClickableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        // Prefer deepest child match
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

        val prefs = getSharedPreferences("dex_hud_prefs", Context.MODE_PRIVATE)
        val mouseModeEnabled = prefs.getBoolean("mouse_mode_enabled", true)
        val volUpAction = prefs.getString("vol_up_action", "LEFT_CLICK") ?: "LEFT_CLICK"
        val volDownAction = prefs.getString("vol_down_action", "RIGHT_CLICK") ?: "RIGHT_CLICK"

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mouseModeEnabled) {
                if (action == KeyEvent.ACTION_DOWN) {
                    log("KEY INTERCEPT: Vol Up DOWN → action: $volUpAction")
                    triggerAction(volUpAction)
                }
                return true
            }
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isVolDownLongPressedTriggered = false
                    volDownPressedTime = System.currentTimeMillis()
                    mainHandler.postDelayed(volDownLongPressRunnable, 5000)
                    log("KEY INTERCEPT: Vol Down DOWN → starting 5s toggle timer...")
                }
                if (mouseModeEnabled) return true
            } else if (action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(volDownLongPressRunnable)
                if (!isVolDownLongPressedTriggered && mouseModeEnabled) {
                    log("KEY INTERCEPT: Vol Down UP → action: $volDownAction")
                    triggerAction(volDownAction)
                }
                val consumed = mouseModeEnabled || isVolDownLongPressedTriggered
                isVolDownLongPressedTriggered = false
                if (consumed) return true
            }
            return false
        }

        if (keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (mouseModeEnabled && action == KeyEvent.ACTION_DOWN) {
                log("KEY INTERCEPT: Bluetooth clicker $keyCode → LEFT_CLICK")
                triggerAction("LEFT_CLICK")
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    private fun triggerAction(actionName: String) {
        sendBroadcast(Intent(ACTION_TRIGGER_ACTION).apply {
            putExtra(EXTRA_ACTION_NAME, actionName)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val ACTION_PERFORM_CLICK   = "com.example.dexoverlay.PERFORM_CLICK"
        const val ACTION_TOGGLE_MOUSE_MODE = "com.example.dexoverlay.TOGGLE_MOUSE_MODE"
        const val ACTION_TRIGGER_ACTION  = "com.example.dexoverlay.TRIGGER_ACTION"
        const val EXTRA_ACTION_NAME = "action_name"
        const val EXTRA_X = "click_x"
        const val EXTRA_Y = "click_y"
        const val EXTRA_IS_RIGHT = "is_right"
    }
}
