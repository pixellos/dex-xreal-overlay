package com.example.dexoverlay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
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

    // Engine 2: XREAL 1s TCP Network Client (169.254.2.1:52998 / Dynamic Subnet)
    private fun runTcpNetworkEngine() {
        while (isRunning) {
            try {
                // Discover target IP and gateway dynamically
                val discoveredHost = discoverTargetHost()
                val targetPort = 52998

                val s = Socket()
                log("Connecting to XREAL 1s TCP Service ($discoveredHost:$targetPort)...")
                s.connect(InetSocketAddress(discoveredHost, targetPort), 3000)
                s.soTimeout = 2000
                tcpSocket = s
                log("CONNECTED successfully to XREAL 1s TCP stream!")

                val inputStream: InputStream = s.getInputStream()
                val buffer = ByteArray(2048)
                val accumulator = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

                while (isRunning && s.isConnected && !s.isClosed) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break

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

                            accumulator.position(startPos + 134)
                        } else {
                            accumulator.position(startPos + 1)
                        }
                    }
                    accumulator.compact()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("TCP Offline: ${e.message}. Reconnecting in 3s...")
                    try { tcpSocket?.close() } catch (ex: Exception) {}
                    try { Thread.sleep(3000) } catch (ie: Exception) {}
                }
            }
        }
    }

    // Dynamic Host and Routing Discovery (Zero Hardcoding)
    private fun discoverTargetHost(): String {
        var targetIp = "169.254.2.1" // Default fallback

        // 1. Scan ARP Cache (/proc/net/arp)
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                // Skip header
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.size >= 6) {
                        val ip = tokens[0]
                        val device = tokens[5].lowercase()
                        if (device.contains("usb") || device.contains("rndis") || device.contains("eth") || device.contains("cdc")) {
                            log("ARP Table match: found peer $ip on interface $device")
                            targetIp = ip
                            return targetIp
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("ARP Table read skipped: ${e.message}")
        }

        // 2. Scan Routing Table (/proc/net/route) to find default gateway on USB interface
        try {
            BufferedReader(FileReader("/proc/net/route")).use { reader ->
                var line: String?
                // Skip header
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line!!.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.size >= 3) {
                        val iface = tokens[0].lowercase()
                        if (iface.contains("usb") || iface.contains("rndis") || iface.contains("eth") || iface.contains("cdc")) {
                            val gatewayHex = tokens[2]
                            if (gatewayHex != "00000000") {
                                // Parse little-endian hex to IP
                                val valHex = gatewayHex.toLong(16)
                                val ip = "${valHex and 0xFF}.${(valHex shr 8) and 0xFF}.${(valHex shr 16) and 0xFF}.${(valHex shr 24) and 0xFF}"
                                log("Routing Table Match: found gateway $ip on interface $iface")
                                targetIp = ip
                                return targetIp
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("Routing Table read skipped: ${e.message}")
        }

        // 3. Fallback: Parse active interfaces to find local subnet and guess gateway
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
                            log("Interface scanning fallback: found local address $host on interface $name")
                            // Guess gateway is .1 of the subnet (e.g. 192.168.42.129 -> 192.168.42.1)
                            val parts = host.split(".")
                            if (parts.size == 4) {
                                targetIp = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                log("Guessed gateway address: $targetIp")
                                return targetIp
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("Interface scanning skipped: ${e.message}")
        }

        log("Using target host: $targetIp")
        return targetIp
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
