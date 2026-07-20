package com.example.dexoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class HeadCursorAccessibilityService : AccessibilityService() {

    private val clickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PERFORM_CLICK) {
                val x = intent.getFloatExtra(EXTRA_X, 960f)
                val y = intent.getFloatExtra(EXTRA_Y, 540f)
                performSystemClick(x, y)
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
    }

    private fun performSystemClick(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                val triggerIntent = Intent("com.example.dexoverlay.TRIGGER_TAP")
                sendBroadcast(triggerIntent)
                return true // Consume key press to prevent system volume overlay or selection triggers
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val ACTION_PERFORM_CLICK = "com.example.dexoverlay.PERFORM_CLICK"
        const val EXTRA_X = "click_x"
        const val EXTRA_Y = "click_y"
    }
}
