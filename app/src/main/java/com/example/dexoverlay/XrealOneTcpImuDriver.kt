package com.example.dexoverlay

import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealOneTcpImuDriver {

    private var socket: Socket? = null
    private var isReading = false
    private var readThread: Thread? = null

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onDebugLogListener: ((log: String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d("XrealTcpDriver", msg)
        onDebugLogListener?.invoke(msg)
    }

    fun startListening(serverIp: String = "169.254.2.1", port: Int = 52998) {
        if (isReading) return
        isReading = true

        readThread = Thread {
            log("Connecting to XREAL 1s TCP Service at $serverIp:$port...")
            while (isReading) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(serverIp, port), 3000)
                    socket = s
                    log("CONNECTED to XREAL 1s TCP Service ($serverIp:$port)!")

                    val inputStream: InputStream = s.getInputStream()
                    val buffer = ByteArray(4096)
                    val accumulator = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

                    while (isReading && s.isConnected && !s.isClosed) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead <= 0) break

                        accumulator.put(buffer, 0, bytesRead)
                        accumulator.flip()

                        // Search for header: 0x28 0x36 0x00 0x00 0x00 0x80 (134-byte frame length)
                        while (accumulator.remaining() >= 134) {
                            val startPos = accumulator.position()
                            if (accumulator.get(startPos) == 0x28.toByte() &&
                                accumulator.get(startPos + 1) == 0x36.toByte() &&
                                accumulator.get(startPos + 5) == 0x80.toByte()
                            ) {
                                // Extract Float32 Gyro @ Byte offset 34
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
                    if (isReading) {
                        log("TCP connection attempt failed: ${e.message}. Retrying in 2s...")
                        try { Thread.sleep(2000) } catch (ie: Exception) {}
                    }
                }
            }
        }.apply { start() }
    }

    fun stopListening() {
        isReading = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        readThread?.interrupt()
        readThread = null
    }

    companion object {
        const val XREAL_ONE_DEFAULT_IP = "169.254.2.1"
        const val XREAL_ONE_PORT = 52998
    }
}
