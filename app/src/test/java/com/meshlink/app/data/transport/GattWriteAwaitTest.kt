package com.meshlink.app.data.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class GattWriteAwaitTest {
    @Test fun missingCallbackReleasesWriterForNewSession() = runTest {
        val mutex=Mutex(); var timedOut=false; var nextCompleted=false
        val missing=CompletableDeferred<Int>()
        val first=launch {
            try { mutex.withLock { awaitGattWrite(missing) } }
            catch (_: TimeoutCancellationException) { timedOut=true }
        }
        val next=launch { mutex.withLock { assertEquals(0,awaitGattWrite(CompletableDeferred(0))); nextCompleted=true } }
        runCurrent(); assertFalse(nextCompleted)
        advanceTimeBy(8001); runCurrent(); first.join(); next.join()
        assertTrue(timedOut); assertTrue(nextCompleted); assertFalse(mutex.isLocked)
    }
    @Test fun cancellationDoesNotHoldWriterPermit() = runTest {
        val mutex=Mutex()
        val first=launch { mutex.withLock { awaitGattWrite(CompletableDeferred()) } }
        runCurrent(); first.cancelAndJoin()
        assertFalse(mutex.isLocked)
        mutex.withLock { assertEquals(0,awaitGattWrite(CompletableDeferred(0))) }
    }
}
