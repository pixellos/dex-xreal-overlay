package com.example.dexoverlay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealUsbImuDriver(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var isReading = false
    private var readThread: Thread? = null
    private var isReceiverRegistered = false

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onGlassesSingleTapListener: (() -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(cntx: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                synchronized(this) {
                    try {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { startReading(it) }
                        } else {
                            Log.e("XrealUsbDriver", "USB Permission DENIED by user!")
                        }
                    } catch (e: Exception) {
                        Log.e("XrealUsbDriver", "Error handling USB permission broadcast", e)
                    }
                }
            }
        }
    }

    fun registerPermissionReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(usbReceiver, filter)
                }
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("XrealUsbDriver", "Error registering receiver", e)
            }
        }
    }

    fun findXrealDevice(): UsbDevice? {
        try {
            val deviceList = usbManager.deviceList.values
            var match = deviceList.find { device ->
                device.vendorId in XREAL_VIDS || device.productId in XREAL_PIDS
            }

            if (match == null) {
                match = deviceList.find { device ->
                    var hasInterruptIn = false
                    for (i in 0 until device.interfaceCount) {
                        val intf = device.getInterface(i)
                        for (e in 0 until intf.endpointCount) {
                            if (intf.getEndpoint(e).direction == UsbConstants.USB_DIR_IN) {
                                hasInterruptIn = true
                                break
                            }
                        }
                    }
                    hasInterruptIn
                }
            }
            return match
        } catch (e: Exception) {
            Log.e("XrealUsbDriver", "Error scanning USB devices", e)
            return null
        }
    }

    fun hasUsbPermission(device: UsbDevice): Boolean {
        return try {
            usbManager.hasPermission(device)
        } catch (e: Exception) {
            false
        }
    }

    fun requestUsbPermission(device: UsbDevice) {
        try {
            registerPermissionReceiver()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            Log.e("XrealUsbDriver", "Error requesting USB permission", e)
        }
    }

    // Send XREAL 1s IMU Activation Stream Handshake (0xAA 0xC5)
    private fun sendXrealImuWakeupPacket(connection: UsbDeviceConnection, intfIndex: Int, outEndpoint: UsbEndpoint?): Boolean {
        return try {
            val enableCmd = ByteArray(64)
            enableCmd[0] = 0xAA.toByte()
            enableCmd[1] = 0xC5.toByte()
            enableCmd[2] = 0x00.toByte()
            enableCmd[3] = 0x01.toByte() // Stream ON command

            val res = if (outEndpoint != null) {
                connection.bulkTransfer(outEndpoint, enableCmd, enableCmd.size, 1000)
            } else {
                connection.controlTransfer(
                    0x21, // SET_REPORT
                    0x09, // HID SET_REPORT
                    0x0300,
                    intfIndex,
                    enableCmd, enableCmd.size, 1000
                )
            }
            Log.d("XrealUsbDriver", "Sent XREAL IMU Stream ON Activation Handshake. Result=$res")
            res >= 0
        } catch (e: Exception) {
            Log.e("XrealUsbDriver", "Failed to send stream activation handshake", e)
            false
        }
    }

    fun startReading(device: UsbDevice): Boolean {
        try {
            val connection = usbManager.openDevice(device) ?: return false

            var targetIntf: UsbInterface? = null
            var targetInEp: UsbEndpoint? = null
            var targetOutEp: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                connection.claimInterface(intf, true)

                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null

                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN && (ep.address == 0x84 || ep.address == 0x86 || targetInEp == null)) {
                        inEp = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEp = ep
                    }
                }

                if (inEp != null) {
                    targetIntf = intf
                    targetInEp = inEp
                    targetOutEp = outEp
                    sendXrealImuWakeupPacket(connection, i, outEp)
                    break
                }
            }

            if (targetIntf == null || targetInEp == null) return false

            usbConnection = connection
            usbInterface = targetIntf
            isReading = true

            val inEp = targetInEp

            readThread = Thread {
                val buffer = ByteArray(64)
                var lastButtonState = false
                var lastPressTime = 0L

                val usbRequest = UsbRequest()
                val byteBuffer = ByteBuffer.wrap(buffer)
                usbRequest.initialize(connection, inEp)

                while (isReading) {
                    var bytesRead = connection.bulkTransfer(inEp, buffer, buffer.size, 100)

                    if (bytesRead < 16) {
                        usbRequest.queue(byteBuffer, buffer.size)
                        if (connection.requestWait() == usbRequest) {
                            bytesRead = buffer.size
                        }
                    }

                    if (bytesRead >= 38) {
                        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

                        // Gyroscope Pitch (X) and Yaw (Y) angular velocity
                        val rawGyroX = bb.getShort(8).toFloat() / 100.0f
                        val rawGyroY = bb.getShort(10).toFloat() / 100.0f

                        val deltaX = rawGyroY * 0.5f
                        val deltaY = -rawGyroX * 0.5f

                        if (Math.abs(deltaX) > 0.01f || Math.abs(deltaY) > 0.01f) {
                            onHeadMoveListener?.invoke(deltaX, deltaY)
                        }

                        // Temple Buttons Bitmask at Byte Offset 38 (0x26)
                        val btnBitmask = buffer[38].toInt()
                        val buttonPressed = (btnBitmask and 0x08) != 0 || (btnBitmask and 0x01) != 0 || (buffer[10].toInt() and 0x01) != 0
                        val currentTime = System.currentTimeMillis()

                        if (buttonPressed && !lastButtonState) {
                            if (currentTime - lastPressTime > 250) {
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
            Log.e("XrealUsbDriver", "Error starting reading", e)
            return false
        }
    }

    fun stopReading() {
        isReading = false
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {}
        }
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
        val XREAL_VIDS = intArrayOf(0x3318, 0x04D8, 0x28E5, 0x0483, 0x0403)
        val XREAL_PIDS = intArrayOf(0x0425, 0x0429, 0x0424, 0x0423, 0x0428, 0x0432, 0x0426, 0x0303, 0x0571)
        const val ACTION_USB_PERMISSION = "com.xreal.hid.USB_PERMISSION"
    }
}
