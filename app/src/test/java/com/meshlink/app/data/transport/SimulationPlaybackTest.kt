package com.meshlink.app.data.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulationPlaybackTest {
    @Test fun pauseStepSpeedResetAndRepeatedDelivery() = runTest {
        val simulator = SimulationTransport()
        val player = SimulationPlayback(simulator, backgroundScope)
        val before = simulator.state.value.clock
        advanceTimeBy(1000); assertEquals(before, simulator.state.value.clock)
        player.step(); assertTrue(simulator.state.value.clock > before)
        player.delayMillis = 50; player.play(); runCurrent(); advanceTimeBy(100)
        player.pause(); runCurrent()
        val paused = simulator.state.value
        advanceTimeBy(1000); assertEquals(paused, simulator.state.value)
        repeat(2) {
            simulator.send("transmission $it"); player.play(); advanceTimeBy(10_000)
            assertEquals("Delivered (destination ACK)", simulator.state.value.statuses[simulator.state.value.lastMessageId])
        }
        assertEquals(2, simulator.state.value.deliveries.size)
        player.reset(); assertFalse(player.playing.value)
        assertTrue(simulator.state.value.deliveries.isEmpty())
    }
}
