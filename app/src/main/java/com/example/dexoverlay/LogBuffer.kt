package com.example.dexoverlay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {
    private val logs = mutableListOf<String>()
    
    @Volatile
    var totalLogsCount = 0L
        private set

    @Synchronized
    fun add(msg: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = sdf.format(Date())
        logs.add("[$timestamp] $msg")
        totalLogsCount++
        
        // Cap the memory buffer size at 1000 items
        if (logs.size > 1000) {
            logs.removeAt(0)
        }
    }

    @Synchronized
    fun getLogsAfter(afterTotalIndex: Long): List<String> {
        val countToFetch = (totalLogsCount - afterTotalIndex).toInt()
        if (countToFetch <= 0) return emptyList()

        val startIndex = (logs.size - countToFetch).coerceAtLeast(0)
        if (startIndex < logs.size) {
            return ArrayList(logs.subList(startIndex, logs.size))
        }
        return emptyList()
    }

    @Synchronized
    fun getLogs(): List<String> {
        return ArrayList(logs)
    }

    @Synchronized
    fun clear() {
        logs.clear()
        totalLogsCount = 0L
    }
}
