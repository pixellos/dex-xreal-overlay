package com.example.dexoverlay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {
    private val logs = mutableListOf<String>()

    @Synchronized
    fun add(msg: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = sdf.format(Date())
        logs.add("[$timestamp] $msg")
        if (logs.size > 500) {
            logs.removeAt(0)
        }
    }

    @Synchronized
    fun getLogs(): List<String> {
        return ArrayList(logs)
    }

    @Synchronized
    fun clear() {
        logs.clear()
    }
}
