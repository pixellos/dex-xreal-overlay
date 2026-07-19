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
    private var isReading = false
    private val activeThreads = mutableListOf<Thread>()
    private var isReceiverRegistered = false

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onGlassesSingleTapListener: (() -> Unit)? = null
    var onDebugLogListener: ((log: String) -> Unit)? = null

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
                            log("USB Permission GRANTED for ${device?.deviceName}")
                            device?.let { startReading(it) }
                        } else {
                            log("USB Permission DENIED by user!")
                        }
                    } catch (e: Exception) {
                        log("Error handling USB permission broadcast: ${e.message}")
                    }
                }
            }
        }
    }

    private fun log(msg: String) {
        Log.d("XrealUsbDriver", msg)
        onDebugLogListener?.invoke(msg)
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
                log("Error registering receiver: ${e.message}")
            }
        }
    }

    fun findXrealDevice(): UsbDevice? {
        try {
            val deviceList = usbManager.deviceList.values
            log("Scanning USB Devices. Count=${deviceList.size}")

            for (device in deviceList) {
                val vidHex = "0x" + Integer.toHexString(device.vendorId).uppercase()
                val pidHex = "0x" + Integer.toHexString(device.productId).uppercase()
                log("Found USB Device -> VID:$vidHex PID:$pidHex Name:${device.deviceName} Interfaces:${device.interfaceCount}")
            }

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

            if (match != null) {
                log("MATCHED XREAL Device -> VID:0x${Integer.toHexString(match.vendorId).uppercase()} PID:0x${Integer.toHexString(match.productId).uppercase()}")
            }

            return match
        } catch (e: Exception) {
            log("Error scanning USB devices: ${e.message}")
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
            log("Requested USB Permission for VID:0x${Integer.toHexString(device.vendorId).uppercase()}")
        } catch (e: Exception) {
            log("Error requesting USB permission: ${e.message}")
        }
    }

    private fun sendXrealImuWakeupPacket(connection: UsbDeviceConnection, intfIndex: Int, outEndpoint: UsbEndpoint?): Boolean {
        return try {
            val enableCmd = ByteArray(64)
            enableCmd[0] = 0xAA.toByte()
            enableCmd[1] = 0xC5.toByte()
            enableCmd[2] = 0x00.toByte()
            enableCmd[3] = 0x01.toByte()

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
            log("Sent Stream ON Command to Intf $intfIndex. Result=$res")
            res >= 0
        } catch (e: Exception) {
            log("Wakeup packet exception on Intf $intfIndex: ${e.message}")
            false
        }
    }

    fun startReading(device: UsbDevice): Boolean {
        try {
            val connection = usbManager.openDevice(device) ?: run {
                log("Failed to open connection to device!")
                return false
            }

            log("Scanning ALL ${device.interfaceCount} interfaces for XREAL 1s (Targeting Intf 8, 9, 10)...")
            val targetInEndpoints = mutableListOf<Pair<Int, UsbEndpoint>>()

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val claimed = connection.claimInterface(intf, true)
                log("Interface $i claimed=$claimed (Endpoints: ${intf.endpointCount})")

                var outEp: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        targetInEndpoints.add(Pair(i, ep))
                        log("Found IN Endpoint 0x${Integer.toHexString(ep.address).uppercase()} on Interface $i")
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEp = ep
                    }
                }
                sendXrealImuWakeupPacket(connection, i, outEp)
            }

            if (targetInEndpoints.isEmpty()) {
                log("No IN endpoints found across any interface!")
                return false
            }

            usbConnection = connection
            isReading = true
            activeThreads.clear()

            log("Launching ${targetInEndpoints.size} concurrent reader threads for Interfaces 8, 9, 10...")

            for ((intfIndex, ep) in targetInEndpoints) {
                val t = Thread {
                    val buffer = ByteArray(64)
                    var lastButtonState = false
                    var lastPressTime = 0L
                    var packetCount = 0

                    val usbRequest = UsbRequest()
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    usbRequest.initialize(connection, ep)

                    log("Started listener on Intf $intfIndex Endpoint 0x${Integer.toHexString(ep.address).uppercase()}")

                    while (isReading) {
                        var bytesRead = connection.bulkTransfer(ep, buffer, buffer.size, 100)

                        if (bytesRead < 16) {
                            usbRequest.queue(byteBuffer, buffer.size)
                            if (connection.requestWait() == usbRequest) {
                                bytesRead = buffer.size
                            }
                        }

                        if (bytesRead >= 16) {
                            packetCount++
                            if (packetCount % 100 == 0) {
                                log("⚡ STREAM ACTIVE on Intf $intfIndex Ep 0x${Integer.toHexString(ep.address).uppercase()}! Packets: $packetCount")
                            }

                            val gyroX: Float
                            val gyroY: Float

                            if (bytesRead >= 38) {
                                val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                                gyroX = bb.getShort(8).toFloat() / 100.0f
                                gyroY = bb.getShort(10).toFloat() / 100.0f
                            } else {
                                val rawGyroX = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
                                val rawGyroY = ((buffer[5].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
                                val gX = if (rawGyroX > 32767) rawGyroX - 65536 else rawGyroX
                                val gY = if (rawGyroY > 32767) rawGyroY - 65536 else rawGyroY
                                gyroX = gX / 400f
                                gyroY = gY / 400f
                            }

                            val deltaX = gyroY * 0.5f
                            val deltaY = -gyroX * 0.5f

                            if (Math.abs(deltaX) > 0.005f || Math.abs(deltaY) > 0.005f) {
                                onHeadMoveListener?.invoke(deltaX, deltaY)
                            }

                            val btnBitmask = if (bytesRead >= 39) buffer[38].toInt() else 0
                            val buttonPressed = (btnBitmask and 0x08) != 0 || (btnBitmask and 0x01) != 0 || (buffer[10].toInt() and 0x01) != 0
                            val currentTime = System.currentTimeMillis()

                            if (buttonPressed && !lastButtonState) {
                                if (currentTime - lastPressTime > 250) {
                                    log("🎯 GLASSES TEMPLE BUTTON TAP DETECTED on Intf $intfIndex!")
                                    onGlassesSingleTapListener?.invoke()
                                    lastPressTime = currentTime
                                }
                            }
                            lastButtonState = buttonPressed
                        }
                    }
                }.apply { start() }

                activeThreads.add(t)
            }

            return true
        } catch (e: Exception) {
            log("Error starting reading: ${e.message}")
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
            for (t in activeThreads) {
                t.interrupt()
            }
            activeThreads.clear()
            usbConnection?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        val XREAL_VIDS = intArrayOf(0x3318, 0x04D8, 0x28E5, 0x0483, 0x0403, 0x0BDA)
        val XREAL_PIDS = intArrayOf(0x0425, 0x0429, 0x0424, 0x0423, 0x0428, 0x0432, 0x0426, 0x0303, 0x0571, 0x1100, 0x2100)
        const val ACTION_USB_PERMISSION = "com.xreal.hid.USB_PERMISSION"
    }
}
