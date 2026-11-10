package com.meshlink.app.ui.components

import kotlin.math.exp

/** Only the current scalar envelope is retained; no audio or level history. */
internal class SpeechLevel {
    private var target = 0f
    private var lastSample = Long.MIN_VALUE
    private var lastFrame: Long? = null
    private var level = 0f

    fun sample(rmsDb: Float, nowMs: Long) {
        target = normalize(rmsDb)
        lastSample = nowMs
    }

    fun frame(nowMs: Long): Float {
        val elapsed = (nowMs - (lastFrame ?: nowMs)).coerceIn(0, 100)
        lastFrame = nowMs
        // Missing callbacks are silence, not permission to invent movement.
        val desired = if (lastSample == Long.MIN_VALUE || nowMs - lastSample > 160) 0f else target
        val timeConstant = if (desired > level) 65.0 else 180.0
        level += (desired - level) * (1.0 - exp(-elapsed / timeConstant)).toFloat()
        if (desired == 0f && level < 0.002f) level = 0f
        return level
    }

    fun reset() { target = 0f; level = 0f; lastSample = Long.MIN_VALUE; lastFrame = null }

    companion object {
        // Recognition services do not share calibrated SPL units. Keep a conservative
        // floor and bounded display range; this is a loudness indicator, not a meter.
        fun normalize(rmsDb: Float): Float =
            if (!rmsDb.isFinite()) 0f else ((rmsDb - 1.5f) / 10.5f).coerceIn(0f, 1f)
    }
}
