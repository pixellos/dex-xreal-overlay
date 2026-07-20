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

    // ── Cyberpunk palette ─────────────────────────────────────────────────────
    companion object {
        const val BG        = "#000000"
        const val BG_CARD   = "#0A0A0A"
        const val GREEN     = "#00FF66"
        const val YELLOW    = "#FFE600"
        const val RED       = "#FF0055"
        const val DIM       = "#1A1A1A"

        const val ACTION_LOG_UPDATE = "com.example.dexoverlay.LOG_UPDATE"
        const val EXTRA_LOG_MSG     = "log_msg"
    }

    private lateinit var statusTag: TextView
    private lateinit var btnPermissionStatus: Button
    private lateinit var cbMouseMode: CheckBox

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
            setPadding(28, 20, 28, 28)
            setBackgroundColor(Color.parseColor(BG))
        }

        // ── Header ────────────────────────────────────────────────────────────
        val header = row().apply {
            background = border(GREEN, 2)
            setPadding(16, 12, 16, 12)
        }
        header.addView(label("CYBERDECK v556 // HUD OS", YELLOW, 14f, bold = true, weight = 1f))
        statusTag = label("[ PENDING ]", RED, 10f)
        header.addView(statusTag)
        root.addView(header)
        root.addView(gap())

        // ── Card: Permissions ─────────────────────────────────────────────────
        val accessCard = card("⚡ UPFRONT SYSTEM ACCESS STATUS", GREEN)
        btnPermissionStatus = btn("🔄 SCAN & ACQUIRE ALL REQUIRED PERMISSIONS", GREEN) {
            checkAndRequestAllPermissions(forceTrigger = true)
        }
        accessCard.addView(btnPermissionStatus)
        root.addView(accessCard)
        root.addView(gap())

        // ── Card: Diagnostics ─────────────────────────────────────────────────
        val devCard = card("👓 XREAL 1s DIAGNOSTICS & CONTROLS", GREEN)
        devCard.addView(btn("🔎 OPEN FULL DIAGNOSTICS & SYSTEM LOGS", GREEN) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        })
        root.addView(devCard)
        root.addView(gap())

        // ── Card: Volume Buttons Action Mapper ────────────────────────────────
        val mapperCard = card("🕹️ VOLUME BUTTONS ACTION MAPPER", YELLOW)

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
        mapperCard.addView(cbMouseMode)
        mapperCard.addView(gap())

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
        mapperCard.addView(gap())

        val tripleActions = listOf(
            OverlayService.ACTION_VAL_HOME to "Send HOME Key (Desktop / Home Screen)",
            OverlayService.ACTION_VAL_NONE to "None (disabled)"
        )
        mapperCard.addView(label("Volume Down Triple-Click Action:", YELLOW, 11f))
        val rgTriple = radioGroup(tripleActions,
            prefs.getString(OverlayService.KEY_VOL_DOWN_TRIPLE_ACTION, OverlayService.ACTION_VAL_HOME) ?: "") { chosen ->
            prefs.edit().putString(OverlayService.KEY_VOL_DOWN_TRIPLE_ACTION, chosen).apply()
            restartOverlay()
        }
        mapperCard.addView(rgTriple)
        mapperCard.addView(gap())

        val quadActions = listOf(
            OverlayService.ACTION_VAL_TOGGLE_MOUSE to "Toggle Head Cursor ON/OFF (Crosshair Toggle)",
            OverlayService.ACTION_VAL_NONE         to "None (disabled)"
        )
        mapperCard.addView(label("Volume Down Quadruple-Click Action:", YELLOW, 11f))
        val rgQuad = radioGroup(quadActions,
            prefs.getString(OverlayService.KEY_VOL_DOWN_QUAD_ACTION, OverlayService.ACTION_VAL_TOGGLE_MOUSE) ?: "") { chosen ->
            prefs.edit().putString(OverlayService.KEY_VOL_DOWN_QUAD_ACTION, chosen).apply()
            restartOverlay()
        }
        mapperCard.addView(rgQuad)

        root.addView(mapperCard)
        root.addView(gap())

        // ── Card: Logarithmic Head Sensitivity Knob ────────────────────────────
        val tuningCard = card("⚡ HEAD CURSOR SENSITIVITY (LOG FILTERED)", GREEN)

        val logMin = Math.log10(0.1) // -1.0
        val logMax = Math.log10(4.0) // 0.602
        val currentSens = prefs.getFloat(OverlayService.KEY_HEAD_SENSITIVITY, 0.45f)
        val sensLabel = label("Head Sensitivity: ${String.format("%.2f", currentSens)}x (Log Filtered)", YELLOW, 11f)
        tuningCard.addView(sensLabel)

        val sensSeekBar = SeekBar(this).apply {
            max = 100
            val logVal = Math.log10(currentSens.toDouble().coerceIn(0.1, 4.0))
            progress = (((logVal - logMin) / (logMax - logMin)) * 100).toInt().coerceIn(0, 100)

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val logValCalc = logMin + (p / 100.0) * (logMax - logMin)
                    val sensCalc = Math.pow(10.0, logValCalc).toFloat()
                    sensLabel.text = "Head Sensitivity: ${String.format("%.2f", sensCalc)}x (Log Filtered)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val p = s?.progress ?: 50
                    val logValCalc = logMin + (p / 100.0) * (logMax - logMin)
                    val sensCalc = Math.pow(10.0, logValCalc).toFloat()
                    prefs.edit().putFloat(OverlayService.KEY_HEAD_SENSITIVITY, sensCalc).apply()
                }
            })
        }
        tuningCard.addView(sensSeekBar)
        root.addView(tuningCard)
        root.addView(gap())

        // ── Card: HUD Scale & Position ────────────────────────────────────────
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
        root.addView(configCard)
        root.addView(gap())

        // ── Card: Alignment ───────────────────────────────────────────────────
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
        root.addView(alignCard)
        root.addView(gap())

        // ── Action Buttons ────────────────────────────────────────────────────
        root.addView(btn("⚡ START CYBERPUNK HUD", YELLOW, textBlack = true) { startOverlayService() })
        root.addView(btn("⏹  STOP CYBERPUNK HUD", RED) { stopOverlayService() })

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
