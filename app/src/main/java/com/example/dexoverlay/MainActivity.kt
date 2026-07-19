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
            setBackgroundColor(Color.parseColor("#06080E"))
        }

        // --- Header ---
        val titleText = TextView(this).apply {
            text = "⚡ Cyberpunk 2077 Minimal HUD"
            textSize = 24f
            setTextColor(Color.parseColor("#FFE600"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Ultra-Minimal Time Overlay for XREAL 1s & Samsung DeX"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 36)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(subtitleText)

        // --- Position Selector ---
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
            setPadding(0, 0, 0, 32)
        }

        val rbTopRight = RadioButton(this).apply {
            text = "Top-Right Corner"
            setTextColor(Color.parseColor("#FFE600"))
            isChecked = currentPos == OverlayService.POS_TOP_RIGHT
        }
        val rbTopLeft = RadioButton(this).apply {
            text = "Top-Left Corner"
            setTextColor(Color.parseColor("#FFE600"))
            isChecked = currentPos == OverlayService.POS_TOP_LEFT
        }

        posGroup.addView(rbTopRight)
        posGroup.addView(rbTopLeft)

        posGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedPos = if (checkedId == rbTopLeft.id) OverlayService.POS_TOP_LEFT else OverlayService.POS_TOP_RIGHT
            prefs.edit().putString(OverlayService.KEY_POSITION, selectedPos).apply()
            Toast.makeText(this, "Position set to $selectedPos", Toast.LENGTH_SHORT).show()
            restartOverlayServiceIfRunning()
        }
        rootLayout.addView(posGroup)

        // --- Permission Button ---
        val btnPermission = Button(this).apply {
            text = "Grant 'Display Over Other Apps' Permission"
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.WHITE)
            setOnClickListener { checkAndRequestOverlayPermission() }
        }
        rootLayout.addView(btnPermission)

        val spacer = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer)

        // --- Start/Stop Buttons ---
        val btnStart = Button(this).apply {
            text = "🚀 START CYBERPUNK HUD"
            setBackgroundColor(Color.parseColor("#FFE600"))
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { startOverlayService() }
        }
        rootLayout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "⏹️ STOP HUD"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopOverlayService() }
        }
        rootLayout.addView(btnStop)

        setContentView(rootLayout)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startOverlayService()
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
