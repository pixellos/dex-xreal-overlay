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
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 20, 28, 28)
            setBackgroundColor(Color.parseColor("#03060B"))
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

        statusTag = TextView(this).apply {
            text = "[ PENDING ]"
            textSize = 10f
            setTextColor(Color.parseColor("#FF0055"))
            typeface = Typeface.MONOSPACE
        }
        headerCard.addView(statusTag)
        rootLayout.addView(headerCard)

        val spacer1 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer1)

        // --- Card 1: Upfront Access Requests ---
        val accessCard = createCompactCard("⚡ UPFRONT SYSTEM ACCESS STATUS", "#00FF66")

        btnPermissionStatus = Button(this).apply {
            text = "🔄 SCAN & ACQUIRE ALL REQUIRED PERMISSIONS"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                checkAndRequestAllPermissions(forceTrigger = true)
            }
        }
        accessCard.addView(btnPermissionStatus)
        rootLayout.addView(accessCard)

        val spacerAccess = TextView(this).apply { text = " " }
        rootLayout.addView(spacerAccess)

        // --- Card 2: XREAL 1s Diagnostics & Control ---
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

        // --- Card 3: Volume Buttons Action Mapper ---
        val mapperCard = createCompactCard("🕹️ VOLUME BUTTONS ACTION MAPPER", "#00E5FF")

        // Mouse Mode Toggle
        cbMouseMode = CheckBox(this).apply {
            text = " Enable Head Cursor Clicking (Mouse Mode)"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            isChecked = prefs.getBoolean(OverlayService.KEY_MOUSE_MODE_ENABLED, true)
            setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.isPressed) {
                    prefs.edit().putBoolean(OverlayService.KEY_MOUSE_MODE_ENABLED, isChecked).apply()
                    restartOverlayServiceIfRunning()
                }
            }
        }
        mapperCard.addView(cbMouseMode)

        val spacerMapper = TextView(this).apply { text = " " }
        mapperCard.addView(spacerMapper)

        // Volume Up Action Configuration
        val labelVolUp = TextView(this).apply {
            text = "Volume Up Press Action:"
            setTextColor(Color.parseColor("#FFE600"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        mapperCard.addView(labelVolUp)

        val rgVolUp = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val actions = listOf(
            OverlayService.ACTION_VAL_LEFT_CLICK to "Execute Left Click",
            OverlayService.ACTION_VAL_RIGHT_CLICK to "Execute Right Click (Long Press)",
            OverlayService.ACTION_VAL_TOGGLE_HUD to "Toggle HUD Overlay On/Off",
            OverlayService.ACTION_VAL_RECENTER to "Recenter Cursor to Center",
            OverlayService.ACTION_VAL_NONE to "None (Disable Action)"
        )

        val currentVolUp = prefs.getString(OverlayService.KEY_VOL_UP_ACTION, OverlayService.ACTION_VAL_LEFT_CLICK)
        val volUpButtons = mutableListOf<RadioButton>()

        for ((actionVal, actionLabel) in actions) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = actionLabel
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                isChecked = (currentVolUp == actionVal)
            }
            rgVolUp.addView(rb)
            volUpButtons.add(rb)
        }
        rgVolUp.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            if (checkedRb != null && checkedRb.isPressed) {
                val index = group.indexOfChild(checkedRb)
                if (index in actions.indices) {
                    val chosen = actions[index].first
                    prefs.edit().putString(OverlayService.KEY_VOL_UP_ACTION, chosen).apply()
                    Toast.makeText(this@MainActivity, "Vol Up action updated!", Toast.LENGTH_SHORT).show()
                    restartOverlayServiceIfRunning()
                }
            }
        }
        mapperCard.addView(rgVolUp)

        val spacerMapper2 = TextView(this).apply { text = "\n" }
        mapperCard.addView(spacerMapper2)

        // Volume Down Action Configuration
        val labelVolDown = TextView(this).apply {
            text = "Volume Down Press Action:"
            setTextColor(Color.parseColor("#FFE600"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        mapperCard.addView(labelVolDown)

        val rgVolDown = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val currentVolDown = prefs.getString(OverlayService.KEY_VOL_DOWN_ACTION, OverlayService.ACTION_VAL_RIGHT_CLICK)
        val volDownButtons = mutableListOf<RadioButton>()

        for ((actionVal, actionLabel) in actions) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = actionLabel
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                isChecked = (currentVolDown == actionVal)
            }
            rgVolDown.addView(rb)
            volDownButtons.add(rb)
        }
        rgVolDown.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            if (checkedRb != null && checkedRb.isPressed) {
                val index = group.indexOfChild(checkedRb)
                if (index in actions.indices) {
                    val chosen = actions[index].first
                    prefs.edit().putString(OverlayService.KEY_VOL_DOWN_ACTION, chosen).apply()
                    Toast.makeText(this@MainActivity, "Vol Down action updated!", Toast.LENGTH_SHORT).show()
                    restartOverlayServiceIfRunning()
                }
            }
        }
        mapperCard.addView(rgVolDown)
        rootLayout.addView(mapperCard)

        val spacer2 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer2)

        // --- Card 4: HUD Customization (Scale & Position) ---
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

        // --- Card 5: Alignment Calibrator ---
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

        // Dynamically register the REFRESH_UI broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshUiReceiver, IntentFilter("com.example.dexoverlay.REFRESH_UI"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshUiReceiver, IntentFilter("com.example.dexoverlay.REFRESH_UI"))
        }

        // Always auto-start the HUD service on launch if overlay permission is present
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(refreshUiReceiver)
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestAllPermissions(forceTrigger = false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun checkAndRequestAllPermissions(forceTrigger: Boolean) {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled(this, HeadCursorAccessibilityService::class.java)

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList.values
        val glassesDevice = deviceList.find { device ->
            device.vendorId == 0x3318 || device.productId in intArrayOf(0x0436, 0x0425, 0x0429)
        }
        val hasUsb = glassesDevice == null || usbManager.hasPermission(glassesDevice)

        // Update UI status tags
        if (hasOverlay && hasAccessibility && hasUsb) {
            statusTag.text = "[ ACTIVE // ALL ACCESS OK ]"
            statusTag.setTextColor(Color.parseColor("#00FF66"))
            btnPermissionStatus.text = "✅ ALL PERMISSIONS ACQUIRED"
            btnPermissionStatus.setBackgroundColor(Color.parseColor("#0B2213"))
            btnPermissionStatus.setTextColor(Color.parseColor("#00FF66"))
        } else {
            statusTag.text = "[ PENDING AUTHORIZATION ]"
            statusTag.setTextColor(Color.parseColor("#FF0055"))
            btnPermissionStatus.text = "⚠️ TAP TO GRANT MISSING ACCESS"
            btnPermissionStatus.setBackgroundColor(Color.parseColor("#2C0C12"))
            btnPermissionStatus.setTextColor(Color.parseColor("#FF0055"))
        }

        if (forceTrigger) {
            if (!hasOverlay) {
                Toast.makeText(this, "Please authorize Overlay Permission", Toast.LENGTH_SHORT).show()
                checkAndRequestOverlayPermission()
                return
            }
            if (!hasAccessibility) {
                Toast.makeText(this, "Please enable accessibility cursor driver", Toast.LENGTH_SHORT).show()
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {}
                return
            }
            if (!hasUsb && glassesDevice != null) {
                Toast.makeText(this, "Please authorize USB glasses connection", Toast.LENGTH_SHORT).show()
                requestXrealUsbPermissionDirectly(glassesDevice)
                return
            }
            if (hasOverlay) {
                startOverlayService()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun requestXrealUsbPermissionDirectly(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val intent = Intent(XrealOneImuManager.ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = android.app.PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, pi)
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
            setPadding(0, 0, 0, 6)
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
