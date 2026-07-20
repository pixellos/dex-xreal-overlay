package com.example.dexoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealOneImuManager(private val context: Context) {

    @Volatile private var isRunning = false
    private var workerThread: Thread? = null

    private var tcpSocket: Socket? = null
    @Volatile private var activeAttemptSocket: Socket? = null
    private var usbConnection: UsbDeviceConnection? = null
    private val usbThreads = mutableListOf<Thread>()

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var isReceiverRegistered = false

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    log("USB: Permission granted by user. Starting button listener...")
                    startUsbButtonListener()
                } else {
                    log("USB: Permission denied by user.")
                }
            }
        }
    }

    private fun log(msg: String) {
        Log.d("XrealOneImu", msg)
        LogBuffer.add(msg)
        val intent = Intent(MainActivity.ACTION_LOG_UPDATE).apply {
            putExtra(MainActivity.EXTRA_LOG_MSG, msg)
        }
        context.sendBroadcast(intent)
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        stopThreadsInternal()

        // Register USB permission receiver dynamically
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            log("USB: Receiver registration error: ${e.message}")
        }

        log("Starting XREAL 1s TCP Tracking Engine...")

        workerThread = Thread {
            runTcpNetworkEngine()
        }.apply { start() }

        // Automatically start the USB button listener (which triggers the popup if permission is needed)
        startUsbButtonListener()
    }

    private fun startUsbButtonListener() {
        val deviceList = usbManager.deviceList.values
        val match = deviceList.find { device ->
            device.vendorId == 0x3318 || device.productId in intArrayOf(0x0436, 0x0425, 0x0429)
        }

        if (match == null) {
            log("USB: No USB XREAL glasses detected for button events.")
            return
        }

        if (!usbManager.hasPermission(match)) {
            log("USB: Missing USB permission to read physical buttons. Requesting...")
            requestUsbPermission(match)
            return
        }

        Thread {
            log("USB: Opening USB connection to read physical buttons...")
            var connection: UsbDeviceConnection? = null
            try {
                connection = usbManager.openDevice(match) ?: return@Thread
                usbConnection = connection
                log("USB: Connected to glasses for physical button events.")

                for (i in 0 until match.interfaceCount) {
                    val intf = match.getInterface(i)
                    val claimed = connection.claimInterface(intf, true)
                    log("USB: Claimed interface $i for buttons: $claimed")

                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            val t = Thread {
                                val buf = ByteArray(64)
                                log("USB: Listening on Endpoint 0x${Integer.toHexString(ep.address)}")
                                while (isRunning) {
                                    val read = connection.bulkTransfer(ep, buf, buf.size, 100)
                                    if (read >= 17) {
                                        if (buf[0] == 0xFD.toByte()) {
                                            val msgId = ((buf[16].toInt() and 0xFF) shl 8) or (buf[15].toInt() and 0xFF)
                                            if (msgId == 0x6C05) {
                                                val virtButton = buf[26].toInt() and 0xFF
                                                val value = buf[30].toInt() and 0xFF
                                                log("USB BUTTON: Pressed virtButton=$virtButton, value=$value")

                                                if (value == 1) { // Button Down
                                                    val triggerIntent = Intent(OverlayService.ACTION_TRIGGER_TAP)
                                                    context.sendBroadcast(triggerIntent)
                                                }
                                            }
                                        }
                                    }
                                }
                            }.apply { start() }
                            usbThreads.add(t)
                        }
                    }
                }
            } catch (e: Exception) {
                log("USB BUTTON: Error reading HID: ${e.message}")
                try { connection?.close() } catch (ex: Exception) {}
            }
        }.start()
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = android.app.PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun runTcpNetworkEngine() {
        while (isRunning) {
            var connectedSocket: Socket? = null
            var connectedHost = ""
            val candidates = collectTargetHostCandidates()

            log("TCP PROBE: Starting sequence over gathered candidates: $candidates")

            for (host in candidates) {
                if (!isRunning) break
                var s: Socket? = null
                try {
                    s = Socket()
                    activeAttemptSocket = s
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    
                    log("TCP PROBE: Attempting connect to [$host:52998] (NoDelay=true)")
                    bindSocketToUsbNetworkIfPossible(s)

                    s.connect(InetSocketAddress(host, 52998), 1000)
                    s.soTimeout = 2000
                    connectedSocket = s
                    connectedHost = host
                    log("TCP PROBE: SUCCESS! Connected to $host:52998")
                    break
                } catch (e: Exception) {
                    log("TCP PROBE: Failed connecting to [$host]: ${e.message}")
                    try { s?.close() } catch (ex: Exception) {}
                } finally {
                    activeAttemptSocket = null
                }
            }

            if (connectedSocket != null) {
                tcpSocket = connectedSocket
                try {
                    val inputStream: InputStream = connectedSocket.getInputStream()
                    val buffer = ByteArray(2048)
                    val accumulator = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                    log("TCP STREAM: Handshake completed, listening for input packets...")

                    var frameCount = 0L
                    while (isRunning && connectedSocket.isConnected && !connectedSocket.isClosed) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead <= 0) {
                            log("TCP STREAM: Read EOF (bytesRead = $bytesRead)")
                            break
                        }

                        accumulator.put(buffer, 0, bytesRead)
                        accumulator.flip()

                        while (accumulator.remaining() >= 134) {
                            val startPos = accumulator.position()
                            if (accumulator.get(startPos) == 0x28.toByte() &&
                                accumulator.get(startPos + 1) == 0x36.toByte() &&
                                accumulator.get(startPos + 5) == 0x80.toByte()
                            ) {
                                val gyroX = accumulator.getFloat(startPos + 34)
                                val gyroY = accumulator.getFloat(startPos + 38)

                                if (!gyroX.isNaN() && !gyroY.isNaN() && gyroX.isFinite() && gyroY.isFinite()) {
                                    onHeadMoveListener?.invoke(gyroY * 0.5f, -gyroX * 0.5f)
                                }

                                frameCount++
                                if (frameCount == 1L || frameCount % 1000 == 0L) {
                                    val frameBytes = ByteArray(134)
                                    val origPos = accumulator.position()
                                    accumulator.position(startPos)
                                    accumulator.get(frameBytes)
                                    accumulator.position(origPos)
                                    
                                    val hexHeader = frameBytes.take(12).joinToString(" ") { String.format("%02X", it) }
                                    val hexGyro = frameBytes.slice(34..45).joinToString(" ") { String.format("%02X", it) }
                                    log("TCP STREAM: Frame #$frameCount raw hex header: [$hexHeader] gyro: [$hexGyro]")
                                }

                                if (frameCount % 200 == 0L) {
                                    log("TCP STREAM: Frame #$frameCount: gyroX=${String.format("%.4f", gyroX)}, gyroY=${String.format("%.4f", gyroY)}")
                                }

                                accumulator.position(startPos + 134)
                            } else {
                                accumulator.position(startPos + 1)
                            }
                        }
                        accumulator.compact()
                    }
                } catch (e: Exception) {
                    log("TCP STREAM: Socket read error from $connectedHost: ${e.message}")
                } finally {
                    try { connectedSocket.close() } catch (ex: Exception) {}
                    tcpSocket = null
                    log("TCP STREAM: Connection closed.")
                }
            }

            if (isRunning) {
                log("TCP STREAM: Restarting candidates scan in 3 seconds...")
                try { Thread.sleep(3000) } catch (ie: Exception) {}
            }
        }
    }

    private fun bindSocketToUsbNetworkIfPossible(socket: Socket) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = cm.allNetworks
                for (network in networks) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val ifaceName = linkProps.interfaceName?.lowercase() ?: continue
                    if (ifaceName.contains("usb") || ifaceName.contains("rndis") || ifaceName.contains("eth") || ifaceName.contains("cdc")) {
                        log("ROUTING: Binding TCP socket specifically to link interface [$ifaceName]")
                        network.bindSocket(socket)
                        return
                    }
                }
            }
            log("ROUTING: No matching ConnectivityManager network interface found for binding.")
        } catch (e: Exception) {
            log("ROUTING: Error binding socket to interface: ${e.message}")
        }
    }

    private fun collectTargetHostCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        log("DISCOVERY: Gathering network target candidate IPs...")

        val customIp = prefs.getString("custom_target_host_ip", "") ?: ""
        if (customIp.trim().isNotEmpty()) {
            log("DISCOVERY: Adding manual override target: ${customIp.trim()}")
            candidates.add(customIp.trim())
            return candidates
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = cm.allNetworks
                for (network in networks) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val ifaceName = linkProps.interfaceName?.lowercase() ?: continue
                    if (ifaceName.contains("usb") || ifaceName.contains("rndis") || ifaceName.contains("eth") || ifaceName.contains("cdc")) {
                        log("DISCOVERY: CM interface match: $ifaceName")
                        for (route in linkProps.routes) {
                            val gateway = route.gateway?.hostAddress
                            if (gateway != null && gateway != "0.0.0.0") {
                                log("DISCOVERY: Adding route gateway candidate: $gateway")
                                if (!candidates.contains(gateway)) candidates.add(gateway)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("DISCOVERY: CM query error: ${e.message}")
        }

        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.size >= 6) {
                        val ip = tokens[0]
                        val device = tokens[5].lowercase()
                        if (device.contains("usb") || device.contains("rndis") || device.contains("eth") || device.contains("cdc")) {
                            log("DISCOVERY: Found ARP peer candidate: $ip on interface $device")
                            if (!candidates.contains(ip)) candidates.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        try {
            BufferedReader(FileReader("/proc/net/route")).use { reader ->
                var line: String?
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.size >= 3) {
                        val iface = tokens[0].lowercase()
                        if (iface.contains("usb") || iface.contains("rndis") || iface.contains("eth") || iface.contains("cdc")) {
                            val gatewayHex = tokens[2]
                            if (gatewayHex != "00000000") {
                                val valHex = gatewayHex.toLong(16)
                                val ip = "${valHex and 0xFF}.${(valHex shr 8) and 0xFF}.${(valHex shr 16) and 0xFF}.${(valHex shr 24) and 0xFF}"
                                log("DISCOVERY: Found route gateway candidate: $ip on interface $iface")
                                if (!candidates.contains(ip)) candidates.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInt = interfaces.nextElement()
                val name = netInt.name.lowercase()
                if (name.contains("usb") || name.contains("rndis") || name.contains("eth") || name.contains("cdc")) {
                    val addrs = netInt.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                            val host = addr.hostAddress
                            log("DISCOVERY: Matching local IP: $host on interface $name")
                            val parts = host.split(".")
                            if (parts.size == 4) {
                                val peerIp = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                log("DISCOVERY: Adding guessed gateway candidate: $peerIp")
                                if (!candidates.contains(peerIp)) candidates.add(peerIp)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        if (!candidates.contains("169.254.2.1")) candidates.add("169.254.2.1")
        if (!candidates.contains("169.254.1.1")) candidates.add("169.254.1.1")

        log("DISCOVERY: Final candidate set gathered: $candidates")
        return candidates
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        stopThreadsInternal()
        
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {}
            isReceiverRegistered = false
        }
    }

    private fun stopThreadsInternal() {
        try {
            activeAttemptSocket?.close()
        } catch (e: Exception) {}
        activeAttemptSocket = null

        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        tcpSocket = null

        try {
            for (t in usbThreads) t.interrupt()
            usbThreads.clear()
            usbConnection?.close()
        } catch (e: Exception) {}
        usbConnection = null

        workerThread?.interrupt()
        workerThread = null
        log("Manager threads and sockets cleaned up.")
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.dexoverlay.USB_PERMISSION"
    }
}
