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
    private var lastClickTimestamp: Long = 0L

    // Volume Down hold state
    private var isVolDownHeld = false
    private var hasScrolledDuringVolDown = false
    private var isVolDownLongPressedTriggered = false
    private val volDownLongPressRunnable = Runnable {
        if (isVolDownHeld && !hasScrolledDuringVolDown) {
            isVolDownLongPressedTriggered = true
            log("ACCESSIBILITY: Volume Down held 5s → Toggle Mouse Mode")
            sendBroadcast(Intent(ACTION_TOGGLE_MOUSE_MODE).apply { setPackage(packageName) })
        }
    }

    // Volume Up hold state (Touch Movement / Touch Drag)
    private var isVolUpHeld = false
    private var hasDraggedDuringVolUp = false
    private var volUpPressTime = 0L

    // Volume Down triple click state
    private var volDownTapCount = 0
    private var lastVolDownTapTime = 0L

    private fun notifyScrollMode(isScrolling: Boolean) {
        val intent = Intent(ACTION_SCROLL_MODE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_SCROLLING, isScrolling)
        }
        sendBroadcast(intent)
    }

    private fun notifyDragMode(isDragging: Boolean) {
        val intent = Intent(ACTION_DRAG_MODE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_DRAGGING, isDragging)
        }
        sendBroadcast(intent)
    }

    private fun triggerAction(actionName: String) {
        val intent = Intent(ACTION_TRIGGER_ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTION_NAME, actionName)
        }
        sendBroadcast(intent)
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
                ACTION_PERFORM_DRAG -> {
                    val fromX = intent.getFloatExtra(EXTRA_FROM_X, 960f)
                    val fromY = intent.getFloatExtra(EXTRA_FROM_Y, 540f)
                    val toX = intent.getFloatExtra(EXTRA_TO_X, 960f)
                    val toY = intent.getFloatExtra(EXTRA_TO_Y, 540f)
                    hasDraggedDuringVolUp = true
                    performSystemDrag(fromX, fromY, toX, toY)
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
            addAction(ACTION_PERFORM_DRAG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(clickReceiver, filter)
        }
        log("HeadCursorAccessibilityService created.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        log("HeadCursorAccessibilityService connected and ready.")
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

        val now = System.currentTimeMillis()
        if (now - lastClickTimestamp < 60L) {
            log("CLICK DEBOUNCE: Suppressed rapid click within 60ms")
            return
        }
        lastClickTimestamp = now

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val clickEngine = prefs.getString(OverlayService.KEY_CLICK_ENGINE, OverlayService.CLICK_ENGINE_TOUCH)
            ?: OverlayService.CLICK_ENGINE_TOUCH

        if (!isRightClick && clickEngine == OverlayService.CLICK_ENGINE_NODE && tryNodeClick(x, y)) {
            return
        }

        val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
        val targetDisplayId = targetDisplay.displayId

        val path = Path().apply { moveTo(x, y) }
        val duration = if (isRightClick) 1000L else 10L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(targetDisplayId)
        }.build()

        val handled = dispatchGesture(gesture, null, null)
        log("ACCESSIBILITY: Dispatched $duration ms touch tap at ($x, $y) on displayId=$targetDisplayId → result=$handled")
    }

    private fun performSystemDrag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
        val targetDisplayId = targetDisplay.displayId

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30L)
        val gesture = GestureDescription.Builder().apply {
            addStroke(stroke)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setDisplayId(targetDisplayId)
        }.build()

        dispatchGesture(gesture, null, null)
    }

    private fun performSystemScroll(x: Float, y: Float, deltaY: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val targetDisplay = DeXDisplayHelper.getTargetDisplay(this)
        val targetDisplayId = targetDisplay.displayId

        val startY = y
        val endY = (y - deltaY).coerceIn(0f, 2160f)

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
            }
            false
        } catch (e: Exception) {
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
        val volDownHoldAction = prefs.getString(OverlayService.KEY_VOL_DOWN_HOLD_ACTION, OverlayService.ACTION_VAL_SCROLL)
            ?: OverlayService.ACTION_VAL_SCROLL

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mouseModeEnabled) {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        isVolUpHeld = true
                        hasDraggedDuringVolUp = false
                        volUpPressTime = System.currentTimeMillis()
                        notifyDragMode(true)
                        log("KEY INTERCEPT: Vol Up DOWN → hold for touch movement/drag, tap for click")
                    }
                    return true
                } else if (action == KeyEvent.ACTION_UP) {
                    isVolUpHeld = false
                    notifyDragMode(false)

                    val holdDuration = System.currentTimeMillis() - volUpPressTime
                    if (!hasDraggedDuringVolUp || holdDuration < 200L) {
                        log("KEY INTERCEPT: Vol Up UP → tap click ($volUpAction)")
                        triggerAction(volUpAction)
                    }
                    hasDraggedDuringVolUp = false
                    return true
                }
            }
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isVolDownHeld = true
                    hasScrolledDuringVolDown = false
                    isVolDownLongPressedTriggered = false

                    if (volDownHoldAction == OverlayService.ACTION_VAL_SCROLL) {
                        notifyScrollMode(true)
                        log("KEY INTERCEPT: Vol Down DOWN → hold to scroll vertical by head pitch")
                    }
                }
                if (mouseModeEnabled) return true
            } else if (action == KeyEvent.ACTION_UP) {
                isVolDownHeld = false
                mainHandler.removeCallbacks(volDownLongPressRunnable)
                if (volDownHoldAction == OverlayService.ACTION_VAL_SCROLL) {
                    notifyScrollMode(false)
                }

                val now = System.currentTimeMillis()
                if (now - lastVolDownTapTime < 350L) {
                    volDownTapCount++
                } else {
                    volDownTapCount = 1
                }
                lastVolDownTapTime = now

                val tripleAction = prefs.getString(OverlayService.KEY_VOL_DOWN_TRIPLE_ACTION, OverlayService.ACTION_VAL_HOME)
                    ?: OverlayService.ACTION_VAL_HOME

                if (volDownTapCount == 3) {
                    volDownTapCount = 0
                    if (tripleAction == OverlayService.ACTION_VAL_HOME) {
                        log("KEY INTERCEPT: Vol Down 3x TRIPLE CLICK → Send GLOBAL_ACTION_HOME")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                    hasScrolledDuringVolDown = false
                    isVolDownLongPressedTriggered = false
                    return true
                }

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

    companion object {
        const val ACTION_PERFORM_CLICK  = "com.example.dexoverlay.PERFORM_CLICK"
        const val ACTION_PERFORM_SCROLL = "com.example.dexoverlay.PERFORM_SCROLL"
        const val ACTION_PERFORM_DRAG   = "com.example.dexoverlay.PERFORM_DRAG"

        const val EXTRA_X = "extra_x"
        const val EXTRA_Y = "extra_y"
        const val EXTRA_IS_RIGHT = "extra_is_right"
        const val EXTRA_SCROLL_DELTA_Y = "extra_scroll_delta_y"

        const val EXTRA_FROM_X = "extra_from_x"
        const val EXTRA_FROM_Y = "extra_from_y"
        const val EXTRA_TO_X = "extra_to_x"
        const val EXTRA_TO_Y = "extra_to_y"

        const val ACTION_TRIGGER_ACTION = "com.example.dexoverlay.TRIGGER_ACTION"
        const val EXTRA_ACTION_NAME = "extra_action_name"

        const val ACTION_TOGGLE_MOUSE_MODE = "com.example.dexoverlay.TOGGLE_MOUSE_MODE"
        const val ACTION_SCROLL_MODE_CHANGED = "com.example.dexoverlay.SCROLL_MODE_CHANGED"
        const val EXTRA_IS_SCROLLING = "extra_is_scrolling"

        const val ACTION_DRAG_MODE_CHANGED = "com.example.dexoverlay.DRAG_MODE_CHANGED"
        const val EXTRA_IS_DRAGGING = "extra_is_dragging"
    }
}
