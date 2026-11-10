package com.meshlink.app.audio

import org.junit.Assert.*
import org.junit.Test

class RecordingStateTest {
    @Test fun manualStopsAtThreeFiveAndNearThirtySecondsRemainReady() {
        listOf(3000L,5000L,29500L).forEach { elapsed ->
            val state=RecordingState(); state.start()
            assertTrue(state.beginFinish(RecordingState.Reason.MANUAL))
            state.ready()
            assertEquals(elapsed.toInt(),RecordingState.duration(0,elapsed))
            assertFalse(state.beginFinish(RecordingState.Reason.DURATION))
            assertEquals(RecordingState.Phase.READY,state.phase)
        }
    }
    @Test fun lateCallbackAndDoubleStopCannotInvalidatePreview() {
        val state=RecordingState(); val token=state.start()
        state.beginFinish(RecordingState.Reason.MANUAL)
        state.signal(token,RecordingState.Reason.ERROR)
        state.ready()
        state.signal(token,RecordingState.Reason.DURATION)
        assertFalse(state.beginFinish(RecordingState.Reason.MANUAL))
        assertEquals(RecordingState.Reason.MANUAL,state.reason)
        assertEquals(RecordingState.Phase.READY,state.phase)
    }
    @Test fun genuineLimitsAndInterruptionAreDistinct() {
        listOf(RecordingState.Reason.DURATION,RecordingState.Reason.SIZE,RecordingState.Reason.INTERRUPTION,RecordingState.Reason.ERROR).forEach {
            val state=RecordingState(); val token=state.start(); state.signal(token,it)
            assertEquals(it,state.reason); assertTrue(state.beginFinish(it)); state.ready()
        }
    }
    @Test fun oldRecorderCannotStopNewRecording() {
        val state=RecordingState(); val old=state.start(); state.clear(); state.start()
        state.signal(old,RecordingState.Reason.ERROR)
        assertNull(state.reason)
        assertEquals(RecordingState.Phase.RECORDING,state.phase)
    }
    @Test fun missingMetadataFallsBackToElapsedMilliseconds() {
        assertEquals(5000,RecordingState.duration(-1,5000))
        assertEquals(3000,RecordingState.duration(3000,5000))
        assertEquals(30000,RecordingState.duration(0,30500))
    }
}
