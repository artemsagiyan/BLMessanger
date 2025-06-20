package com.example.test2

import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType = LogType.INFO
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

enum class LogType {
    INFO, ERROR, DEBUG
} 