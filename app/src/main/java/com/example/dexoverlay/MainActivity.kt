package com.example.dexoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        stopOverlayService()

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 20, 28, 28)
            setBackgroundColor(Color.parseColor("#03060B")) // Sleek Cyberpunk Netrunner Dark
        }

        // --- Streamlined Cyberdeck Header ---
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#09111E"))
                setStroke(2, Color.parseColor("#FF0055"))
                cornerRadius = 3f
            }
        }

        val headerText = TextView(this).apply {
            text = "CYBERDECK v556 // HUD OS"
            textSize = 14f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCard.addView(headerText)

        val statusTag = TextView(this).apply {
            text = "[ DEVELOPER SYSTEM ]"
            textSize = 10f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
        }
        headerCard.addView(statusTag)
        rootLayout.addView(headerCard)

        val spacer1 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer1)

        val devCard = createCompactCard("👓 XREAL 1s DIAGNOSTICS & CONTROLS", "#00E5FF")

        val btnDiagnostics = Button(this).apply {
            text = "🔎 OPEN FULL DIAGNOSTICS & SYSTEM LOGS"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                val intent = Intent(this@MainActivity, DiagnosticsActivity::class.java)
                startActivity(intent)
            }
        }
        devCard.addView(btnDiagnostics)
        rootLayout.addView(devCard)

        val spacer_mode = TextView(this).apply { text = " " }
        rootLayout.addView(spacer_mode)

        // --- Card 2: Quick Action Selection ---
        val enableHeadCursor = prefs.getBoolean(OverlayService.KEY_ENABLE_HEAD_CURSOR, true)
        val singleTapAction = prefs.getString(OverlayService.KEY_SINGLE_TAP_ACTION, OverlayService.SINGLE_TAP_ACTION_CLICK) ?: OverlayService.SINGLE_TAP_ACTION_CLICK
        val actionCard = createCompactCard("🔘 QUICK ACTION SELECTION", "#00E5FF")

        val cbHeadCursor = CheckBox(this).apply {
            text = " Enable Motion Head Cursor"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            isChecked = enableHeadCursor
            setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.isPressed) {
                    prefs.edit().putBoolean(OverlayService.KEY_ENABLE_HEAD_CURSOR, isChecked).apply()
                    restartOverlayServiceIfRunning()
                }
            }
        }
        actionCard.addView(cbHeadCursor)

        val btnAccessibility = Button(this).apply {
            text = "👆 ENABLE ACCESSIBILITY FOR SYSTEM MOUSE CLICKS"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(this@MainActivity, "Enable 'DeX Head Cursor Accessibility Driver'", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
        actionCard.addView(btnAccessibility)

        val tapGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val rbClick = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Execute Click at Head Cursor Position"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isChecked = (singleTapAction == OverlayService.SINGLE_TAP_ACTION_CLICK)
        }
        val rbToggleHud = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Toggle HUD Overlay On/Off"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isChecked = (singleTapAction == OverlayService.SINGLE_TAP_ACTION_TOGGLE_HUD)
        }
        val rbRecenter = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Recenter Cursor to Center"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isChecked = (singleTapAction == OverlayService.SINGLE_TAP_ACTION_RECENTER)
        }

        tapGroup.addView(rbClick)
        tapGroup.addView(rbToggleHud)
        tapGroup.addView(rbRecenter)

        tapGroup.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            if (checkedRb != null && checkedRb.isPressed) {
                val selectedAction = when (checkedId) {
                    rbToggleHud.id -> OverlayService.SINGLE_TAP_ACTION_TOGGLE_HUD
                    rbRecenter.id -> OverlayService.SINGLE_TAP_ACTION_RECENTER
                    else -> OverlayService.SINGLE_TAP_ACTION_CLICK
                }
                prefs.edit().putString(OverlayService.KEY_SINGLE_TAP_ACTION, selectedAction).apply()
                Toast.makeText(this, "Quick Action Updated!", Toast.LENGTH_SHORT).show()
                restartOverlayServiceIfRunning()
            }
        }
        actionCard.addView(tapGroup)
        rootLayout.addView(actionCard)

        val spacer2 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer2)

        // --- Card 3: HUD Customization (Scale & Position) ---
        val currentScale = prefs.getFloat(OverlayService.KEY_SCALE, 1.0f)
        val currentPos = prefs.getString(OverlayService.KEY_POSITION, OverlayService.POS_TOP_RIGHT)

        val configCard = createCompactCard("🎯 HUD SCALE & CORNER POSITION", "#00E5FF")

        val scaleLabel = TextView(this).apply {
            text = "HUD Size Scale: ${String.format("%.2f", currentScale)}x"
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
        }
        configCard.addView(scaleLabel)

        val seekBar = SeekBar(this).apply {
            max = 125
            progress = ((currentScale - 0.25f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val newScale = 0.25f + (progress / 100f)
                    scaleLabel.text = "HUD Size Scale: ${String.format("%.2f", newScale)}x"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: 75
                    val finalScale = 0.25f + (progress / 100f)
                    prefs.edit().putFloat(OverlayService.KEY_SCALE, finalScale).apply()
                    restartOverlayServiceIfRunning()
                }
            })
        }
        configCard.addView(seekBar)

        val posGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val rbTopRight = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Top-Right  "
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            isChecked = (currentPos == OverlayService.POS_TOP_RIGHT)
        }
        val rbTopLeft = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Top-Left"
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            isChecked = (currentPos == OverlayService.POS_TOP_LEFT)
        }

        posGroup.addView(rbTopRight)
        posGroup.addView(rbTopLeft)

        posGroup.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            if (checkedRb != null && checkedRb.isPressed) {
                val selectedPos = if (checkedId == rbTopLeft.id) OverlayService.POS_TOP_LEFT else OverlayService.POS_TOP_RIGHT
                prefs.edit().putString(OverlayService.KEY_POSITION, selectedPos).apply()
                restartOverlayServiceIfRunning()
            }
        }
        configCard.addView(posGroup)
        rootLayout.addView(configCard)

        val spacer3 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer3)

        // --- Card 4: Alignment Calibrator ---
        val xOff = prefs.getInt(OverlayService.KEY_X_OFFSET, 40)
        val yOff = prefs.getInt(OverlayService.KEY_Y_OFFSET, 40)

        val alignCard = createCompactCard("📍 HUD ALIGNMENT CALIBRATOR", "#00E5FF")

        val offsetLabel = TextView(this).apply {
            text = "Offset: X=$xOff, Y=$yOff"
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
        }
        alignCard.addView(offsetLabel)

        val dpadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun updateOffset(dx: Int, dy: Int) {
            val curX = prefs.getInt(OverlayService.KEY_X_OFFSET, 40)
            val curY = prefs.getInt(OverlayService.KEY_Y_OFFSET, 40)
            val newX = (curX + dx).coerceIn(-500, 1500)
            val newY = (curY + dy).coerceIn(-500, 1500)
            prefs.edit().apply {
                putInt(OverlayService.KEY_X_OFFSET, newX)
                putInt(OverlayService.KEY_Y_OFFSET, newY)
            }.apply()
            offsetLabel.text = "Offset: X=$newX, Y=$newY"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_POSITION))
        }

        val btnUp = Button(this).apply {
            text = "▲ UP"
            setBackgroundColor(Color.parseColor("#09111E"))
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setOnClickListener { updateOffset(0, -10) }
        }
        dpadLayout.addView(btnUp)

        val lrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val btnLeft = Button(this).apply {
            text = "◄ LEFT"
            setBackgroundColor(Color.parseColor("#09111E"))
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setOnClickListener { updateOffset(-10, 0) }
        }

        val btnRight = Button(this).apply {
            text = "RIGHT ►"
            setBackgroundColor(Color.parseColor("#09111E"))
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setOnClickListener { updateOffset(10, 0) }
        }

        lrRow.addView(btnLeft)
        lrRow.addView(btnRight)
        dpadLayout.addView(lrRow)

        val btnDown = Button(this).apply {
            text = "▼ DOWN"
            setBackgroundColor(Color.parseColor("#09111E"))
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setOnClickListener { updateOffset(0, 10) }
        }
        dpadLayout.addView(btnDown)
        alignCard.addView(dpadLayout)

        val btnReset = Button(this).apply {
            text = "[ RESET ALIGNMENT (40, 40) ]"
            textSize = 9f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#FF0055"))
            setOnClickListener {
                prefs.edit().apply {
                    putInt(OverlayService.KEY_X_OFFSET, 40)
                    putInt(OverlayService.KEY_Y_OFFSET, 40)
                }.apply()
                offsetLabel.text = "Offset: X=40, Y=40"
                sendBroadcast(Intent(OverlayService.ACTION_UPDATE_POSITION))
            }
        }
        alignCard.addView(btnReset)
        rootLayout.addView(alignCard)

        val spacer4 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer4)

        // --- Action Buttons ---
        val btnPermission = Button(this).apply {
            text = "[ GRANT OVERLAY PERMISSION ]"
            setBackgroundColor(Color.parseColor("#121B2C"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setOnClickListener { checkAndRequestOverlayPermission() }
        }
        rootLayout.addView(btnPermission)

        val btnStart = Button(this).apply {
            text = "⚡ [ START CYBERPUNK HUD ]"
            setBackgroundColor(Color.parseColor("#FFE600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { startOverlayService() }
        }
        rootLayout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "⏹️ [ STOP CYBERPUNK HUD ]"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { stopOverlayService() }
        }
        rootLayout.addView(btnStop)

        val scrollView = android.widget.ScrollView(this).apply {
            addView(rootLayout)
        }
        setContentView(scrollView)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun createCompactCard(title: String, borderColorHex: String): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#09111E"))
                setStroke(1, Color.parseColor(borderColorHex))
                cornerRadius = 2f
            }
        }

        val cardTitle = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.parseColor(borderColorHex))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        container.addView(cardTitle)
        return container
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 101)
            } else {
                Toast.makeText(this, "Permission Already Granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restartOverlayServiceIfRunning() {
        stopOverlayService()
        startOverlayService()
    }

    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission first!", Toast.LENGTH_LONG).show()
            checkAndRequestOverlayPermission()
            return
        }

        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Cyberpunk HUD Active!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
    }

    companion object {
        const val ACTION_LOG_UPDATE = "com.example.dexoverlay.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "log_msg"
    }
}
