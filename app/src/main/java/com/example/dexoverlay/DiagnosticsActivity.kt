package com.example.dexoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.NetworkInterface

class DiagnosticsActivity : Activity() {

    private lateinit var consoleTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var ipInputEditText: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayedLogCount = 0L

    private val logPollingRunnable = object : Runnable {
        override fun run() {
            pollBufferLogs()
            mainHandler.postDelayed(this, 300) // Poll logs every 300ms for real-time smoothness
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

        // --- SECTION 1: Dynamic Connection Pickers & Targets ---
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
            setText(prefs.getString("custom_target_host_ip", ""))
            hint = "Auto-Discover (Leave Empty)"
            setHintTextColor(Color.parseColor("#448855"))
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
                prefs.edit().putString("custom_target_host_ip", enteredHost).apply()
                restartOverlayService()
            }
        }
        btnRow.addView(btnConnect)
        controlCard.addView(btnRow)
        rootLayout.addView(controlCard)

        val spacer1 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer1)

        // --- SECTION 2: Network Interface Configuration ---
        val netConfigCard = createCompactCard("🔧 ETH1 STATIC IP CONFIGURATION", "#FF6600")

        val statusLabel = TextView(this).apply {
            text = "To enable the 'Save' button in Android Ethernet Settings:\n" +
                   " • IP Address: 169.254.2.2\n" +
                   " • Network Prefix Length: 24 (do NOT type 255.255.255.0!)\n" +
                   " • Gateway: 169.254.2.1\n" +
                   " • DNS 1: 8.8.8.8"
            setTextColor(Color.parseColor("#FFE600"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 8)
        }
        netConfigCard.addView(statusLabel)

        val netBtnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }

        val btnOpenEthSettings = Button(this).apply {
            text = "⚙ OPEN ETHERNET SETTINGS"
            setBackgroundColor(Color.parseColor("#FF6600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 4, 0)
            }
            setOnClickListener {
                openEthernetSettings()
            }
        }
        netBtnRow1.addView(btnOpenEthSettings)

        val btnUsbPermission = Button(this).apply {
            text = "🔑 KEY/BUTTON USB ACCESS"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener {
                requestGlassesUsbPermissionDirectly()
            }
        }
        netBtnRow1.addView(btnUsbPermission)
        netConfigCard.addView(netBtnRow1)
        rootLayout.addView(netConfigCard)

        val spacer2 = TextView(this).apply { text = " " }
        rootLayout.addView(spacer2)

        // Log Action Row (Clear & Export)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnClear = Button(this).apply {
            text = "CLEAR LOGS"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 4, 0)
            }
            setOnClickListener {
                LogBuffer.clear()
                consoleTextView.text = "> Console Cleared.\n"
                displayedLogCount = 0L
            }
        }
        actionRow.addView(btnClear)

        val btnExport = Button(this).apply {
            text = "📤 EXPORT & SHARE LOGS"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener {
                exportAndShareLogs()
            }
        }
        actionRow.addView(btnExport)
        rootLayout.addView(actionRow)

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
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(logPollingRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(logPollingRunnable)
    }

    private fun pollBufferLogs() {
        val nextIndexToFetch = displayedLogCount
        val totalCount = LogBuffer.totalLogsCount
        
        if (totalCount > nextIndexToFetch) {
            val newLines = LogBuffer.getLogsAfter(nextIndexToFetch)
            for (line in newLines) {
                consoleTextView.append(line + "\n")
            }
            displayedLogCount = totalCount
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        } else if (totalCount < nextIndexToFetch) {
            consoleTextView.text = "> Console Cleared.\n"
            displayedLogCount = 0L
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

    private fun exportAndShareLogs() {
        try {
            val allLogs = consoleTextView.text.toString()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Cyberdeck Diagnostics Log Report")
                putExtra(Intent.EXTRA_TEXT, allLogs)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Diagnostics Report via"))
            Toast.makeText(this, "Logs exported to share dialog!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runManualLinkAndArpScan() {
        LogBuffer.add("--- INITIATING MANUAL ARP & LINK SCAN ---")
        
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values
            LogBuffer.add("Connected USB Devices: ${devices.size}")
            for (dev in devices) {
                LogBuffer.add(" -> VID:0x${Integer.toHexString(dev.vendorId)} PID:0x${Integer.toHexString(dev.productId)} - Name: ${dev.deviceName}")
            }
        } catch (e: Exception) {
            LogBuffer.add("USB query error: ${e.message}")
        }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = cm.allNetworks
                LogBuffer.add("ConnectivityManager Active Networks: ${networks.size}")
                for (network in networks) {
                    val props = cm.getLinkProperties(network) ?: continue
                    val iface = props.interfaceName
                    for (route in props.routes) {
                        LogBuffer.add(" -> Link route: iface=$iface dest=${route.destination} gateway=${route.gateway?.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            LogBuffer.add("ConnectivityManager query error: ${e.message}")
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInt = interfaces.nextElement()
                val name = netInt.name
                val addresses = netInt.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    LogBuffer.add("Interface [$name]: IP=${addr.hostAddress} Loopback=${addr.isLoopbackAddress}")
                }
            }
        } catch (e: Exception) {
            LogBuffer.add("Network interfaces query error: ${e.message}")
        }

        try {
            BufferedReader(FileReader("/proc/net/route")).use { reader ->
                var line: String?
                LogBuffer.add("Routing table entries (/proc/net/route):")
                while (reader.readLine().also { line = it } != null) {
                    LogBuffer.add("  $line")
                }
            }
        } catch (e: Exception) {
            LogBuffer.add("Route table read skipped: [SELinux Blocked /proc/net/route]")
        }

        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                LogBuffer.add("ARP cache table entries (/proc/net/arp):")
                while (reader.readLine().also { line = it } != null) {
                    LogBuffer.add("  $line")
                }
            }
        } catch (e: Exception) {
            LogBuffer.add("ARP table read skipped: [SELinux Blocked /proc/net/arp]")
        }

        LogBuffer.add("--- SCAN COMPLETED ---")
    }

    private fun openEthernetSettings() {
        LogBuffer.add("Attempting to open Ethernet settings...")
        val intentsToTry = listOf(
            Intent("android.settings.ETHERNET_SETTINGS"),
            Intent("com.samsung.android.settings.ETHERNET_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intentsToTry) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                LogBuffer.add("Opened: ${intent.action}")
                return
            } catch (e: Exception) {
                LogBuffer.add("Intent ${intent.action} failed: ${e.message}")
            }
        }
        Toast.makeText(this, "Could not open Ethernet settings", Toast.LENGTH_SHORT).show()
    }

    private fun requestGlassesUsbPermissionDirectly() {
        LogBuffer.add("USB: Requesting USB access directly from UI...")
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList.values
            val match = deviceList.find { device ->
                device.vendorId == 0x3318 || device.productId in intArrayOf(0x0436, 0x0425, 0x0429)
            }
            if (match != null) {
                val intent = Intent(XrealOneImuManager.ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = android.app.PendingIntent.getBroadcast(this, 0, intent, flags)
                usbManager.requestPermission(match, pi)
                LogBuffer.add("USB: Permission request dialog triggered.")
                Toast.makeText(this, "USB permission requested!", Toast.LENGTH_SHORT).show()
            } else {
                LogBuffer.add("USB: XREAL Glasses not found on USB bus. Plug them in!")
                Toast.makeText(this, "Plug in glasses first!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            LogBuffer.add("USB: Permission request error: ${e.message}")
        }
    }
}
