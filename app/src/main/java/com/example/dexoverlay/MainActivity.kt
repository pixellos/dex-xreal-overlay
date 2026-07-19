package com.example.dexoverlay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val titleText = TextView(this).apply {
            text = "DeX & XREAL Overlay PoC"
            textSize = 24f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Floating Clock + Semi-Transparent Maps Widget"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(subtitleText)

        val btnPermission = Button(this).apply {
            text = "1. Grant 'Display Over Other Apps'"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener { checkAndRequestOverlayPermission() }
        }
        rootLayout.addView(btnPermission)

        val spacer = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer)

        val btnStart = Button(this).apply {
            text = "🚀 START OVERLAY (DeX / Emulator)"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { startOverlayService() }
        }
        rootLayout.addView(btnStart)

        val spacer2 = TextView(this).apply { text = "\n" }
        rootLayout.addView(spacer2)

        val btnStop = Button(this).apply {
            text = "⏹️ STOP OVERLAY"
            setBackgroundColor(Color.parseColor("#FF5252"))
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
        Toast.makeText(this, "Overlay Service Started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        Toast.makeText(this, "Overlay Service Stopped", Toast.LENGTH_SHORT).show()
    }
}
