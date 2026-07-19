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

    fun getAllConnectedUsbDevicesSummary(): String {
        try {
            val deviceList = usbManager.deviceList.values
            if (deviceList.isEmpty()) return "No USB devices detected on USB-C port"

            val sb = StringBuilder()
            for (dev in deviceList) {
                sb.append("VID:0x${Integer.toHexString(dev.vendorId).uppercase()} PID:0x${Integer.toHexString(dev.productId).uppercase()} - ${dev.deviceName}\n")
            }
            return sb.toString()
        } catch (e: Exception) {
            return "Error scanning USB devices: ${e.message}"
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

    fun startReading(device: UsbDevice): Boolean {
        try {
            val connection = usbManager.openDevice(device) ?: return false

            var targetIntf: UsbInterface? = null
            var targetEndpoint: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        targetIntf = intf
                        targetEndpoint = ep
                        break
                    }
                }
                if (targetEndpoint != null) break
            }

            if (targetIntf == null || targetEndpoint == null) return false
            if (!connection.claimInterface(targetIntf, true)) return false

            usbConnection = connection
            usbInterface = targetIntf
            isReading = true

            readThread = Thread {
                val buffer = ByteArray(64)
                var lastButtonState = false
                var lastPressTime = 0L

                val usbRequest = UsbRequest()
                val byteBuffer = ByteBuffer.wrap(buffer)
                usbRequest.initialize(connection, targetEndpoint)

                while (isReading) {
                    var bytesRead = connection.bulkTransfer(targetEndpoint, buffer, buffer.size, 100)

                    if (bytesRead < 16) {
                        usbRequest.queue(byteBuffer, buffer.size)
                        if (connection.requestWait() == usbRequest) {
                            bytesRead = buffer.size
                        }
                    }

                    if (bytesRead >= 16) {
                        val rawGyroX = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
                        val rawGyroY = ((buffer[5].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)

                        val gyroX = if (rawGyroX > 32767) rawGyroX - 65536 else rawGyroX
                        val gyroY = if (rawGyroY > 32767) rawGyroY - 65536 else rawGyroY

                        val deltaX = (gyroY / 500f)
                        val deltaY = -(gyroX / 500f)

                        if (Math.abs(deltaX) > 0.02f || Math.abs(deltaY) > 0.02f) {
                            onHeadMoveListener?.invoke(deltaX, deltaY)
                        }

                        val buttonPressed = (buffer[10].toInt() and 0x01) != 0 || (buffer[12].toInt() and 0x01) != 0
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
        val XREAL_VIDS = intArrayOf(0x3318, 0x28E5, 0x0483, 0x0403)
        val XREAL_PIDS = intArrayOf(0x0424, 0x0423, 0x0428, 0x0422, 0x0571, 0x0425)
        const val ACTION_USB_PERMISSION = "com.example.dexoverlay.USB_PERMISSION"
    }
}
