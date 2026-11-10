package com.meshlink.app.data.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FairMailboxTest {
    @Test fun controlAndReadsProgressUnderFullDataLoad() = runTest {
        val box=FairMailbox<Pair<Int,Int>> { it.first }
        repeat(8) { assertTrue(box.offer(2 to it)) }
        repeat(16) { assertTrue(box.offer(1 to it)) }
        repeat(30) { assertTrue(box.offer(0 to it)) }
        val first=List(6) { box.receive() }
        assertEquals(listOf(0,1,0,2,0,1),first.map { it.first })
        assertEquals(listOf(0,1,2),first.filter { it.first==0 }.map { it.second })
        assertFalse(box.offer(1 to 99) && box.offer(1 to 100) && box.offer(1 to 101))
    }
    @Test fun bulkProgressCannotBeStarvedByControl() = runTest {
        val box=FairMailbox<Int> { it }
        box.offer(2)
        repeat(12) { box.offer(1); box.offer(0) }
        assertTrue(List(6) { box.receive() }.contains(2))
    }
    @Test fun closeReleasesWaitingWorkerAndRejectsFurtherAdmission() = runTest {
        val box=FairMailbox<Int> { 0 }
        val worker=launch { box.receive() }
        runCurrent(); box.close(); runCurrent()
        assertTrue(worker.isCompleted); assertFalse(box.offer(0))
    }
    @Test fun fiveBatchesWithImmediateTextKeepAdvancing() = runTest {
        val box=FairMailbox<Pair<Int,String>> { it.first }
        repeat(5) { batch ->
            repeat(7) { box.offer(2 to "voice-$batch-$it") }
            box.offer(2 to "text-$batch"); box.offer(1 to "ack-$batch"); box.offer(0 to "reply-$batch")
            val drained=List(10) { box.receive().second }
            assertTrue(drained.contains("text-$batch")); assertTrue(drained.indexOf("ack-$batch")<3)
        }
    }
}
