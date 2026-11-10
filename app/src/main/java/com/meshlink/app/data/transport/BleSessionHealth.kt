package com.meshlink.app.data.transport

/** Only concrete local liveness evidence; idle authenticated sessions are healthy. */
internal object BleSessionHealth {
    fun failure(ready: Boolean, workerActive: Boolean, writeStarted: Long?, now: Long, lastAuthenticatedRx: Long? = null): String? = when {
        ready && !workerActive -> "ready_without_worker"
        writeStarted != null && now - writeStarted > 12_000 -> "write_completion_overdue"
        ready && lastAuthenticatedRx != null && now - lastAuthenticatedRx > 100_000 -> "authenticated_rx_silent"
        else -> null
    }
}
