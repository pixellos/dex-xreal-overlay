package com.example.dexoverlay

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

class XrealOneImuManager(private val context: Context) : SensorEventListener {

    private var isRunning = false
    private var workerThread: Thread? = null

    // Engine 1: TCP Client (169.254.2.1:52998)
    private var tcpSocket: Socket? = null

    // Engine 2: UDP Client (Port 9090)
    private var udpSocket: DatagramSocket? = null

    // Engine 3: Phone Gyroscope fallback
    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null

    // Engine 4: USB HID Driver fallback
    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private val usbThreads = mutableListOf<Thread>()

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

        // Initialize and register Phone Gyroscope
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyroSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            log("Phone Gyroscope fallback registered.")
        }

        workerThread = Thread {
            log("Starting Unified Multi-Engine Driver...")
            
            // Start UDP Receiver
            startUdpReceiver()

            // Start USB HID fallback search
            startUsbHidReceiver()

            // TCP socket client reader loop
            while (isRunning) {
                try {
                    val s = Socket()
                    log("Connecting to XREAL 1s TCP stream (169.254.2.1:52998)...")
                    s.connect(InetSocketAddress("169.254.2.1", 52998), 2500)
                    s.soTimeout = 2000
                    tcpSocket = s
                    log("CONNECTED successfully to XREAL 1s TCP Service!")

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

                                // Route glasses IMU data (disable phone gyro temporarily if glasses are streaming)
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
                        log("TCP offline: ${e.message}. Reconnecting in 3s...")
                        try { tcpSocket?.close() } catch (ex: Exception) {}
                        try { Thread.sleep(3000) } catch (ie: Exception) {}
                    }
                }
            }
        }.apply { start() }
    }

    private fun startUdpReceiver() {
        Thread {
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
        }.start()
    }

    private fun startUsbHidReceiver() {
        Thread {
            try {
                val deviceList = usbManager.deviceList.values
                val match = deviceList.find { device ->
                    device.vendorId == 0x3318 || device.productId in intArrayOf(0x0436, 0x0425, 0x0429)
                }

                if (match != null && usbManager.hasPermission(match)) {
                    val connection = usbManager.openDevice(match)
                    if (connection != null) {
                        log("Opened USB connection to VID:0x${Integer.toHexString(match.vendorId)} PID:0x${Integer.toHexString(match.productId)}")
                        usbConnection = connection

                        // Send Wakeup Magic Packets
                        for (i in 0 until match.interfaceCount) {
                            val intf = match.getInterface(i)
                            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID || intf.interfaceClass == 255 || i >= 3) {
                                connection.claimInterface(intf, true)
                                
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

                                // Listen on Endpoint
                                for (e in 0 until intf.endpointCount) {
                                    val ep = intf.getEndpoint(e)
                                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                                        val t = Thread {
                                            val buf = ByteArray(64)
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
                    }
                }
            } catch (e: Exception) {
                log("USB HID fallback error: ${e.message}")
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Gyroscope values in rad/s: axis 0 = pitch, axis 1 = yaw, axis 2 = roll
            val gyroY = event.values[1] // Yaw
            val gyroX = event.values[0] // Pitch
            
            // Map gyroscope delta values to head reticle positioning
            onHeadMoveListener?.invoke(gyroY * -0.6f, gyroX * 0.6f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun stop() {
        isRunning = false
        sensorManager?.unregisterListener(this)
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
