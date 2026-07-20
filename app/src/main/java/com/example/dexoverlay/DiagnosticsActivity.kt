package com.example.dexoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.FileReader
import java.net.NetworkInterface

class DiagnosticsActivity : Activity() {

    private lateinit var consoleTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var ipInputEditText: EditText

    private val mainHandler = Handler(Looper.getMainLooper())

    // Monotonic cursor — tracks how many total log lines we have consumed
    private var displayedLogCount = 0L

    // ── Log filter state ──────────────────────────────────────────────────────
    // Each entry: prefix-keyword → (label, colour, enabled)
    data class LogFilter(val keyword: String, val label: String, val colour: String, var enabled: Boolean = true)

    private val logFilters = listOf(
        LogFilter("KEY INTERCEPT",          "⌨ KEYS",     "#FFE600"),
        LogFilter("ACCESSIBILITY GESTURE",  "🖱 GESTURE",  "#00E5FF"),
        LogFilter("RECEIVER_CLICK",         "📡 RECV",     "#00FF66"),
        LogFilter("TCP STREAM",             "📶 TCP",      "#FF6600"),
        LogFilter("UDP",                    "📶 UDP",      "#FF6600"),
        LogFilter("USB",                    "🔌 USB",      "#AA88FF"),
        LogFilter("IMU",                    "📐 IMU",      "#FF0055"),
        LogFilter("HeadCursorAccessibilityService", "♿ SVC", "#888888"),
    )

    private val logPollingRunnable = object : Runnable {
        override fun run() {
            pollBufferLogs()
            mainHandler.postDelayed(this, 300)
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
        rootLayout.addView(TextView(this).apply {
            text = "SYSTEM OS // DIAGNOSTICS & ENGINE CONTROL"
            textSize = 14f
            setTextColor(Color.parseColor("#FFE600"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })

        // ── Card 1: Connections ───────────────────────────────────────────────
        val controlCard = createCompactCard("⚡ DYNAMIC CONNECTIONS & SCANS", "#00FF66")

        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }
        ipRow.addView(TextView(this).apply {
            text = "Target Host IP: "
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        })
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
        btnRow.addView(Button(this).apply {
            text = "🔍 MANUAL ARP & LINK SCAN"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00FF66"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener { runManualLinkAndArpScan() }
        })
        btnRow.addView(Button(this).apply {
            text = "⚡ FORCE RECONNECT ENGINE"
            setBackgroundColor(Color.parseColor("#FFE600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
            setOnClickListener {
                prefs.edit().putString("custom_target_host_ip", ipInputEditText.text.toString().trim()).apply()
                restartOverlayService()
            }
        })
        controlCard.addView(btnRow)
        rootLayout.addView(controlCard)
        rootLayout.addView(spacer())

        // ── Card 2: Ethernet / USB ────────────────────────────────────────────
        val netConfigCard = createCompactCard("🔧 ETH1 STATIC IP CONFIGURATION", "#FF6600")
        netConfigCard.addView(TextView(this).apply {
            text = "To enable the 'Save' button in Android Ethernet Settings:\n" +
                   " • IP Address: 169.254.2.2\n" +
                   " • Network Prefix Length: 24 (do NOT type 255.255.255.0!)\n" +
                   " • Gateway: 169.254.2.1\n • DNS 1: 8.8.8.8"
            setTextColor(Color.parseColor("#FFE600"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 8)
        })
        val netBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        netBtnRow.addView(Button(this).apply {
            text = "⚙ OPEN ETHERNET SETTINGS"
            setBackgroundColor(Color.parseColor("#FF6600"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener { openEthernetSettings() }
        })
        netBtnRow.addView(Button(this).apply {
            text = "🔑 USB ACCESS"
            setBackgroundColor(Color.parseColor("#0C182B"))
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
            setOnClickListener { requestGlassesUsbPermissionDirectly() }
        })
        netConfigCard.addView(netBtnRow)
        rootLayout.addView(netConfigCard)
        rootLayout.addView(spacer())

        // ── Log filter chip bar ───────────────────────────────────────────────
        val filterCard = createCompactCard("🔎 LOG FILTERS  (tap to toggle)", "#444466")

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }

        for (filter in logFilters) {
            val chip = CheckBox(this).apply {
                text = " ${filter.label} "
                isChecked = filter.enabled
                setTextColor(Color.parseColor(filter.colour))
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#09111E"))
                    setStroke(1, Color.parseColor(filter.colour))
                    cornerRadius = 20f
                }
                setPadding(12, 4, 12, 4)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 8, 0)
                layoutParams = lp
                setOnCheckedChangeListener { _, checked ->
                    filter.enabled = checked
                    // Redraw the console immediately from full buffer
                    redrawConsoleFromBuffer()
                }
            }
            chipRow.addView(chip)
        }

        // "ALL / NONE" quick toggles
        chipRow.addView(Button(this).apply {
            text = "ALL"
            textSize = 9f
            setBackgroundColor(Color.parseColor("#00FF66"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(8, 0, 4, 0)
            layoutParams = lp
            setOnClickListener {
                logFilters.forEach { it.enabled = true }
                // Re-tick all checkboxes
                for (i in 0 until chipRow.childCount) {
                    val v = chipRow.getChildAt(i)
                    if (v is CheckBox) v.isChecked = true
                }
                redrawConsoleFromBuffer()
            }
        })
        chipRow.addView(Button(this).apply {
            text = "NONE"
            textSize = 9f
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(4, 0, 0, 0)
            layoutParams = lp
            setOnClickListener {
                logFilters.forEach { it.enabled = false }
                for (i in 0 until chipRow.childCount) {
                    val v = chipRow.getChildAt(i)
                    if (v is CheckBox) v.isChecked = false
                }
                redrawConsoleFromBuffer()
            }
        })

        chipScroll.addView(chipRow)
        filterCard.addView(chipScroll)
        rootLayout.addView(filterCard)
        rootLayout.addView(spacer())

        // ── Log action row ────────────────────────────────────────────────────
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "CLEAR LOGS"
            setBackgroundColor(Color.parseColor("#FF0055"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener {
                LogBuffer.clear()
                consoleTextView.text = "> Console Cleared.\n"
                displayedLogCount = 0L
            }
        })
        actionRow.addView(Button(this).apply {
            text = "📤 EXPORT & SHARE LOGS"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
            setOnClickListener { exportAndShareLogs() }
        })
        rootLayout.addView(actionRow)

        // ── Console ───────────────────────────────────────────────────────────
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
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

    // ── Core log rendering ────────────────────────────────────────────────────

    private fun linePassesFilter(line: String): Boolean {
        val anyEnabled = logFilters.any { it.enabled }
        if (!anyEnabled) return false // all off → show nothing

        // If a line matches a known keyword, honour that filter's toggle
        for (f in logFilters) {
            if (line.contains(f.keyword, ignoreCase = true)) return f.enabled
        }
        // Lines that don't match any known keyword are shown by default
        return true
    }

    private fun pollBufferLogs() {
        val totalCount = LogBuffer.totalLogsCount
        if (totalCount > displayedLogCount) {
            val newLines = LogBuffer.getLogsAfter(displayedLogCount)
            displayedLogCount = totalCount
            for (line in newLines) {
                if (linePassesFilter(line)) consoleTextView.append(line + "\n")
            }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        } else if (totalCount < displayedLogCount) {
            consoleTextView.text = "> Console Cleared.\n"
            displayedLogCount = 0L
        }
    }

    /** Full redraw from the in-memory buffer (called when filters change). */
    private fun redrawConsoleFromBuffer() {
        val allLogs = LogBuffer.getLogs()
        val sb = StringBuilder()
        for (line in allLogs) {
            if (linePassesFilter(line)) sb.append(line).append('\n')
        }
        consoleTextView.text = sb
        displayedLogCount = LogBuffer.totalLogsCount
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun spacer() = TextView(this).apply { text = " " }

    private fun createCompactCard(title: String, borderColorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#09111E"))
                setStroke(1, Color.parseColor(borderColorHex))
                cornerRadius = 2f
            }
            addView(TextView(this@DiagnosticsActivity).apply {
                text = title
                textSize = 11f
                setTextColor(Color.parseColor(borderColorHex))
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, 6)
            })
        }
    }

    private fun restartOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        val startIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(startIntent)
        else startService(startIntent)
        Toast.makeText(this, "HUD Service Restarted!", Toast.LENGTH_SHORT).show()
    }

    private fun exportAndShareLogs() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Cyberdeck Diagnostics Log Report")
                putExtra(Intent.EXTRA_TEXT, consoleTextView.text.toString())
            }, "Share Diagnostics Report via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runManualLinkAndArpScan() {
        LogBuffer.add("--- INITIATING MANUAL ARP & LINK SCAN ---")
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values
            LogBuffer.add("USB Connected Devices: ${devices.size}")
            for (dev in devices) {
                LogBuffer.add(" -> VID:0x${Integer.toHexString(dev.vendorId)} PID:0x${Integer.toHexString(dev.productId)} - ${dev.deviceName}")
            }
        } catch (e: Exception) { LogBuffer.add("USB query error: ${e.message}") }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = cm.allNetworks
                LogBuffer.add("ConnectivityManager Networks: ${networks.size}")
                for (network in networks) {
                    val props = cm.getLinkProperties(network) ?: continue
                    for (route in props.routes) {
                        LogBuffer.add(" -> iface=${props.interfaceName} dest=${route.destination} gw=${route.gateway?.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) { LogBuffer.add("ConnectivityManager error: ${e.message}") }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    LogBuffer.add("Interface [${ni.name}]: IP=${addr.hostAddress} loopback=${addr.isLoopbackAddress}")
                }
            }
        } catch (e: Exception) { LogBuffer.add("Network interfaces error: ${e.message}") }

        try {
            BufferedReader(FileReader("/proc/net/arp")).use { r ->
                LogBuffer.add("ARP cache (/proc/net/arp):")
                r.forEachLine { LogBuffer.add("  $it") }
            }
        } catch (e: Exception) { LogBuffer.add("ARP read blocked: ${e.message}") }

        LogBuffer.add("--- SCAN COMPLETED ---")
    }

    private fun openEthernetSettings() {
        val intents = listOf(
            Intent("android.settings.ETHERNET_SETTINGS"),
            Intent("com.samsung.android.settings.ETHERNET_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                LogBuffer.add("Opened: ${intent.action}")
                return
            } catch (e: Exception) { /* try next */ }
        }
        Toast.makeText(this, "Could not open Ethernet settings", Toast.LENGTH_SHORT).show()
    }

    private fun requestGlassesUsbPermissionDirectly() {
        LogBuffer.add("USB: Requesting USB access from UI...")
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val match = usbManager.deviceList.values.find { d ->
                d.vendorId == 0x3318 || d.productId in intArrayOf(0x0436, 0x0425, 0x0429)
            }
            if (match != null) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                val pi = android.app.PendingIntent.getBroadcast(this, 0,
                    Intent(XrealOneImuManager.ACTION_USB_PERMISSION).apply { setPackage(packageName) }, flags)
                usbManager.requestPermission(match, pi)
                LogBuffer.add("USB: Permission request triggered.")
                Toast.makeText(this, "USB permission requested!", Toast.LENGTH_SHORT).show()
            } else {
                LogBuffer.add("USB: XREAL Glasses not found — plug them in first!")
                Toast.makeText(this, "Plug in glasses first!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { LogBuffer.add("USB: Error: ${e.message}") }
    }
}
