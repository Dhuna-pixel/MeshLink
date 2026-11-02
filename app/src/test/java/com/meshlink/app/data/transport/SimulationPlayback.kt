package com.meshlink.app.data.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Playback advances the actual virtual clock; pause leaves topology and queued packets intact. */
class SimulationPlayback(private val simulator: SimulationTransport, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0L
    private val _playing = MutableStateFlow(false)
    val playing = _playing.asStateFlow()
    var delayMillis = 180L
        set(value) { field = value.coerceIn(50, 2000) }
    fun pause() { generation++; job?.cancel(); job = null; _playing.value = false }
    fun play() {
        if (job?.isActive == true) return
        val run = ++generation
        _playing.value = true
        job = scope.launch {
            try { while (simulator.state.value.hasWork) { simulator.step(); delay(delayMillis) } }
            finally { if (run == generation) _playing.value = false }
        }
    }
    fun step() { pause(); simulator.step() }
    fun reset() { pause(); simulator.reset() }
}
