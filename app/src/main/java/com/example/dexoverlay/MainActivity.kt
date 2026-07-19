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

        // Force stop running OverlayService to clean up any cached WindowManager views
        stopOverlayService()

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#03060B")) // Authentic Cyberpunk Netrunner Pitch Dark
        }

        // --- Cyberdeck Top Banner ---
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#09111E"))
                setStroke(2, Color.parseColor("#FF0055"))
                cornerRadius = 2f
            }
        }

        val subtitleText = TextView(this).apply {
            text = "CYBERDECK v 552.322"
            textSize = 12f
            setTextColor(Color.parseColor("#FF0055"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        headerCard.addView(subtitleText)

        val titleText = TextView(this).apply {
            text = "AVAILABLE QUICKHACKS:"
            textSize = 18f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 2, 0, 0)
        }
        headerCard.addView(titleText)
        rootLayout.addView(headerCard)

        val spacer1 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer1)

        // --- Quickhack 1: CYBERWARE IMU (XREAL Head Tracking & Button Click Driver) ---
        val enableHeadCursor = prefs.getBoolean(OverlayService.KEY_ENABLE_HEAD_CURSOR, false)
        val singleTapAction = prefs.getString(OverlayService.KEY_SINGLE_TAP_ACTION, OverlayService.SINGLE_TAP_ACTION_CLICK) ?: OverlayService.SINGLE_TAP_ACTION_CLICK

        val imuCard = createAuthenticQuickhackSlot(
            name = "CYBERWARE IMU // HEAD CURSOR & SINGLE TAP",
            tier = "ICONIC // TIER 5",
            statusTag = "READY | INSTALLED",
            ramCost = "24",
            iconText = "👓",
            isHighlighted = true
        )

        val cbHeadCursor = CheckBox(this).apply {
            text = "[ ENABLE XREAL 1s IMU HEAD-TRACKED CURSOR ]"
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            isChecked = enableHeadCursor
            setPadding(20, 8, 20, 4)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(OverlayService.KEY_ENABLE_HEAD_CURSOR, isChecked).apply()
                Toast.makeText(this@MainActivity, "Head cursor ${if (isChecked) "ENABLED" else "DISABLED"}", Toast.LENGTH_SHORT).show()
                restartOverlayServiceIfRunning()
            }
        }
        imuCard.addView(cbHeadCursor)

        val tapLabel = TextView(this).apply {
            text = "XREAL Temple Button Single Tap Action:"
            textSize = 11f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setPadding(20, 6, 20, 2)
        }
        imuCard.addView(tapLabel)

        val tapGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(20, 2, 20, 10)
        }

        val rbClick = RadioButton(this).apply {
            text = "(●) Click at Head Cursor Position"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            isChecked = singleTapAction == OverlayService.SINGLE_TAP_ACTION_CLICK
        }
        val rbToggleHud = RadioButton(this).apply {
            text = "( ) Toggle Cyberpunk HUD On/Off"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            isChecked = singleTapAction == OverlayService.SINGLE_TAP_ACTION_TOGGLE_HUD
        }
        val rbRecenter = RadioButton(this).apply {
            text = "( ) Recenter Cursor to Screen Center"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            isChecked = singleTapAction == OverlayService.SINGLE_TAP_ACTION_RECENTER
        }

        tapGroup.addView(rbClick)
        tapGroup.addView(rbToggleHud)
        tapGroup.addView(rbRecenter)

        tapGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedAction = when (checkedId) {
                rbToggleHud.id -> OverlayService.SINGLE_TAP_ACTION_TOGGLE_HUD
                rbRecenter.id -> OverlayService.SINGLE_TAP_ACTION_RECENTER
                else -> OverlayService.SINGLE_TAP_ACTION_CLICK
            }
            prefs.edit().putString(OverlayService.KEY_SINGLE_TAP_ACTION, selectedAction).apply()
            Toast.makeText(this, "Single Tap Action Updated!", Toast.LENGTH_SHORT).show()
            restartOverlayServiceIfRunning()
        }
        imuCard.addView(tapGroup)

        rootLayout.addView(imuCard)

        val spacer_imu = TextView(this).apply { text = " " }
        rootLayout.addView(spacer_imu)

        // --- Quickhack 2: SYSTEM COLLAPSE (HUD Scale 0.25x - 1.50x) ---
        val currentScale = prefs.getFloat(OverlayService.KEY_SCALE, 1.0f)
        val scaleCard = createAuthenticQuickhackSlot(
            name = "SYSTEM COLLAPSE // HUD SCALE",
            tier = "ICONIC // TIER 5",
            statusTag = "READY",
            ramCost = "28",
            iconText = "🗲",
            isHighlighted = false
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
            max = 125
            progress = ((currentScale - 0.25f) * 100).toInt()
            setPadding(20, 0, 20, 14)
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

        // --- Quickhack 3: OVERHEAT (Corner Position) ---
        val currentPos = prefs.getString(OverlayService.KEY_POSITION, OverlayService.POS_TOP_RIGHT)
        val posCard = createAuthenticQuickhackSlot(
            name = "OVERHEAT // CORNER POSITION",
            tier = "ICONIC // TIER 5",
            statusTag = "READY | TRACEABLE",
            ramCost = "9",
            iconText = "🎯",
            isHighlighted = false
        )

        val posGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(20, 8, 20, 14)
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

        // --- Quickhack 4: CRIPPLE MOVEMENT (HUD Alignment Calibrator) ---
        val xOff = prefs.getInt(OverlayService.KEY_X_OFFSET, 40)
        val yOff = prefs.getInt(OverlayService.KEY_Y_OFFSET, 40)
        val calibrationCard = createAuthenticQuickhackSlot(
            name = "CRIPPLE MOVEMENT // ALIGNMENT",
            tier = "ICONIC // TIER 5",
            statusTag = "READY | TRACEABLE",
            ramCost = "6",
            iconText = "📍",
            isHighlighted = false
        )

        val offsetLabel = TextView(this).apply {
            text = "Offset: X=$xOff, Y=$yOff"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            setPadding(20, 6, 20, 4)
        }
        calibrationCard.addView(offsetLabel)

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

    private fun createAuthenticQuickhackSlot(
        name: String,
        tier: String,
        statusTag: String,
        ramCost: String,
        iconText: String,
        isHighlighted: Boolean
    ): LinearLayout {
        val borderColor = if (isHighlighted) Color.parseColor("#FFE600") else Color.parseColor("#00E5FF")
        val darkBg = Color.parseColor("#09111E")

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
            background = GradientDrawable().apply {
                setColor(darkBg)
                setStroke(2, borderColor)
                cornerRadius = 2f
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
        }

        val textStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        textStack.addView(titleView)

        val tierTagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 0)
        }

        val tagBox = TextView(this).apply {
            text = statusTag
            textSize = 9f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setStroke(1, Color.parseColor("#00E5FF"))
                cornerRadius = 1f
            }
            setPadding(6, 1, 6, 1)
        }
        tierTagRow.addView(tagBox)

        val tierText = TextView(this).apply {
            text = "  $tier"
            textSize = 9f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
        }
        tierTagRow.addView(tierText)
        textStack.addView(tierTagRow)

        topBar.addView(textStack)

        val ramBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 4, 10, 4)
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
            setTextColor(Color.parseColor("#00E5FF"))
        }
        ramBox.addView(ramIcon)
        topBar.addView(ramBox)

        val iconView = TextView(this).apply {
            text = iconText
            textSize = 16f
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER
            setPadding(10, 8, 10, 8)
            background = GradientDrawable().apply {
                setStroke(1, Color.parseColor("#00E5FF"))
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
