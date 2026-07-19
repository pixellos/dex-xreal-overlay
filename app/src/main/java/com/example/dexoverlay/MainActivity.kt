package com.example.dexoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#0D0F12"))
        }

        // --- Header ---
        val titleText = TextView(this).apply {
            text = "👓 Cyberpunk DeX HUD"
            textSize = 24f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "XREAL 1s & Samsung DeX Configurator"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 32)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(subtitleText)

        // --- Section 1: HUD Position ---
        val posLabel = TextView(this).apply {
            text = "📍 HUD Position:"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(posLabel)

        val currentPos = prefs.getString(OverlayService.KEY_POSITION, OverlayService.POS_TOP_RIGHT)

        val posGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        val rbTopRight = RadioButton(this).apply {
            text = "Top-Right Corner"
            setTextColor(Color.WHITE)
            isChecked = currentPos == OverlayService.POS_TOP_RIGHT
        }
        val rbTopLeft = RadioButton(this).apply {
            text = "Top-Left Corner"
            setTextColor(Color.WHITE)
            isChecked = currentPos == OverlayService.POS_TOP_LEFT
        }

        posGroup.addView(rbTopRight)
        posGroup.addView(rbTopLeft)

        posGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedPos = if (checkedId == rbTopLeft.id) OverlayService.POS_TOP_LEFT else OverlayService.POS_TOP_RIGHT
            prefs.edit().putString(OverlayService.KEY_POSITION, selectedPos).apply()
            Toast.makeText(this, "HUD Position Updated to $selectedPos", Toast.LENGTH_SHORT).show()
            restartOverlayServiceIfRunning()
        }
        rootLayout.addView(posGroup)

        // --- Section 2: Map & Navigation Mode ---
        val mapLabel = TextView(this).apply {
            text = "🗺️ Map & Navigation Mode:"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(mapLabel)

        val currentMapMode = prefs.getString(OverlayService.KEY_MAP_MODE, OverlayService.MAP_MODE_AUTO_NAV)

        val mapGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val rbAutoNav = RadioButton(this).apply {
            text = "Auto-Show Only When Google Maps Nav is Active (Recommended)"
            setTextColor(Color.parseColor("#00E5FF"))
            isChecked = currentMapMode == OverlayService.MAP_MODE_AUTO_NAV
        }
        val rbAlwaysOn = RadioButton(this).apply {
            text = "Always Show Map Widget"
            setTextColor(Color.WHITE)
            isChecked = currentMapMode == OverlayService.MAP_MODE_ALWAYS_ON
        }
        val rbOff = RadioButton(this).apply {
            text = "Disable Map Widget (Clock & Battery Only)"
            setTextColor(Color.LTGRAY)
            isChecked = currentMapMode == OverlayService.MAP_MODE_OFF
        }

        mapGroup.addView(rbAutoNav)
        mapGroup.addView(rbAlwaysOn)
        mapGroup.addView(rbOff)

        mapGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                rbAlwaysOn.id -> OverlayService.MAP_MODE_ALWAYS_ON
                rbOff.id -> OverlayService.MAP_MODE_OFF
                else -> OverlayService.MAP_MODE_AUTO_NAV
            }
            prefs.edit().putString(OverlayService.KEY_MAP_MODE, selectedMode).apply()
            Toast.makeText(this, "Map Mode Updated: $selectedMode", Toast.LENGTH_SHORT).show()
            restartOverlayServiceIfRunning()
        }
        rootLayout.addView(mapGroup)

        // --- Section 3: System Permissions ---
        val btnPermission = Button(this).apply {
            text = "1. Grant 'Display Over Other Apps'"
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.WHITE)
            setOnClickListener { checkAndRequestOverlayPermission() }
        }
        rootLayout.addView(btnPermission)

        val btnNavPermission = Button(this).apply {
            text = "2. Grant Notification Access (for Google Maps Sync)"
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.parseColor("#FFE600"))
            setOnClickListener { openNotificationListenerSettings() }
        }
        rootLayout.addView(btnNavPermission)

        val spacer = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer)

        // --- Section 4: Overlay Controls ---
        val btnStart = Button(this).apply {
            text = "🚀 START CYBERPUNK HUD"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { startOverlayService() }
        }
        rootLayout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "⏹️ STOP HUD"
            setBackgroundColor(Color.parseColor("#FF3366"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopOverlayService() }
        }
        rootLayout.addView(btnStop)

        setContentView(rootLayout)

        // Auto-start Overlay Service on launch if permission is granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Open Notification Listener settings in phone settings", Toast.LENGTH_LONG).show()
        }
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
                Toast.makeText(this, "Overlay Permission Already Granted!", Toast.LENGTH_SHORT).show()
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
