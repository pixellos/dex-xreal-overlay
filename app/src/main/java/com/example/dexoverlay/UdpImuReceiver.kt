package com.example.dexoverlay

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpImuReceiver(private val port: Int = 9090) {

    private var socket: DatagramSocket? = null
    private var isListening = false
    private var listenThread: Thread? = null

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onGlassesSingleTapListener: (() -> Unit)? = null

    fun startListening() {
        if (isListening) return
        isListening = true

        listenThread = Thread {
            try {
                val udpSocket = DatagramSocket(port)
                socket = udpSocket
                val buffer = ByteArray(128)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d("UdpImuReceiver", "UDP IMU Receiver listening on port $port")

                while (isListening && !udpSocket.isClosed) {
                    udpSocket.receive(packet)
                    val data = packet.data
                    val length = packet.length

                    if (length >= 8) {
                        val byteBuf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                        val deltaX = byteBuf.getFloat()
                        val deltaY = byteBuf.getFloat()

                        val isClick = if (length >= 12) byteBuf.getInt() else 0

                        onHeadMoveListener?.invoke(deltaX, deltaY)

                        if (isClick == 1) {
                            onGlassesSingleTapListener?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UdpImuReceiver", "UDP Socket Exception", e)
            }
        }.apply { start() }
    }

    fun stopListening() {
        isListening = false
        try {
            socket?.close()
            socket = null
            listenThread?.interrupt()
            listenThread = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
