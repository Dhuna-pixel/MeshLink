package com.meshlink.app.data.transport
import org.junit.Assert.*
import org.junit.Test
class BleConnectionPolicyTest {
    @Test fun boundedBackoffAndCapacity() {
        var time=0L; val policy=BleConnectionPolicy(4) { time }
        assertFalse(policy.mayConnect("p",4))
        repeat(5) { attempt ->
            assertTrue(policy.mayConnect("p",0));policy.attempted("p")
            assertFalse(policy.mayConnect("p",0));time += 2_000L shl attempt
        }
        assertFalse(policy.mayConnect("p",0));policy.reset();assertTrue(policy.mayConnect("p",0))
    }
    @Test fun discoveryDedupAndExpiry() {
        var time=0L;val policy=BleConnectionPolicy { time }
        assertTrue(policy.discovered("p"));assertFalse(policy.discovered("p"))
        time=30001;assertEquals(listOf("p"),policy.stale());assertTrue(policy.discovered("p"))
    }
}
