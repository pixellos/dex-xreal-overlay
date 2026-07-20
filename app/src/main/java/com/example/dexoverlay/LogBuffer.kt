package com.example.dexoverlay

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBuffer {
    private val logs = ArrayDeque<String>(1005)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    var totalLogsCount = 0L
        private set

    @Synchronized
    fun add(msg: String) {
        val timestamp = dateFormat.format(Date())
        logs.addLast("[$timestamp] $msg")
        totalLogsCount++
        
        // O(1) buffer capacity enforcement
        if (logs.size > 1000) {
            logs.removeFirst()
        }
    }

    @Synchronized
    fun getLogsAfter(afterTotalIndex: Long): List<String> {
        val countToFetch = (totalLogsCount - afterTotalIndex).toInt()
        if (countToFetch <= 0) return emptyList()

        val list = logs.toList()
        val startIndex = (list.size - countToFetch).coerceAtLeast(0)
        return if (startIndex < list.size) {
            list.subList(startIndex, list.size)
        } else {
            emptyList()
        }
    }

    @Synchronized
    fun getLogs(): List<String> = logs.toList()

    @Synchronized
    fun clear() {
        logs.clear()
        totalLogsCount = 0L
    }
}
