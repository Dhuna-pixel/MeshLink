package com.meshlink.app.data.transport
import org.junit.Assert.*
import org.junit.Test
class BleConnectionPolicyTest {
    @Test fun manualRetryResumesPausedAndExhaustedPeerWithoutBypassingCapacity() {
        val policy = BleConnectionPolicy(2) { 0L }
        repeat(5) { policy.attempted("a") }
        policy.pause("a", "peer-token")
        assertFalse(policy.mayConnect("a",0))
        assertTrue(policy.paused("rotated", "peer-token"))
        policy.requestConnection("rotated", "peer-token")
        assertFalse(policy.paused("a"))
        policy.requestConnection("a")
        assertTrue(policy.mayConnect("a",0))
        assertFalse(policy.mayConnect("a",2))
    }
    @Test fun reconnectUsesFreshAdvertiserAddressAcrossAddressRotation() {
        var time = 0L
        val policy = BleConnectionPolicy { time }
        policy.advertised("peer","old")
        policy.advertised("peer","new")
        assertEquals("new",policy.advertisedAddress("peer"))
        time += 30_001
        assertNull(policy.advertisedAddress("peer"))
    }
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
    @Test fun expiredLinksRequireFreshAuthentication() {
        assertFalse(BleConnectionPolicy.sessionExpired(59_999,false))
        assertTrue(BleConnectionPolicy.sessionExpired(60_000,false))
        assertFalse(BleConnectionPolicy.sessionExpired(3_599_999,true))
        assertTrue(BleConnectionPolicy.sessionExpired(3_600_000,true))
    }
    @Test fun visiblePeerSurvivesRetryExhaustionAndPartialAdvertisements() {
        var time = 0L
        val policy = BleConnectionPolicy { time }
        assertTrue(policy.discovered("p"))
        assertTrue(policy.discovered("p","token"))
        repeat(5) {
            policy.attempted("p")
            time += 20_000
            assertFalse(policy.discovered("p"))
            assertTrue(policy.stale().isEmpty())
        }
        assertFalse(policy.mayConnect("p",0))
        assertFalse(policy.discovered("p","token"))
        policy.ready("p")
        assertTrue(policy.discovered("p","token"))
    }
}
