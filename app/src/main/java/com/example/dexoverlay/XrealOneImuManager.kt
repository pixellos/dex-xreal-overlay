package com.example.dexoverlay

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealOneImuManager(private val context: Context) {

    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false
    private var workerThread: Thread? = null

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

        workerThread = Thread {
            log("XREAL 1s IMU Manager Active.")
            
            // Start UDP Receiver thread on Port 9090
            startUdpReceiver()

            // Main TCP Client loop connecting to 169.254.2.1:52998
            while (isRunning) {
                try {
                    val s = Socket()
                    log("Attempting TCP connection to XREAL 1s (169.254.2.1:52998)...")
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

                        // Parse 134-byte frames starting with 0x28 0x36 0x00 0x00 0x00 0x80
                        while (accumulator.remaining() >= 134) {
                            val startPos = accumulator.position()
                            if (accumulator.get(startPos) == 0x28.toByte() &&
                                accumulator.get(startPos + 1) == 0x36.toByte() &&
                                accumulator.get(startPos + 5) == 0x80.toByte()
                            ) {
                                val gyroX = accumulator.getFloat(startPos + 34)
                                val gyroY = accumulator.getFloat(startPos + 38)

                                val deltaX = gyroY * 0.5f
                                val deltaY = -gyroX * 0.5f

                                if (Math.abs(deltaX) > 0.001f || Math.abs(deltaY) > 0.001f) {
                                    onHeadMoveListener?.invoke(deltaX, deltaY)
                                }

                                accumulator.position(startPos + 134)
                            } else {
                                accumulator.position(startPos + 1)
                            }
                        }
                        accumulator.compact()
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        log("TCP Link offline: ${e.message}. Reconnecting in 3s...")
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

                        if (Math.abs(deltaX) > 0.001f || Math.abs(deltaY) > 0.001f) {
                            onHeadMoveListener?.invoke(deltaX, deltaY)
                        }
                        if (isClick == 1) {
                            onSingleTapListener?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("UDP Socket inactive: ${e.message}")
                }
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        try {
            udpSocket?.close()
        } catch (e: Exception) {}
        workerThread?.interrupt()
        workerThread = null
    }
}
