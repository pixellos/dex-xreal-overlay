package com.example.dexoverlay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealOneImuManager(private val context: Context) {

    private var isRunning = false
    private var workerThread: Thread? = null

    // Engines
    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var usbConnection: UsbDeviceConnection? = null
    private val usbThreads = mutableListOf<Thread>()

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onSingleTapListener: (() -> Unit)? = null

    private fun log(msg: String) {
        Log.d("XrealOneImu", msg)
        LogBuffer.add(msg)
        val intent = Intent(MainActivity.ACTION_LOG_UPDATE).apply {
            putExtra(MainActivity.EXTRA_LOG_MSG, msg)
        }
        context.sendBroadcast(intent)
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val selectedEngine = prefs.getString(OverlayService.KEY_TRACKING_ENGINE, OverlayService.ENGINE_USB) ?: OverlayService.ENGINE_USB

        log("Starting Tracking Engine: [ $selectedEngine ]")

        workerThread = Thread {
            when (selectedEngine) {
                OverlayService.ENGINE_USB -> runUsbHidEngine()
                OverlayService.ENGINE_TCP -> runTcpNetworkEngine()
                OverlayService.ENGINE_UDP -> runUdpSocketEngine()
            }
        }.apply { start() }
    }

    // Engine 1: XREAL 1s USB HID Mode
    private fun runUsbHidEngine() {
        log("Searching for USB XREAL 1s glasses...")
        val deviceList = usbManager.deviceList.values
        val match = deviceList.find { device ->
            device.vendorId == 0x3318 || device.productId in intArrayOf(0x0436, 0x0425, 0x0429)
        }

        if (match == null) {
            log("No USB XREAL glasses detected. Plug in glasses to USB-C!")
            return
        }

        if (!usbManager.hasPermission(match)) {
            log("Missing USB Permission for glasses! Request permission in App UI.")
            return
        }

        try {
            val connection = usbManager.openDevice(match) ?: run {
                log("Failed to open connection to XREAL USB device!")
                return
            }
            usbConnection = connection
            log("Connected to XREAL USB: VID:0x${Integer.toHexString(match.vendorId)} PID:0x${Integer.toHexString(match.productId)}")

            for (i in 0 until match.interfaceCount) {
                val intf = match.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID || intf.interfaceClass == 255 || i >= 3) {
                    val claimed = connection.claimInterface(intf, true)
                    log("Claimed interface $i: $claimed")

                    // Wakeup MCU
                    val cmd1 = ByteArray(64).apply {
                        this[0] = 0xAA.toByte()
                        this[1] = 0xC5.toByte()
                        this[2] = 0x00.toByte()
                        this[3] = 0x01.toByte()
                    }
                    connection.controlTransfer(0x21, 0x09, 0x0300, i, cmd1, cmd1.size, 1000)

                    val cmd2 = byteArrayOf(
                        0xFD.toByte(), 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1D.toByte(),
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x01
                    )
                    connection.controlTransfer(0x21, 0x09, 0x0300, i, cmd2, cmd2.size, 1000)

                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            val t = Thread {
                                val buf = ByteArray(64)
                                log("Listening on USB Endpoint 0x${Integer.toHexString(ep.address)}")
                                while (isRunning) {
                                    val read = connection.bulkTransfer(ep, buf, buf.size, 100)
                                    if (read >= 16) {
                                        val rawX = ((buf[3].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
                                        val rawY = ((buf[5].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                                        val gX = if (rawX > 32767) rawX - 65536 else rawX
                                        val gY = if (rawY > 32767) rawY - 65536 else rawY

                                        onHeadMoveListener?.invoke(gY / 400f * 0.5f, -gX / 400f * 0.5f)
                                    }
                                }
                            }.apply { start() }
                            usbThreads.add(t)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("USB HID Engine Error: ${e.message}")
        }
    }

    // Engine 2: XREAL 1s TCP Network Client (Dynamic candidate search)
    private fun runTcpNetworkEngine() {
        while (isRunning) {
            var connectedSocket: Socket? = null
            var connectedHost = ""
            val candidates = collectTargetHostCandidates()

            log("TCP PROBE: Starting sequence over gathered candidates: $candidates")

            // Try connecting to candidates sequentially
            for (host in candidates) {
                if (!isRunning) break
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    
                    log("TCP PROBE: Attempting connect to [$host:52998] (NoDelay=true)")
                    bindSocketToUsbNetworkIfPossible(s)

                    s.connect(InetSocketAddress(host, 52998), 1000)
                    s.soTimeout = 2000
                    connectedSocket = s
                    connectedHost = host
                    log("TCP PROBE: SUCCESS! Established connection to $host:52998")
                    break
                } catch (e: Exception) {
                    log("TCP PROBE: Failed connecting to [$host]: ${e.message}")
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

                                onHeadMoveListener?.invoke(gyroY * 0.5f, -gyroX * 0.5f)

                                frameCount++
                                if (frameCount % 1000 == 0L) {
                                    log("TCP STREAM: Successfully processed $frameCount raw frames.")
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

    // Dynamic Host and Routing Discovery (SELinux compliant)
    private fun collectTargetHostCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        log("DISCOVERY: Gathering network target candidate IPs...")

        // 1. User manual override
        val customIp = prefs.getString("custom_target_host_ip", "") ?: ""
        if (customIp.trim().isNotEmpty()) {
            log("DISCOVERY: Adding manual override target: ${customIp.trim()}")
            candidates.add(customIp.trim())
            return candidates
        }

        // 2. ConnectivityManager Gateways (SELinux compliant)
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

        // 3. ARP Cache (/proc/net/arp)
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                reader.readLine() // skip header
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

        // 4. Routing Table (/proc/net/route)
        try {
            BufferedReader(FileReader("/proc/net/route")).use { reader ->
                var line: String?
                reader.readLine() // skip header
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

        // 5. Java NetworkInterface scanning fallback (local_ip & 0xFFFFFF00) | 0x01
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

        // Defaults
        if (!candidates.contains("169.254.2.1")) candidates.add("169.254.2.1")
        if (!candidates.contains("169.254.1.1")) candidates.add("169.254.1.1")

        log("DISCOVERY: Final candidate set gathered: $candidates")
        return candidates
    }

    // Engine 3: External UDP Socket Listener (Port 9090)
    private fun runUdpSocketEngine() {
        try {
            val socket = DatagramSocket(9090)
            udpSocket = socket
            val packetBuffer = ByteArray(64)
            log("UDP Port 9090 Socket Listener Active.")

            while (isRunning) {
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                socket.receive(packet)

                if (packet.length >= 12) {
                    val bb = ByteBuffer.wrap(packet.data, 0, packet.length).order(ByteOrder.LITTLE_ENDIAN)
                    val deltaX = bb.float
                    val deltaY = bb.float
                    val isClick = bb.int

                    onHeadMoveListener?.invoke(deltaX, deltaY)
                    if (isClick == 1) {
                        onSingleTapListener?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                log("UDP Socket closed: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        try {
            udpSocket?.close()
        } catch (e: Exception) {}
        try {
            for (t in usbThreads) t.interrupt()
            usbThreads.clear()
            usbConnection?.close()
        } catch (e: Exception) {}
        workerThread?.interrupt()
        workerThread = null
    }
}
