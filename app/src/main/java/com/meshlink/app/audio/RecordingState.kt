package com.meshlink.app.audio

/** Recorder callbacks and finalization share one state; UI mode is never the authority. */
class RecordingState {
    enum class Phase { IDLE, RECORDING, FINALIZING, READY }
    enum class Reason { MANUAL, DURATION, SIZE, ERROR, INTERRUPTION }
    @Volatile var phase = Phase.IDLE; private set
    @Volatile var generation = 0; private set
    @Volatile var reason: Reason? = null; private set
    @Synchronized fun start(): Int { generation++; phase = Phase.RECORDING; reason = null; return generation }
    @Synchronized fun signal(token: Int, event: Reason) {
        if (token == generation && phase == Phase.RECORDING && reason == null) reason = event
    }
    @Synchronized fun beginFinish(request: Reason): Boolean {
        if (phase != Phase.RECORDING) return false
        phase = Phase.FINALIZING; reason = request; return true
    }
    @Synchronized fun ready() { check(phase == Phase.FINALIZING); phase = Phase.READY }
    @Synchronized fun clear() { generation++; phase = Phase.IDLE; reason = null }
    companion object {
        fun duration(metadata: Int, elapsed: Long): Int =
            (if (metadata > 0) metadata.toLong() else elapsed).coerceIn(1, 30_000).toInt()
    }
}
