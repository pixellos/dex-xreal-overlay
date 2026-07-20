package com.example.dexoverlay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
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

    // Engine 2: XREAL 1s TCP Network Client (169.254.2.1:52998)
    private fun runTcpNetworkEngine() {
        while (isRunning) {
            try {
                val s = Socket()
                log("Connecting to XREAL 1s TCP Service (169.254.2.1:52998)...")
                s.connect(InetSocketAddress("169.254.2.1", 52998), 2500)
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
