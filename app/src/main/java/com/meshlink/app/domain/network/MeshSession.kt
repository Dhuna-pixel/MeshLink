package com.meshlink.app.domain.network

/** Service-owned lease. UI attachment/detachment never controls this lifecycle. */
class MeshSession(private val startTransport: () -> Unit, private val stopTransport: () -> Unit) {
    var active = false
        private set
    fun start() {
        if (active) return
        active = true
        try { startTransport() } catch (error: Exception) { stop(); throw error }
    }
    fun stop() {
        if (!active) return
        active = false
        stopTransport()
    }
}
