package com.meshlink.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class SpeechDraftTest {
    @Test fun recognitionAppendsToEditableDraftOnce() {
        val draft = SpeechDraft()
        draft.start()
        assertTrue(draft.active)
        assertEquals("Typed spoken", draft.result("Typed", "spoken"))
        assertFalse(draft.active)
        assertEquals("Edited", draft.result("Edited", "duplicate"))
    }
    @Test fun canceledResultsCannotChangeComposer() {
        val draft = SpeechDraft()
        draft.start()
        draft.cancel()
        assertFalse(draft.active)
        assertEquals("Keep this", draft.result("Keep this", "discard"))
        draft.start()
        assertEquals("new", draft.result("", "new"))
    }

    @Test fun disconnectPreservesPartialAndManualRetrySucceeds() {
        val draft = SpeechDraft()
        val first = draft.start()!!
        draft.partial(first, "heard words")
        val preserved = draft.finish(first, "Typed  ")
        assertEquals("Typed  heard words", preserved)
        assertFalse(draft.active)
        val retry = draft.start()!!
        assertNotEquals(first, retry)
        assertEquals("Typed  heard words retry", draft.finish(retry, preserved, "retry"))
    }
    @Test fun rapidStartsCannotOverlap() {
        val draft = SpeechDraft()
        val first = draft.start()!!
        repeat(20) { assertNull(draft.start()) }
        assertTrue(draft.owns(first))
    }
    @Test fun cancelRestartIgnoresAllOldCallbacks() {
        val draft = SpeechDraft()
        val old = draft.start()!!
        draft.cancel()
        val fresh = draft.start()!!
        draft.partial(old, "obsolete")
        assertEquals("Unchanged", draft.finish(old, "Unchanged", "late result"))
        assertTrue(draft.owns(fresh))
        assertEquals("Unchanged valid", draft.finish(fresh, "Unchanged", "valid"))
    }
    @Test fun teardownRejectsCallbacksAndNewCapture() {
        val draft = SpeechDraft()
        val token = draft.start()!!
        draft.dispose()
        assertFalse(draft.owns(token))
        assertNull(draft.start())
        assertEquals("Keep whitespace  ", draft.finish(token, "Keep whitespace  ", "late"))
    }
    @Test fun emptyFailurePreservesComposerExactly() {
        val draft = SpeechDraft()
        assertEquals("typed  ", draft.finish(draft.start()!!, "typed  "))
    }
}
