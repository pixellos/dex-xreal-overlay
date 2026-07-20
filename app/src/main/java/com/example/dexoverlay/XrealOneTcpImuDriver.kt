package com.example.dexoverlay

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealOneTcpImuDriver(private val context: Context) {

    private var socket: Socket? = null
    private var isReading = false
    private var readThread: Thread? = null

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null

    private fun log(msg: String) {
        Log.d("XrealTcpDriver", msg)
        val intent = Intent(MainActivity.ACTION_LOG_UPDATE).apply {
            putExtra(MainActivity.EXTRA_LOG_MSG, msg)
        }
        context.sendBroadcast(intent)
    }

    fun startListening(serverIp: String = "169.254.2.1", port: Int = 52998) {
        if (isReading) return
        isReading = true

        readThread = Thread {
            log("Starting XREAL 1s TCP Driver...")
            while (isReading) {
                try {
                    val s = Socket()
                    s.soTimeout = 1000 // Set read timeout

                    // 1. Scan network interfaces to find the USB-Ethernet (CDC-ECM) interface
                    var bindAddress: InetAddress? = null
                    try {
                        val interfaces = NetworkInterface.getNetworkInterfaces()
                        while (interfaces.hasMoreElements()) {
                            val netInterface = interfaces.nextElement()
                            val name = netInterface.name.lowercase()
                            
                            if (name.contains("usb") || name.contains("rndis") || name.contains("eth") || name.contains("cdc")) {
                                val addresses = netInterface.inetAddresses
                                while (addresses.hasMoreElements()) {
                                    val addr = addresses.nextElement()
                                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                                        log("Found interface: ${netInterface.name} -> ${addr.hostAddress}")
                                        if (addr.hostAddress.startsWith("169.254")) {
                                            bindAddress = addr
                                            break
                                        }
                                        bindAddress = addr
                                    }
                                }
                            }
                            if (bindAddress?.hostAddress?.startsWith("169.254") == true) break
                        }
                    } catch (e: Exception) {
                        log("Error scanning interfaces: ${e.message}")
                    }

                    // 2. Bind socket to forcing routing over USB-Ethernet
                    if (bindAddress != null) {
                        log("Binding socket to $bindAddress for USB routing...")
                        s.bind(InetSocketAddress(bindAddress, 0))
                    } else {
                        log("No active 169.254.X.X USB interface. Default routing...")
                    }

                    // 3. Connect to XREAL 1s TCP Server
                    log("Connecting to $serverIp:$port...")
                    s.connect(InetSocketAddress(serverIp, port), 3000)
                    socket = s
                    log("CONNECTED successfully to XREAL 1s TCP Server!")

                    val inputStream: InputStream = s.getInputStream()
                    val buffer = ByteArray(4096)
                    val accumulator = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

                    while (isReading && s.isConnected && !s.isClosed) {
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
                        log("TCP Connection failed: ${e.message}. Retrying in 2s...")
                        try { socket?.close() } catch (ex: Exception) {}
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
