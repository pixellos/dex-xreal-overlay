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
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class HeadCursorAccessibilityService : AccessibilityService() {

    private var volDownPressedTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isVolDownLongPressedTriggered = false

    private val volDownLongPressRunnable = Runnable {
        isVolDownLongPressedTriggered = true
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

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_PERFORM_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(clickReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(clickReceiver) } catch (e: Exception) {}
        mainHandler.removeCallbacks(volDownLongPressRunnable)
    }

    private fun performSystemClick(x: Float, y: Float, isRightClick: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val duration = if (isRightClick) 1000L else 50L
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        val prefs = getSharedPreferences("dex_hud_prefs", Context.MODE_PRIVATE)
        val mouseModeEnabled = prefs.getBoolean("mouse_mode_enabled", true)
        val volUpAction = prefs.getString("vol_up_action", "LEFT_CLICK") ?: "LEFT_CLICK"
        val volDownAction = prefs.getString("vol_down_action", "RIGHT_CLICK") ?: "RIGHT_CLICK"

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mouseModeEnabled) {
                if (action == KeyEvent.ACTION_DOWN) {
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
                }
                if (mouseModeEnabled) return true
            } else if (action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(volDownLongPressRunnable)
                if (!isVolDownLongPressedTriggered && mouseModeEnabled) {
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
