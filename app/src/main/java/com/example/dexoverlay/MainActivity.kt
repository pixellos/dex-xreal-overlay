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
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#04070D")) // In-Game Netrunner Dark
        }

        // --- Cyberdeck Top Bar (Cyberpunk 2077 In-Game Header) ---
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#09111E"))
                setStroke(2, Color.parseColor("#00E5FF"))
                cornerRadius = 2f
            }
        }

        val subtitleText = TextView(this).apply {
            text = "CYBERDECK v 552.322"
            textSize = 11f
            setTextColor(Color.parseColor("#FF0055")) // Cyberpunk Red Accent
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        headerCard.addView(subtitleText)

        val titleText = TextView(this).apply {
            text = "AVAILABLE QUICKHACKS:"
            textSize = 18f
            setTextColor(Color.parseColor("#FFE600")) // Cyberpunk Yellow
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 2, 0, 0)
        }
        headerCard.addView(titleText)
        rootLayout.addView(headerCard)

        val spacer1 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer1)

        // --- Quickhack 1: SYSTEM COLLAPSE (HUD Scale) ---
        val currentScale = prefs.getFloat(OverlayService.KEY_SCALE, 1.0f)
        val scaleCard = createInGameQuickhackBar(
            title = "SYSTEM COLLAPSE",
            statusTag = "READY",
            ramCost = "28",
            iconText = "🗲"
        )

        val scaleLabel = TextView(this).apply {
            text = "HUD Size Scale: ${String.format("%.2f", currentScale)}x"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setPadding(20, 8, 20, 4)
        }
        scaleCard.addView(scaleLabel)

        val seekBar = SeekBar(this).apply {
            max = 125 // 0.25 to 1.50 (1.25 range)
            progress = ((currentScale - 0.25f) * 100).toInt()
            setPadding(20, 0, 20, 12)
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

        val spacer2 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer2)

        // --- Quickhack 2: OVERHEAT (Corner Position) ---
        val currentPos = prefs.getString(OverlayService.KEY_POSITION, OverlayService.POS_TOP_RIGHT)
        val posCard = createInGameQuickhackBar(
            title = "OVERHEAT",
            statusTag = "READY | TRACEABLE",
            ramCost = "9",
            iconText = "🎯"
        )

        val posGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(20, 8, 20, 12)
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

        val spacer3 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer3)

        // --- Quickhack 3: CRIPPLE MOVEMENT (HUD Alignment Calibrator) ---
        val xOff = prefs.getInt(OverlayService.KEY_X_OFFSET, 40)
        val yOff = prefs.getInt(OverlayService.KEY_Y_OFFSET, 40)
        val calibrationCard = createInGameQuickhackBar(
            title = "CRIPPLE MOVEMENT",
            statusTag = "READY | TRACEABLE",
            ramCost = "6",
            iconText = "📍"
        )

        val offsetLabel = TextView(this).apply {
            text = "Offset: X=$xOff, Y=$yOff"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setPadding(20, 6, 20, 4)
        }
        calibrationCard.addView(offsetLabel)

        // D-Pad Direction Button Panel
        val dpadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 4, 0, 4)
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
        calibrationCard.addView(dpadLayout)

        // Smooth Touch Trackpad
        val joystickPad = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#060C16"))
                setStroke(1, Color.parseColor("#00E5FF"))
                cornerRadius = 2f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                140
            ).apply { setMargins(20, 6, 20, 6) }

            var lastX = 0f
            var lastY = 0f

            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        lastX = event.x
                        lastY = event.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - lastX).toInt()
                        val dy = (event.y - lastY).toInt()

                        if (dx != 0 || dy != 0) {
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

                            lastX = event.x
                            lastY = event.y
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                true
            }
        }
        calibrationCard.addView(joystickPad)

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
        calibrationCard.addView(btnReset)

        rootLayout.addView(calibrationCard)

        val spacer4_perm = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer4_perm)

        // --- Permission Button ---
        val btnPermission = Button(this).apply {
            text = "[ GRANT OVERLAY PERMISSION ]"
            setBackgroundColor(Color.parseColor("#121B2C"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 14, 0, 14)
            setOnClickListener { checkAndRequestOverlayPermission() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(btnPermission)

        val spacer4_exec = TextView(this).apply { text = " " }
        rootLayout.addView(spacer4_exec)

        // --- Cyberware Action Buttons ---
        val btnStart = Button(this).apply {
            text = "⚡ [ EXECUTE QUICKHACKS ]"
            setBackgroundColor(Color.parseColor("#FFE600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 16)
            setOnClickListener { startOverlayService() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(btnStart)

        val spacer5 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer5)

        val btnStop = Button(this).apply {
            text = "⏹️ [ TERMINATE OVERLAY ]"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 14, 0, 14)
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

    // Creates authentic Cyberpunk 2077 In-Game Quickhack Action Bar
    private fun createInGameQuickhackBar(
        title: String,
        statusTag: String,
        ramCost: String,
        iconText: String
    ): LinearLayout {
        val cyan = Color.parseColor("#00E5FF")
        val darkBg = Color.parseColor("#09111E")

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            background = GradientDrawable().apply {
                setColor(darkBg)
                setStroke(2, cyan) // Cyberpunk Cyan Border
                cornerRadius = 2f
            }
        }

        // Main Horizontal Quickhack Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }

        // Left Text Stack
        val textStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        textStack.addView(titleView)

        val tagBox = TextView(this).apply {
            text = statusTag
            textSize = 9f
            setTextColor(cyan)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setStroke(1, cyan)
                cornerRadius = 1f
            }
            setPadding(6, 1, 6, 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 0) }
        }
        textStack.addView(tagBox)
        topBar.addView(textStack)

        // RAM Cost Box (Cyberpunk 2077 In-Game RAM Badge)
        val ramBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050A12"))
                setStroke(1, Color.parseColor("#1B2A40"))
            }
        }

        val ramText = TextView(this).apply {
            text = ramCost
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        ramBox.addView(ramText)

        val ramIcon = TextView(this).apply {
            text = " ▮"
            textSize = 12f
            setTextColor(cyan)
        }
        ramBox.addView(ramIcon)
        topBar.addView(ramBox)

        // Far Right Cyberware Icon Box
        val iconView = TextView(this).apply {
            text = iconText
            textSize = 16f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply {
                setStroke(1, cyan)
                cornerRadius = 1f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(10, 0, 0, 0) }
        }
        topBar.addView(iconView)

        cardContainer.addView(topBar)
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
