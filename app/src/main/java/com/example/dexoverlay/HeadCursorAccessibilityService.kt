package com.example.dexoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class HeadCursorAccessibilityService : AccessibilityService() {

    private var volDownPressedTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVolDownLongPressedTriggered = false

    private val volDownLongPressRunnable = Runnable {
        isVolDownLongPressedTriggered = true
        log("KEY INTERCEPT: Volume Down long press (5s) triggered! Toggling mouse mode...")
        val toggleIntent = Intent(ACTION_TOGGLE_MOUSE_MODE)
        sendBroadcast(toggleIntent)
    }

    private val clickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PERFORM_CLICK) {
                val x = intent.getFloatExtra(EXTRA_X, 960f)
                val y = intent.getFloatExtra(EXTRA_Y, 540f)
                val isRight = intent.getBooleanExtra(EXTRA_IS_RIGHT, false)
                performSystemClick(x, y, isRight)
            }
        }
    }

    private fun log(msg: String) {
        Log.d("HeadCursorService", msg)
        LogBuffer.add(msg)
        val intent = Intent(MainActivity.ACTION_LOG_UPDATE).apply {
            putExtra(MainActivity.EXTRA_LOG_MSG, msg)
        }
        sendBroadcast(intent)
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

    private fun performSystemClick(x: Float, y: Float, isRightClick: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val duration = if (isRightClick) 1000L else 50L
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            
            val clickTypeStr = if (isRightClick) "RIGHT CLICK (Long Press)" else "LEFT CLICK (Tap)"
            log("ACCESSIBILITY GESTURE: Dispatched $clickTypeStr at coordinate ($x, $y)")

            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    log("ACCESSIBILITY GESTURE: Successfully completed click at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    log("ACCESSIBILITY GESTURE: Click gesture was CANCELLED/BLOCKED by system at ($x, $y)")
                }
            }, null)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val actionStr = if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"

        // LOG ALL KEY EVENTS ENTERING THE ACCESSIBILITY DRIVER
        log("KEY INTERCEPT: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}), action=$actionStr")

        val prefs = getSharedPreferences("dex_hud_prefs", Context.MODE_PRIVATE)
        val mouseModeEnabled = prefs.getBoolean("mouse_mode_enabled", true)
        val volUpAction = prefs.getString("vol_up_action", "LEFT_CLICK") ?: "LEFT_CLICK"
        val volDownAction = prefs.getString("vol_down_action", "RIGHT_CLICK") ?: "RIGHT_CLICK"

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mouseModeEnabled) {
                if (action == KeyEvent.ACTION_DOWN) {
                    log("KEY INTERCEPT: Volume Up DOWN -> executing mapped action: $volUpAction")
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
                    mainHandler.postDelayed(volDownLongPressRunnable, 5000) // 5 seconds long press
                    log("KEY INTERCEPT: Volume Down DOWN -> starting 5s Mouse Mode toggle timer...")
                }
                if (mouseModeEnabled) return true
            } else if (action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(volDownLongPressRunnable)
                if (!isVolDownLongPressedTriggered && mouseModeEnabled) {
                    log("KEY INTERCEPT: Volume Down UP -> executing mapped action: $volDownAction")
                    triggerAction(volDownAction)
                }
                val consumed = mouseModeEnabled || isVolDownLongPressedTriggered
                isVolDownLongPressedTriggered = false
                if (consumed) return true
            }
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
        ) {
            if (mouseModeEnabled) {
                if (action == KeyEvent.ACTION_DOWN) {
                    log("KEY INTERCEPT: Bluetooth clicker key $keyCode -> executing Left Click")
                    triggerAction("LEFT_CLICK")
                }
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    private fun triggerAction(actionName: String) {
        val intent = Intent(ACTION_TRIGGER_ACTION).apply {
            putExtra(EXTRA_ACTION_NAME, actionName)
        }
        sendBroadcast(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val ACTION_PERFORM_CLICK = "com.example.dexoverlay.PERFORM_CLICK"
        const val ACTION_TOGGLE_MOUSE_MODE = "com.example.dexoverlay.TOGGLE_MOUSE_MODE"
        const val ACTION_TRIGGER_ACTION = "com.example.dexoverlay.TRIGGER_ACTION"
        const val EXTRA_ACTION_NAME = "action_name"
        const val EXTRA_X = "click_x"
        const val EXTRA_Y = "click_y"
        const val EXTRA_IS_RIGHT = "is_right"
    }
}
