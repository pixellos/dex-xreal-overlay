package com.example.dexoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force stop running OverlayService to clean up any cached WindowManager views
        stopOverlayService()

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.parseColor("#080A0F")) // Cyberpunk Cyberdeck Dark
        }

        // --- Cyberdeck Header ---
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121824"))
                setStroke(2, Color.parseColor("#00E5FF"))
                cornerRadius = 4f
            }
        }

        val subtitleText = TextView(this).apply {
            text = "⚡ CYBERDECK v2.077 // AVAILABLE CONFIGS:"
            textSize = 12f
            setTextColor(Color.parseColor("#FF0055")) // Neon Pink
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        headerCard.addView(subtitleText)

        val titleText = TextView(this).apply {
            text = "XREAL 1s & DeX Minimal HUD"
            textSize = 22f
            setTextColor(Color.parseColor("#FFE600")) // Cyberpunk Yellow
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        headerCard.addView(titleText)
        rootLayout.addView(headerCard)

        val spacer1 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer1)

        // --- Quickhack Card 1: HUD Scale Setting ---
        val currentScale = prefs.getFloat(OverlayService.KEY_SCALE, 1.0f)
        val scaleCard = createQuickhackCard("SYSTEM HUD SCALE", "READY", "1")

        val scaleLabel = TextView(this).apply {
            text = "HUD Size Scale: ${String.format("%.2f", currentScale)}x"
            textSize = 13f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 8, 24, 8)
        }
        scaleCard.addView(scaleLabel)

        val seekBar = SeekBar(this).apply {
            max = 125 // 0.25 to 1.50 (1.25 range)
            progress = ((currentScale - 0.25f) * 100).toInt()
            setPadding(24, 0, 24, 16)
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
        scaleCard.addView(seekBar)
        rootLayout.addView(scaleCard)

        val spacer2 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer2)

        // --- Quickhack Card 2: Corner Position ---
        val currentPos = prefs.getString(OverlayService.KEY_POSITION, OverlayService.POS_TOP_RIGHT)
        val posCard = createQuickhackCard("DISPLAY CORNER POSITION", "TRACEABLE", "2")

        val posGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(24, 8, 24, 16)
        }

        val rbTopRight = RadioButton(this).apply {
            text = "[ TOP-RIGHT ]"
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            isChecked = currentPos == OverlayService.POS_TOP_RIGHT
        }
        val rbTopLeft = RadioButton(this).apply {
            text = "[ TOP-LEFT ]"
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            isChecked = currentPos == OverlayService.POS_TOP_LEFT
        }

        posGroup.addView(rbTopRight)
        posGroup.addView(rbTopLeft)

        posGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedPos = if (checkedId == rbTopLeft.id) OverlayService.POS_TOP_LEFT else OverlayService.POS_TOP_RIGHT
            prefs.edit().putString(OverlayService.KEY_POSITION, selectedPos).apply()
            restartOverlayServiceIfRunning()
        }
        posCard.addView(posGroup)
        rootLayout.addView(posCard)

        val spacer3 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer3)

        // --- Quickhack Card 3: HUD Alignment Calibrator (Joystick) ---
        val xOff = prefs.getInt(OverlayService.KEY_X_OFFSET, 40)
        val yOff = prefs.getInt(OverlayService.KEY_Y_OFFSET, 40)
        val calibrationCard = createQuickhackCard("HUD ALIGNMENT CALIBRATOR", "CALIBRATING", "3")

        val offsetLabel = TextView(this).apply {
            text = "Offset: X=$xOff, Y=$yOff"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 8, 24, 0)
        }
        calibrationCard.addView(offsetLabel)

        val joystickPad = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A121E"))
                setStroke(1, Color.parseColor("#00E5FF"))
                cornerRadius = 4f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            ).apply { setMargins(24, 16, 24, 16) }

            var lastX = 0f
            var lastY = 0f

            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        lastX = event.x
                        lastY = event.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - lastX).toInt() / 2
                        val dy = (event.y - lastY).toInt() / 2
                        
                        if (dx != 0 || dy != 0) {
                            val newX = (prefs.getInt(OverlayService.KEY_X_OFFSET, 40) + dx).coerceIn(-500, 1500)
                            val newY = (prefs.getInt(OverlayService.KEY_Y_OFFSET, 40) + dy).coerceIn(-500, 1500)
                            
                            prefs.edit().apply {
                                putInt(OverlayService.KEY_X_OFFSET, newX)
                                putInt(OverlayService.KEY_Y_OFFSET, newY)
                            }.apply()
                            
                            offsetLabel.text = "Offset: X=$newX, Y=$newY"
                            restartOverlayServiceIfRunning()
                            
                            lastX = event.x
                            lastY = event.y
                        }
                    }
                }
                true
            }
        }
        calibrationCard.addView(joystickPad)

        val btnReset = Button(this).apply {
            text = "[ RESET ALIGNMENT ]"
            textSize = 10f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#FF0055"))
            setOnClickListener {
                prefs.edit().apply {
                    putInt(OverlayService.KEY_X_OFFSET, 40)
                    putInt(OverlayService.KEY_Y_OFFSET, 40)
                }.apply()
                offsetLabel.text = "Offset: X=40, Y=40"
                restartOverlayServiceIfRunning()
            }
        }
        calibrationCard.addView(btnReset)

        rootLayout.addView(calibrationCard)

        val spacer4_perm = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer4_perm)

        // --- Permission Button ---
        val btnPermission = Button(this).apply {
            text = "[ GRANT OVERLAY PERMISSION ]"
            setBackgroundColor(Color.parseColor("#151D2A"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 16, 0, 16)
            setOnClickListener { checkAndRequestOverlayPermission() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
        }
        rootLayout.addView(btnPermission)

        val spacer4_exec = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer4_exec)

        // --- Cyberware Action Buttons ---
        val btnStart = Button(this).apply {
            text = "⚡ [ EXECUTE: START CYBERPUNK HUD ]"
            setBackgroundColor(Color.parseColor("#FFE600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 16)
            setOnClickListener { startOverlayService() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
        }
        rootLayout.addView(btnStart)

        val spacer5 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer5)

        val btnStop = Button(this).apply {
            text = "⏹️ [ TERMINATE: STOP HUD ]"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 16)
            setOnClickListener { stopOverlayService() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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

    private fun createQuickhackCard(title: String, tag: String, ramCost: String = "0"): LinearLayout {
        val cyan = Color.parseColor("#00E5FF")
        val darkBlue = Color.parseColor("#0A121E")

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // Left Content Box (Notched Look simulated with border)
        val contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(darkBlue)
                setStroke(2, cyan)
                cornerRadius = 2f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val textStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = title.uppercase()
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        textStack.addView(titleView)

        val tagBox = TextView(this).apply {
            text = tag.uppercase()
            textSize = 9f
            setTextColor(cyan)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setStroke(1, cyan)
                cornerRadius = 2f
            }
            setPadding(8, 2, 8, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 0) }
        }
        textStack.addView(tagBox)
        contentBox.addView(textStack)

        // RAM Cost
        val ramView = TextView(this).apply {
            text = ramCost
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(16, 0, 16, 0)
        }
        contentBox.addView(ramView)

        // Far Right Icon Box
        val iconView = TextView(this).apply {
            text = "⚡"
            textSize = 16f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                setStroke(2, cyan)
                cornerRadius = 2f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(8, 0, 0, 0) }
        }

        mainRow.addView(contentBox)
        mainRow.addView(iconView)
        cardContainer.addView(mainRow)

        return cardContainer
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
}
