package com.example.dexoverlay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

class XrealUsbImuDriver(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var isReading = false
    private var readThread: Thread? = null

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onGlassesSingleTapListener: (() -> Unit)? = null

    fun findXrealDevice(): UsbDevice? {
        return usbManager.deviceList.values.find { device ->
            device.vendorId == XREAL_VID && (device.productId in XREAL_PIDS)
        }
    }

    fun hasUsbPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestUsbPermission(device: UsbDevice, pendingIntent: android.app.PendingIntent) {
        usbManager.requestPermission(device, pendingIntent)
    }

    fun startReading(device: UsbDevice): Boolean {
        try {
            val connection = usbManager.openDevice(device) ?: return false
            
            var targetIntf: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.endpointCount > 0) {
                    targetIntf = intf
                    break
                }
            }

            if (targetIntf == null) return false
            if (!connection.claimInterface(targetIntf, true)) return false

            usbConnection = connection
            usbInterface = targetIntf
            isReading = true

            val endpoint = targetIntf.getEndpoint(0)

            readThread = Thread {
                val buffer = ByteArray(64)
                var lastButtonState = false
                var lastPressTime = 0L

                while (isReading) {
                    val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, 100)
                    if (bytesRead >= 16) {
                        // Gyro Angular Velocity for Head Tracking
                        val rawGyroX = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
                        val rawGyroY = ((buffer[5].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)

                        val gyroX = if (rawGyroX > 32767) rawGyroX - 65536 else rawGyroX
                        val gyroY = if (rawGyroY > 32767) rawGyroY - 65536 else rawGyroY

                        val deltaX = (gyroY / 1000f)
                        val deltaY = -(gyroX / 1000f)

                        if (Math.abs(deltaX) > 0.05f || Math.abs(deltaY) > 0.05f) {
                            onHeadMoveListener?.invoke(deltaX, deltaY)
                        }

                        // XREAL Quick Action Multi-Function Temple Button (Byte 10 or 12)
                        val buttonPressed = (buffer[10].toInt() and 0x01) != 0 || (buffer[12].toInt() and 0x01) != 0
                        val currentTime = System.currentTimeMillis()

                        // Single Tap Detection logic
                        if (buttonPressed && !lastButtonState) {
                            if (currentTime - lastPressTime > 250) { // Debounce threshold
                                onGlassesSingleTapListener?.invoke()
                                lastPressTime = currentTime
                            }
                        }
                        lastButtonState = buttonPressed
                    }
                }
            }.apply { start() }

            return true
        } catch (e: Exception) {
            Log.e("XrealUsbImuDriver", "Failed to start IMU reading", e)
            return false
        }
    }

    fun stopReading() {
        isReading = false
        try {
            readThread?.interrupt()
            readThread = null
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val XREAL_VID = 0x3318
        val XREAL_PIDS = intArrayOf(0x0424, 0x0423, 0x0428, 0x0422)
    }
}
