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

    // ── Cyberpunk palette (same as MainActivity) ──────────────────────────────
    private val BG      = "#000000"
    private val BG_CARD = "#0A0A0A"
    private val GREEN   = "#00FF66"
    private val YELLOW  = "#FFE600"
    private val RED     = "#FF0055"
    private val DIM     = "#1A1A1A"

    private lateinit var consoleTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var ipInputEditText: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayedLogCount = 0L

    data class LogFilter(val keyword: String, val label: String, var enabled: Boolean = true)

    private val logFilters = listOf(
        LogFilter("KEY INTERCEPT",                  "⌨ KEYS"),
        LogFilter("ACCESSIBILITY GESTURE",          "🖱 GESTURE"),
        LogFilter("NODE CLICK",                     "🎯 NODE"),
        LogFilter("RECEIVER_CLICK",                 "📡 RECV"),
        LogFilter("TCP STREAM",                     "📶 TCP"),
        LogFilter("UDP",                            "📶 UDP"),
        LogFilter("USB",                            "🔌 USB"),
        LogFilter("IMU",                            "📐 IMU"),
        LogFilter("HeadCursorAccessibilityService", "♿ SVC")
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor(BG))
        }

        root.addView(label("DIAGNOSTICS // SYSTEM MONITOR", YELLOW, 14f, bold = true).apply {
            setPadding(0, 0, 0, 12)
        })

        // ── Card: Connections ─────────────────────────────────────────────────
        val connCard = card("⚡ CONNECTIONS & SCANS", GREEN)
        val ipRow = row().apply { setPadding(0, 4, 0, 4) }
        ipRow.addView(label("Host IP: ", GREEN, 11f))
        ipInputEditText = EditText(this).apply {
            setText(prefs.getString("custom_target_host_ip", ""))
            hint = "Auto-Discover"
            setHintTextColor(Color.parseColor("#336644"))
            setTextColor(Color.parseColor(GREEN))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050505"))
                setStroke(1, Color.parseColor(GREEN))
                cornerRadius = 2f
            }
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipRow.addView(ipInputEditText)
        connCard.addView(ipRow)

        val btnRow1 = row()
        btnRow1.addView(btn("🔍 ARP SCAN", GREEN) { runManualLinkAndArpScan() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
        })
        btnRow1.addView(btn("⚡ RECONNECT", YELLOW, textBlack = true) {
            prefs.edit().putString("custom_target_host_ip", ipInputEditText.text.toString().trim()).apply()
            restartOverlayService()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
        })
        connCard.addView(btnRow1)
        root.addView(connCard)
        root.addView(gap())

        // ── Card: Ethernet / USB ──────────────────────────────────────────────
        val netCard = card("🔧 ETH1 STATIC IP CONFIG", YELLOW)
        netCard.addView(label(
            "• IP: 169.254.2.2   • Prefix: 24\n• Gateway: 169.254.2.1   • DNS: 8.8.8.8",
            GREEN, 10f).apply { setPadding(0, 0, 0, 8) })
        val btnRow2 = row()
        btnRow2.addView(btn("⚙ ETH SETTINGS", YELLOW, textBlack = true) { openEthernetSettings() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
        })
        btnRow2.addView(btn("🔑 USB ACCESS", GREEN) { requestGlassesUsbPermission() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
        })
        netCard.addView(btnRow2)
        root.addView(netCard)
        root.addView(gap())

        // ── Card: Log Filters ─────────────────────────────────────────────────
        val filterCard = card("🔎 LOG FILTERS", YELLOW)

        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }

        // Chip references for ALL/NONE buttons
        val chipViews = mutableListOf<CheckBox>()
        for (filter in logFilters) {
            val chip = CheckBox(this).apply {
                text = " ${filter.label} "
                isChecked = filter.enabled
                // ALL chips use GREEN (enabled) / dimmed (disabled)
                setTextColor(Color.parseColor(GREEN))
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(BG_CARD))
                    setStroke(1, Color.parseColor(GREEN))
                    cornerRadius = 20f
                }
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 8, 0) }
                setOnCheckedChangeListener { _, checked ->
                    filter.enabled = checked
                    alpha = if (checked) 1f else 0.35f
                    redrawConsoleFromBuffer()
                }
            }
            chipRow.addView(chip)
            chipViews.add(chip)
        }

        // ALL button
        chipRow.addView(btn("ALL", YELLOW, textBlack = true) {
            logFilters.forEach { it.enabled = true }
            chipViews.forEach { it.isChecked = true; it.alpha = 1f }
            redrawConsoleFromBuffer()
        }.apply {
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(8, 0, 4, 0) }
        })

        // NONE button
        chipRow.addView(btn("NONE", RED) {
            logFilters.forEach { it.enabled = false }
            chipViews.forEach { it.isChecked = false; it.alpha = 0.35f }
            redrawConsoleFromBuffer()
        }.apply {
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(4, 0, 0, 0) }
        })

        chipScroll.addView(chipRow)
        filterCard.addView(chipScroll)
        root.addView(filterCard)
        root.addView(gap())

        // ── Log action row ────────────────────────────────────────────────────
        val actionRow = row()
        actionRow.addView(btn("🗑 CLEAR", RED) {
            LogBuffer.clear()
            consoleTextView.text = "> Console cleared.\n"
            displayedLogCount = 0L
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
        })
        actionRow.addView(btn("📤 EXPORT", GREEN) { exportLogs() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
        })
        root.addView(actionRow)

        // ── Console ───────────────────────────────────────────────────────────
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, 12, 0, 0) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#050505"))
                setStroke(2, Color.parseColor(GREEN))
                cornerRadius = 4f
            }
        }
        consoleTextView = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(Color.parseColor(GREEN))
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(consoleTextView)
        root.addView(scrollView)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(logPollingRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(logPollingRunnable)
    }

    // ── Log rendering ─────────────────────────────────────────────────────────

    private fun linePassesFilter(line: String): Boolean {
        if (logFilters.none { it.enabled }) return false
        for (f in logFilters) {
            if (line.contains(f.keyword, ignoreCase = true)) return f.enabled
        }
        return true // unmatched lines always shown
    }

    private fun pollBufferLogs() {
        val total = LogBuffer.totalLogsCount
        if (total > displayedLogCount) {
            val newLines = LogBuffer.getLogsAfter(displayedLogCount)
            displayedLogCount = total
            for (line in newLines) {
                if (linePassesFilter(line)) consoleTextView.append(line + "\n")
            }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        } else if (total < displayedLogCount) {
            consoleTextView.text = "> Console cleared.\n"
            displayedLogCount = 0L
        }
    }

    private fun redrawConsoleFromBuffer() {
        val sb = StringBuilder()
        for (line in LogBuffer.getLogs()) {
            if (linePassesFilter(line)) sb.append(line).append('\n')
        }
        consoleTextView.text = sb
        displayedLogCount = LogBuffer.totalLogsCount
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── View factory helpers ──────────────────────────────────────────────────

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun gap() = TextView(this).apply { text = " " }

    private fun label(text: String, color: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            typeface = Typeface.MONOSPACE
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(title: String, accentColor: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BG_CARD))
                setStroke(1, Color.parseColor(accentColor))
                cornerRadius = 3f
            }
            addView(label(title, accentColor, 11f, bold = true).apply { setPadding(0, 0, 0, 6) })
        }

    private fun btn(text: String, color: String, textBlack: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            textSize = 10f
            if (textBlack) {
                setBackgroundColor(Color.parseColor(color))
                setTextColor(Color.BLACK)
            } else {
                setBackgroundColor(Color.parseColor(DIM))
                setTextColor(Color.parseColor(color))
            }
            setOnClickListener { onClick() }
        }

    // ── Functionality ─────────────────────────────────────────────────────────

    private fun restartOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this, "HUD Restarted!", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogs() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Cyberdeck Diagnostics")
                putExtra(Intent.EXTRA_TEXT, consoleTextView.text.toString())
            }, "Export logs via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runManualLinkAndArpScan() {
        LogBuffer.add("--- ARP & LINK SCAN ---")
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            LogBuffer.add("USB devices: ${usbManager.deviceList.size}")
            usbManager.deviceList.values.forEach { d ->
                LogBuffer.add(" -> VID:0x${Integer.toHexString(d.vendorId)} PID:0x${Integer.toHexString(d.productId)} ${d.deviceName}")
            }
        } catch (e: Exception) { LogBuffer.add("USB error: ${e.message}") }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.allNetworks.forEach { net ->
                    val props = cm.getLinkProperties(net) ?: return@forEach
                    props.routes.forEach { r ->
                        LogBuffer.add("Route: iface=${props.interfaceName} dest=${r.destination} gw=${r.gateway?.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) { LogBuffer.add("Network error: ${e.message}") }

        try {
            NetworkInterface.getNetworkInterfaces()?.let { ifaces ->
                while (ifaces.hasMoreElements()) {
                    val ni = ifaces.nextElement()
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        LogBuffer.add("Interface [${ni.name}]: ${a.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) { LogBuffer.add("Interface error: ${e.message}") }

        try {
            BufferedReader(FileReader("/proc/net/arp")).use { r ->
                LogBuffer.add("ARP table:")
                r.forEachLine { LogBuffer.add("  $it") }
            }
        } catch (e: Exception) { LogBuffer.add("ARP blocked by SELinux") }

        LogBuffer.add("--- SCAN COMPLETE ---")
    }

    private fun openEthernetSettings() {
        for (action in listOf(
            "android.settings.ETHERNET_SETTINGS",
            "com.samsung.android.settings.ETHERNET_SETTINGS",
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS
        )) {
            try {
                startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return
            } catch (e: Exception) { /* try next */ }
        }
        Toast.makeText(this, "Could not open Ethernet settings", Toast.LENGTH_SHORT).show()
    }

    private fun requestGlassesUsbPermission() {
        LogBuffer.add("USB: Requesting permission from Diagnostics UI...")
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
                LogBuffer.add("USB: Dialog triggered.")
            } else {
                LogBuffer.add("USB: Glasses not found — plug in first!")
                Toast.makeText(this, "Plug in glasses first!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { LogBuffer.add("USB: Error: ${e.message}") }
    }
}
