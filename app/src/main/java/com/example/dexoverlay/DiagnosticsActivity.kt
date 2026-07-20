package com.example.dexoverlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.FileReader
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : Activity() {

    private lateinit var consoleTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var ipInputEditText: EditText

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

        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundColor(Color.parseColor("#03060B"))
        }

        // Title
        val header = TextView(this).apply {
            text = "SYSTEM OS // DIAGNOSTICS & ENGINE CONTROL"
            textSize = 14f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        rootLayout.addView(header)

        // --- SECTION 1: Engine Selector ---
        val selectorCard = createCompactCard("👓 XREAL 1s TRACKING ENGINE SELECTOR", "#00E5FF")
        val selectedEngine = prefs.getString(OverlayService.KEY_TRACKING_ENGINE, OverlayService.ENGINE_USB) ?: OverlayService.ENGINE_USB

        val engineGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 4, 0, 4)
        }

        val rbUsb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Engine 1: XREAL USB HID Mode (Direct USB Cable)"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isChecked = (selectedEngine == OverlayService.ENGINE_USB)
        }
        val rbTcp = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Engine 2: XREAL TCP Network Client (169.254.2.1:52998)"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isChecked = (selectedEngine == OverlayService.ENGINE_TCP)
        }
        val rbUdp = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Engine 3: External UDP Socket Listener (Port 9090)"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isChecked = (selectedEngine == OverlayService.ENGINE_UDP)
        }

        engineGroup.addView(rbUsb)
        engineGroup.addView(rbTcp)
        engineGroup.addView(rbUdp)

        engineGroup.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            if (checkedRb != null && checkedRb.isPressed) {
                val newEngine = when (checkedId) {
                    rbTcp.id -> OverlayService.ENGINE_TCP
                    rbUdp.id -> OverlayService.ENGINE_UDP
                    else -> OverlayService.ENGINE_USB
                }
                prefs.edit().putString(OverlayService.KEY_TRACKING_ENGINE, newEngine).apply()
                Toast.makeText(this, "Engine switched to $newEngine!", Toast.LENGTH_SHORT).show()
                restartOverlayService()
            }
        }
        selectorCard.addView(engineGroup)
        rootLayout.addView(selectorCard)

        val spacer1 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer1)

        // --- SECTION 2: Dynamic Connection Pickers & Targets ---
        val controlCard = createCompactCard("⚡ DYNAMIC CONNECTIONS & SCANS", "#00FF66")

        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }

        val ipLabel = TextView(this).apply {
            text = "Target Host IP: "
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        ipRow.addView(ipLabel)

        ipInputEditText = EditText(this).apply {
            setText(prefs.getString("custom_target_host_ip", "169.254.2.1"))
            setTextColor(Color.parseColor("#00FF66"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050B14"))
                setStroke(1, Color.parseColor("#00FF66"))
                cornerRadius = 2f
            }
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipRow.addView(ipInputEditText)
        controlCard.addView(ipRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }

        val btnScan = Button(this).apply {
            text = "🔍 MANUAL ARP & LINK SCAN"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 4, 0)
            }
            setOnClickListener {
                runManualLinkAndArpScan()
            }
        }
        btnRow.addView(btnScan)

        val btnConnect = Button(this).apply {
            text = "⚡ FORCE RECONNECT ENGINE"
            setBackgroundColor(Color.parseColor("#FFE600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener {
                val enteredHost = ipInputEditText.text.toString().trim()
                if (enteredHost.isNotEmpty()) {
                    prefs.edit().putString("custom_target_host_ip", enteredHost).apply()
                }
                restartOverlayService()
            }
        }
        btnRow.addView(btnConnect)
        controlCard.addView(btnRow)
        rootLayout.addView(controlCard)

        val spacer2 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer2)

        // Clear Logs Button
        val btnClear = Button(this).apply {
            text = "CLEAR LOG TERMINAL"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setOnClickListener {
                LogBuffer.clear()
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
                setMargins(0, 12, 0, 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050B14"))
                setStroke(2, Color.parseColor("#00FF66"))
                cornerRadius = 4f
            }
        }

        consoleTextView = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(consoleTextView)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        // Load historical logs
        val savedLogs = LogBuffer.getLogs()
        for (log in savedLogs) {
            consoleTextView.append(log + "\n")
        }
        if (savedLogs.isEmpty()) {
            consoleTextView.append("> Initializing diagnostic control center...\n")
        }

        // Register receiver for live logs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter(MainActivity.ACTION_LOG_UPDATE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter(MainActivity.ACTION_LOG_UPDATE))
        }

        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun createCompactCard(title: String, borderColorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#09111E"))
                setStroke(1, Color.parseColor(borderColorHex))
                cornerRadius = 2f
            }
            val cardTitle = TextView(this@DiagnosticsActivity).apply {
                text = title
                textSize = 11f
                setTextColor(Color.parseColor(borderColorHex))
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, 6)
            }
            addView(cardTitle)
        }
    }

    private fun restartOverlayService() {
        val stopIntent = Intent(this, OverlayService::class.java)
        stopService(stopIntent)

        val startIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        Toast.makeText(this, "HUD Service Restarted!", Toast.LENGTH_SHORT).show()
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

    private fun runManualLinkAndArpScan() {
        appendLog("--- INITIATING MANUAL ARP & LINK SCAN ---")
        
        // Log connected USB interfaces
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values
            appendLog("Connected USB Devices: ${devices.size}")
            for (dev in devices) {
                appendLog(" -> VID:0x${Integer.toHexString(dev.vendorId)} PID:0x${Integer.toHexString(dev.productId)} - Name: ${dev.deviceName}")
            }
        } catch (e: Exception) {
            appendLog("USB query error: ${e.message}")
        }

        // Log active network interfaces and addresses
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInt = interfaces.nextElement()
                val name = netInt.name
                val addresses = netInt.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    appendLog("Interface [$name]: IP=${addr.hostAddress} Loopback=${addr.isLoopbackAddress}")
                }
            }
        } catch (e: Exception) {
            appendLog("Network interfaces query error: ${e.message}")
        }

        // Log Routing table
        try {
            BufferedReader(FileReader("/proc/net/route")).use { reader ->
                var line: String?
                appendLog("Routing table entries (/proc/net/route):")
                while (reader.readLine().also { line = it } != null) {
                    appendLog("  $line")
                }
            }
        } catch (e: Exception) {
            appendLog("Route table read skipped: ${e.message}")
        }

        // Log ARP entries
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                appendLog("ARP cache table entries (/proc/net/arp):")
                while (reader.readLine().also { line = it } != null) {
                    appendLog("  $line")
                }
            }
        } catch (e: Exception) {
            appendLog("ARP table read skipped: ${e.message}")
        }

        appendLog("--- SCAN COMPLETED ---")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {}
    }
}
