package com.meshlink.app.domain.models

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Log severity levels for filtering and visual styling in the engineering log console.
 */
enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * An immutable log entry representing an event in the MeshLink ad-hoc network.
 * Essential for the university Review II evaluation and technical demonstration.
 */
data class NetworkLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    val packetSummary: String? = null,
    val relatedNodeId: String? = null
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
