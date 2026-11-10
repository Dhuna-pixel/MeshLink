package com.meshlink.app.data.transport

import org.junit.Assert.*
import org.junit.Test

class BleQueueBudgetTest {
    @Test fun fullDataQueueStillAdmitsControlAndAck() {
        val budget=BleQueueBudget()
        repeat(8) { assertTrue(budget.acquire(false)) }
        assertFalse(budget.acquire(false))
        repeat(16) { assertTrue(budget.acquire(true)) }
        assertFalse(budget.acquire(true)); assertEquals(24,budget.depth())
        repeat(8) { budget.release(false) }; repeat(16) { budget.release(true) }
        assertEquals(0,budget.depth()); assertTrue(budget.acquire(false))
    }
    @Test fun failedAdmissionAndDequeuesDoNotLeakCapacity() {
        val budget=BleQueueBudget()
        repeat(1000) { assertTrue(budget.acquire(false)); budget.release(false) }
        assertEquals(0,budget.depth())
    }
}
