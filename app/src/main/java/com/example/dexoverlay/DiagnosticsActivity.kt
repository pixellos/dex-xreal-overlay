package com.example.dexoverlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : Activity() {

    private lateinit var consoleTextView: TextView
    private lateinit var scrollView: ScrollView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_LOG_UPDATE) {
                val logMsg = intent.getStringExtra(MainActivity.EXTRA_LOG_MSG) ?: ""
                appendLog(logMsg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#03060B"))
        }

        // Header Title
        val header = TextView(this).apply {
            text = "SYSTEM OS // DIAGNOSTICS SCANNER"
            textSize = 14f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(header)

        // Clear Logs Button
        val btnClear = Button(this).apply {
            text = "CLEAR LOG TERMINAL"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setOnClickListener {
                consoleTextView.text = "> Console Cleared.\n"
            }
        }
        rootLayout.addView(btnClear)

        // Scrollable Console Text View
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, 16, 0, 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050B14"))
                setStroke(2, Color.parseColor("#00FF66"))
                cornerRadius = 4f
            }
        }

        consoleTextView = TextView(this).apply {
            text = "> Initializing diagnostic scanner...\n> Connected receivers online.\n"
            textSize = 10f
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(consoleTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        // Register receiver for logs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter(MainActivity.ACTION_LOG_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter(MainActivity.ACTION_LOG_UPDATE))
        }

        // Trigger an initial dump of network configurations
        dumpNetworkDiagnostics()
    }

    private fun appendLog(msg: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = sdf.format(Date())
        runOnUiThread {
            consoleTextView.append("[$timestamp] $msg\n")
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun dumpNetworkDiagnostics() {
        appendLog("--- DUMPING NETWORK INTERFACE DIAGNOSTICS ---")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var count = 0
            while (interfaces.hasMoreElements()) {
                val netInt = interfaces.nextElement()
                val name = netInt.name
                val isUp = netInt.isUp
                val addresses = netInt.inetAddresses
                appendLog("Interface [$name] Up=$isUp Loopback=${netInt.isLoopback}")
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    appendLog("  -> Addr: ${addr.hostAddress}")
                }
                count++
            }
            appendLog("Scanned $count network interfaces successfully.")
        } catch (e: Exception) {
            appendLog("Error fetching network interfaces: ${e.message}")
        }
        appendLog("---------------------------------------------")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {}
    }
}
