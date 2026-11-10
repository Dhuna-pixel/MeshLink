package com.meshlink.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class SpeechLevelTest {
    @Test fun normalizationRejectsNoiseAndInvalidValues() {
        listOf(-10f, 0f, 1.5f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(0f, SpeechLevel.normalize(it), 0f)
        }
        assertEquals(1f, SpeechLevel.normalize(100f), 0f)
        assertTrue(SpeechLevel.normalize(3f) < SpeechLevel.normalize(7f))
        assertTrue(SpeechLevel.normalize(7f) < SpeechLevel.normalize(12f))
    }
    private fun sustained(rms: Float): Float {
        val meter = SpeechLevel()
        var result = 0f
        for (time in 0L..500L step 20) {
            meter.sample(rms, time)
            result = meter.frame(time)
        }
        return result
    }
    @Test fun actualLoudnessScalesAndAttackIsSmooth() {
        assertTrue(sustained(3f) < sustained(7f))
        assertTrue(sustained(7f) < sustained(12f))
        val meter = SpeechLevel()
        meter.sample(12f, 0)
        meter.frame(0)
        assertTrue(meter.frame(16) in 0.01f..0.5f)
    }
    @Test fun missingCallbacksAndSilenceDecayToFlat() {
        val meter = SpeechLevel()
        assertEquals(0f, meter.frame(0), 0f)
        meter.sample(12f, 0)
        assertTrue(meter.frame(100) > 0f)
        for (time in 200L..2500L step 50) meter.frame(time)
        assertEquals(0f, meter.frame(2550), 0f)
        meter.sample(12f, 2600)
        assertTrue(meter.frame(2600) > 0f)
        for (time in 2650L..5000L step 50) {
            meter.sample(0f, time)
            meter.frame(time)
        }
        assertEquals(0f, meter.frame(5050), 0f)
    }
    @Test fun obsoleteSessionCannotAffectNewSession() {
        val draft = SpeechDraft()
        val old = draft.start()!!
        draft.cancel()
        draft.start()
        draft.rms(old, 12f, 0)
        draft.audioLevel.frame(0)
        assertEquals(0f, draft.audioLevel.frame(100), 0f)
    }
    @Test fun cancelErrorEndAndTeardownResetLevel() {
        repeat(4) { action ->
            val draft = SpeechDraft()
            val id = draft.start()!!
            draft.rms(id, 12f, 0)
            draft.audioLevel.frame(0)
            assertTrue(draft.audioLevel.frame(100) > 0f)
            when (action) {
                0 -> draft.cancel()
                1 -> draft.finish(id, "preserved") // Shared terminal error/result path.
                2 -> draft.endSpeech(id)
                else -> draft.dispose()
            }
            draft.rms(id, 12f, 150)
            draft.audioLevel.frame(150)
            assertEquals(0f, draft.audioLevel.frame(250), 0f)
        }
    }
}
