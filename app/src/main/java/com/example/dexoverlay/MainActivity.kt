package com.example.dexoverlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // ── Cyberpunk / Banana Palette ────────────────────────────────────────────
    companion object {
        const val BG        = "#000000"
        const val BG_CARD   = "#0A0A0A"
        const val GREEN     = "#00FF66"
        const val YELLOW    = "#FFE600"
        const val CYAN      = "#00E5FF"
        const val RED       = "#FF0055"
        const val DIM       = "#1A1A1A"

        const val ACTION_LOG_UPDATE = "com.example.dexoverlay.LOG_UPDATE"
        const val EXTRA_LOG_MSG     = "log_msg"
    }

    private lateinit var statusTag: TextView
    private lateinit var btnPermissionStatus: Button
    private lateinit var cbMouseMode: CheckBox

    // Tab view containers
    private lateinit var tabHudContent: LinearLayout
    private lateinit var tabHeadMouseContent: LinearLayout
    private lateinit var tabDiagnoseContent: LinearLayout

    // Tab selection buttons
    private lateinit var btnTabHud: Button
    private lateinit var btnTabHeadMouse: Button
    private lateinit var btnTabDiagnose: Button

    private var activeTab = 0 // 0 = HUD, 1 = HEAD MOUSE, 2 = DIAGNOSE

    private val refreshUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.dexoverlay.REFRESH_UI") {
                val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
                cbMouseMode.isChecked = prefs.getBoolean(OverlayService.KEY_MOUSE_MODE_ENABLED, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopOverlayService()

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 18, 24, 24)
            setBackgroundColor(Color.parseColor(BG))
        }

        // ── Header ────────────────────────────────────────────────────────────
        val header = row().apply {
            background = border(YELLOW, 2)
            setPadding(16, 12, 16, 12)
        }
        header.addView(label("🍌 CYBERDECK // BANANA OS", YELLOW, 14f, bold = true, weight = 1f))
        statusTag = label("[ PENDING ]", RED, 10f)
        header.addView(statusTag)
        root.addView(header)
        root.addView(gap())

        // ── Banana 3-Tab Selector Bar ──────────────────────────────────────────
        val tabNav = row().apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        }

        btnTabHud = Button(this).apply {
            text = "🖥️ HUD"
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener { switchTab(0) }
        }

        btnTabHeadMouse = Button(this).apply {
            text = "🎮 HEAD MOUSE"
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
            setOnClickListener { switchTab(1) }
        }

        btnTabDiagnose = Button(this).apply {
            text = "🔍 DIAGNOSE"
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
            setOnClickListener { switchTab(2) }
        }

        tabNav.addView(btnTabHud)
        tabNav.addView(btnTabHeadMouse)
        tabNav.addView(btnTabDiagnose)
        root.addView(tabNav)
        root.addView(gap())

        // ══════════════════════════════════════════════════════════════════════
        // ── TAB 0: HUD CONTENT ────────────────────────────────────────────────
        // ══════════════════════════════════════════════════════════════════════
        tabHudContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Card: HUD Scale & Position
        val currentScale = prefs.getFloat(OverlayService.KEY_SCALE, 1.0f)
        val currentPos   = prefs.getString(OverlayService.KEY_POSITION, OverlayService.POS_TOP_RIGHT)

        val configCard = card("🎯 HUD SCALE & CORNER POSITION", YELLOW)
        val scaleLabel = label("HUD Scale: ${String.format("%.2f", currentScale)}x", GREEN, 11f)
        configCard.addView(scaleLabel)

        val seekBar = SeekBar(this).apply {
            max = 125
            progress = ((currentScale - 0.25f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    scaleLabel.text = "HUD Scale: ${String.format("%.2f", 0.25f + p / 100f)}x"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val scale = 0.25f + (s?.progress ?: 75) / 100f
                    prefs.edit().putFloat(OverlayService.KEY_SCALE, scale).apply()
                    restartOverlay()
                }
            })
        }
        configCard.addView(seekBar)

        val posGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val rbRight = radioBtn("Top-Right", currentPos == OverlayService.POS_TOP_RIGHT)
        val rbLeft  = radioBtn("Top-Left",  currentPos == OverlayService.POS_TOP_LEFT)
        posGroup.addView(rbRight)
        posGroup.addView(rbLeft)
        posGroup.setOnCheckedChangeListener { _, id ->
            val rb = posGroup.findViewById<RadioButton>(id)
            if (rb != null && rb.isPressed) {
                val pos = if (id == rbLeft.id) OverlayService.POS_TOP_LEFT else OverlayService.POS_TOP_RIGHT
                prefs.edit().putString(OverlayService.KEY_POSITION, pos).apply()
                restartOverlay()
            }
        }
        configCard.addView(posGroup)
        tabHudContent.addView(configCard)
        tabHudContent.addView(gap())

        // Card: Alignment Calibrator
        val alignCard = card("📍 HUD ALIGNMENT CALIBRATOR", YELLOW)
        val offsetLabel = label(
            "Offset: X=${prefs.getInt(OverlayService.KEY_X_OFFSET, 40)}, Y=${prefs.getInt(OverlayService.KEY_Y_OFFSET, 40)}",
            GREEN, 11f)
        alignCard.addView(offsetLabel)

        fun nudge(dx: Int, dy: Int) {
            val nx = (prefs.getInt(OverlayService.KEY_X_OFFSET, 40) + dx).coerceIn(-500, 1500)
            val ny = (prefs.getInt(OverlayService.KEY_Y_OFFSET, 40) + dy).coerceIn(-500, 1500)
            prefs.edit().putInt(OverlayService.KEY_X_OFFSET, nx).putInt(OverlayService.KEY_Y_OFFSET, ny).apply()
            offsetLabel.text = "Offset: X=$nx, Y=$ny"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_POSITION).apply { setPackage(packageName) })
        }

        val dpad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        dpad.addView(btn("▲ UP", YELLOW) { nudge(0, -10) })
        val lr = row().apply { gravity = Gravity.CENTER_HORIZONTAL }
        lr.addView(btn("◄ LEFT", YELLOW) { nudge(-10, 0) })
        lr.addView(btn("RIGHT ►", YELLOW) { nudge(10, 0) })
        dpad.addView(lr)
        dpad.addView(btn("▼ DOWN", YELLOW) { nudge(0, 10) })
        alignCard.addView(dpad)

        alignCard.addView(btn("[ RESET (40, 40) ]", RED) {
            prefs.edit().putInt(OverlayService.KEY_X_OFFSET, 40).putInt(OverlayService.KEY_Y_OFFSET, 40).apply()
            offsetLabel.text = "Offset: X=40, Y=40"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_POSITION).apply { setPackage(packageName) })
        })
        tabHudContent.addView(alignCard)
        tabHudContent.addView(gap())

        // HUD Action Buttons
        tabHudContent.addView(btn("⚡ START CYBERPUNK HUD", YELLOW, textBlack = true) { startOverlayService() })
        tabHudContent.addView(btn("⏹  STOP CYBERPUNK HUD", RED) { stopOverlayService() })
        root.addView(tabHudContent)

        // ══════════════════════════════════════════════════════════════════════
        // ── TAB 1: HEAD MOUSE CONTENT ─────────────────────────────────────────
        // ══════════════════════════════════════════════════════════════════════
        tabHeadMouseContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        // Card: Enable Head Cursor CheckBox
        val mouseToggleCard = card("🖱️ HEAD MOUSE CONTROLS", CYAN)
        cbMouseMode = CheckBox(this).apply {
            text = "  Enable Head Cursor (Mouse Mode)"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            isChecked = prefs.getBoolean(OverlayService.KEY_MOUSE_MODE_ENABLED, true)
            setOnCheckedChangeListener { v, checked ->
                if (v.isPressed) {
                    prefs.edit().putBoolean(OverlayService.KEY_MOUSE_MODE_ENABLED, checked).apply()
                    restartOverlay()
                }
            }
        }
        mouseToggleCard.addView(cbMouseMode)
        tabHeadMouseContent.addView(mouseToggleCard)
        tabHeadMouseContent.addView(gap())

        // Card: Head Cursor Sensitivity
        val tuningCard = card("⚡ HEAD CURSOR SENSITIVITY", GREEN)
        val currentSens = prefs.getFloat(OverlayService.KEY_HEAD_SENSITIVITY, 0.45f)
        val sensLabel = label("Head Sensitivity: ${String.format("%.2f", currentSens)}x", YELLOW, 11f)
        tuningCard.addView(sensLabel)

        val sensSeekBar = SeekBar(this).apply {
            max = 100
            progress = (((currentSens - 0.1f) / (4.0f - 0.1f)) * 100).toInt().coerceIn(0, 100)

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val sensCalc = 0.1f + (p / 100.0f) * (4.0f - 0.1f)
                    sensLabel.text = "Head Sensitivity: ${String.format("%.2f", sensCalc)}x"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val p = s?.progress ?: 50
                    val sensCalc = 0.1f + (p / 100.0f) * (4.0f - 0.1f)
                    prefs.edit().putFloat(OverlayService.KEY_HEAD_SENSITIVITY, sensCalc).apply()
                }
            })
        }
        tuningCard.addView(sensSeekBar)
        tabHeadMouseContent.addView(tuningCard)
        tabHeadMouseContent.addView(gap())

        // Card: Head Movement Strategy
        val strategyCard = card("🎮 HEAD MOVEMENT STRATEGY", GREEN)
        val strategies = listOf(
            OverlayService.STRATEGY_LINEAR     to "Linear (Direct 1:1)",
            OverlayService.STRATEGY_LOG        to "Logarithmic (Micro-Precision & Fast Sweep)",
            OverlayService.STRATEGY_NLOG       to "Negative Log (Exponential Power Curve)",
            OverlayService.STRATEGY_FILTERED   to "Filtered (1€ Adaptive Low-Pass Jitter Filter)",
            OverlayService.STRATEGY_DERIVATIVE to "Derivative (Velocity Acceleration Gain)"
        )
        val currentStrategy = prefs.getString(OverlayService.KEY_MOVEMENT_STRATEGY, OverlayService.STRATEGY_LINEAR)
            ?: OverlayService.STRATEGY_LINEAR
        val rgStrategy = radioGroup(strategies, currentStrategy) { chosen ->
            prefs.edit().putString(OverlayService.KEY_MOVEMENT_STRATEGY, chosen).apply()
            restartOverlay()
        }
        strategyCard.addView(rgStrategy)
        tabHeadMouseContent.addView(strategyCard)
        tabHeadMouseContent.addView(gap())

        // Card: Volume Buttons Action Mapper
        val mapperCard = card("🕹️ VOLUME BUTTONS ACTION MAPPER", YELLOW)
        val pressActions = listOf(
            OverlayService.ACTION_VAL_LEFT_CLICK  to "Execute Left Click",
            OverlayService.ACTION_VAL_RIGHT_CLICK to "Execute Right Click"
        )

        mapperCard.addView(label("Volume Up Press Action:", YELLOW, 11f))
        val rgUp = radioGroup(pressActions,
            prefs.getString(OverlayService.KEY_VOL_UP_ACTION, OverlayService.ACTION_VAL_LEFT_CLICK) ?: "") { chosen ->
            prefs.edit().putString(OverlayService.KEY_VOL_UP_ACTION, chosen).apply()
            restartOverlay()
        }
        mapperCard.addView(rgUp)
        mapperCard.addView(gap())

        mapperCard.addView(label("Volume Down Press Action:", YELLOW, 11f))
        val rgDown = radioGroup(pressActions,
            prefs.getString(OverlayService.KEY_VOL_DOWN_ACTION, OverlayService.ACTION_VAL_RIGHT_CLICK) ?: "") { chosen ->
            prefs.edit().putString(OverlayService.KEY_VOL_DOWN_ACTION, chosen).apply()
            restartOverlay()
        }
        mapperCard.addView(rgDown)
        mapperCard.addView(gap())

        val holdActions = listOf(
            OverlayService.ACTION_VAL_SCROLL to "Vertical Head Scroll (Hold + Tilt Head)",
            OverlayService.ACTION_VAL_NONE   to "None (disabled)"
        )
        mapperCard.addView(label("Volume Down Hold Action:", YELLOW, 11f))
        val rgHold = radioGroup(holdActions,
            prefs.getString(OverlayService.KEY_VOL_DOWN_HOLD_ACTION, OverlayService.ACTION_VAL_SCROLL) ?: "") { chosen ->
            prefs.edit().putString(OverlayService.KEY_VOL_DOWN_HOLD_ACTION, chosen).apply()
            restartOverlay()
        }
        mapperCard.addView(rgHold)
        tabHeadMouseContent.addView(mapperCard)
        root.addView(tabHeadMouseContent)

        // ══════════════════════════════════════════════════════════════════════
        // ── TAB 2: DIAGNOSE CONTENT ───────────────────────────────────────────
        // ══════════════════════════════════════════════════════════════════════
        tabDiagnoseContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        // Card: Permissions
        val accessCard = card("⚡ UPFRONT SYSTEM ACCESS STATUS", GREEN)
        btnPermissionStatus = btn("🔄 SCAN & ACQUIRE ALL REQUIRED PERMISSIONS", GREEN) {
            checkAndRequestAllPermissions(forceTrigger = true)
        }
        accessCard.addView(btnPermissionStatus)
        tabDiagnoseContent.addView(accessCard)
        tabDiagnoseContent.addView(gap())

        // Card: Diagnostics & Log Console
        val devCard = card("👓 XREAL 1s DIAGNOSTICS & SYSTEM MONITOR", GREEN)
        devCard.addView(btn("🔎 OPEN FULL DIAGNOSTICS & SYSTEM LOGS", GREEN) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        })
        tabDiagnoseContent.addView(devCard)
        root.addView(tabDiagnoseContent)

        // Initialize active tab styling
        switchTab(0)

        val sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshUiReceiver,
                IntentFilter("com.example.dexoverlay.REFRESH_UI"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshUiReceiver, IntentFilter("com.example.dexoverlay.REFRESH_UI"))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex

        tabHudContent.visibility       = if (tabIndex == 0) View.VISIBLE else View.GONE
        tabHeadMouseContent.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        tabDiagnoseContent.visibility  = if (tabIndex == 2) View.VISIBLE else View.GONE

        // Update tab button highlight colors
        btnTabHud.setBackgroundColor(Color.parseColor(if (tabIndex == 0) YELLOW else DIM))
        btnTabHud.setTextColor(if (tabIndex == 0) Color.BLACK else Color.parseColor(YELLOW))

        btnTabHeadMouse.setBackgroundColor(Color.parseColor(if (tabIndex == 1) CYAN else DIM))
        btnTabHeadMouse.setTextColor(if (tabIndex == 1) Color.BLACK else Color.parseColor(CYAN))

        btnTabDiagnose.setBackgroundColor(Color.parseColor(if (tabIndex == 2) GREEN else DIM))
        btnTabDiagnose.setTextColor(if (tabIndex == 2) Color.BLACK else Color.parseColor(GREEN))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(refreshUiReceiver) } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestAllPermissions(forceTrigger = false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun checkAndRequestAllPermissions(forceTrigger: Boolean) {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityEnabled()
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val glassesDevice = usbManager.deviceList.values.find { d ->
            d.vendorId == 0x3318 || d.productId in intArrayOf(0x0436, 0x0425, 0x0429)
        }
        val hasUsb = glassesDevice == null || usbManager.hasPermission(glassesDevice)

        if (hasOverlay && hasAccessibility && hasUsb) {
            statusTag.text = "[ ACTIVE // ALL ACCESS OK ]"
            statusTag.setTextColor(Color.parseColor(GREEN))
            btnPermissionStatus.text = "✅ ALL PERMISSIONS ACQUIRED"
            btnPermissionStatus.setBackgroundColor(Color.parseColor(DIM))
            btnPermissionStatus.setTextColor(Color.parseColor(GREEN))
        } else {
            statusTag.text = "[ PENDING AUTHORIZATION ]"
            statusTag.setTextColor(Color.parseColor(RED))
            btnPermissionStatus.text = "⚠️ TAP TO GRANT MISSING ACCESS"
            btnPermissionStatus.setBackgroundColor(Color.parseColor(DIM))
            btnPermissionStatus.setTextColor(Color.parseColor(RED))
        }

        if (forceTrigger) {
            if (!hasOverlay) { requestOverlayPermission(); return }
            if (!hasAccessibility) {
                Toast.makeText(this, "Enable DeX Head Cursor in Accessibility settings", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {}
                return
            }
            if (!hasUsb && glassesDevice != null) {
                requestUsbPermission(glassesDevice); return
            }
            if (hasOverlay) startOverlayService()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, HeadCursorAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val cn = ComponentName.unflattenFromString(splitter.next())
            if (cn != null && cn == expected) return true
        }
        return false
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val pi = android.app.PendingIntent.getBroadcast(this, 0,
            Intent(XrealOneImuManager.ACTION_USB_PERMISSION).apply { setPackage(packageName) }, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")), 101)
        }
    }

    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission(); return
        }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun restartOverlay() {
        stopOverlayService()
        startOverlayService()
    }

    // ── View factory helpers ──────────────────────────────────────────────────

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun gap() = TextView(this).apply { text = " " }

    private fun border(color: String, stroke: Int = 1) = GradientDrawable().apply {
        setColor(Color.parseColor(BG_CARD))
        setStroke(stroke, Color.parseColor(color))
        cornerRadius = 3f
    }

    private fun label(text: String, color: String, size: Float,
                      bold: Boolean = false, weight: Float = 0f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            typeface = Typeface.MONOSPACE
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (weight > 0f) layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    private fun card(title: String, accentColor: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = border(accentColor)
            addView(label(title, accentColor, 12f, bold = true).apply {
                setPadding(0, 0, 0, 8)
            })
        }

    private fun btn(text: String, color: String, textBlack: Boolean = false,
                    onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            textSize = 11f
            if (textBlack) {
                setBackgroundColor(Color.parseColor(color))
                setTextColor(Color.BLACK)
            } else {
                setBackgroundColor(Color.parseColor(DIM))
                setTextColor(Color.parseColor(color))
            }
            setOnClickListener { onClick() }
        }

    private fun radioBtn(text: String, checked: Boolean) = RadioButton(this).apply {
        id = android.view.View.generateViewId()
        this.text = text
        isChecked = checked
        setTextColor(Color.parseColor(GREEN))
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setPadding(0, 0, 24, 0)
    }

    private fun radioGroup(options: List<Pair<String,String>>, current: String,
                           onPick: (String) -> Unit): RadioGroup {
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        options.forEach { (value, label) ->
            val rb = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = label
                isChecked = (value == current)
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                textSize = 10f
            }
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { group, id ->
            val rb = group.findViewById<RadioButton>(id)
            if (rb != null && rb.isPressed) {
                val idx = group.indexOfChild(rb)
                if (idx in options.indices) onPick(options[idx].first)
            }
        }
        return rg
    }
}
