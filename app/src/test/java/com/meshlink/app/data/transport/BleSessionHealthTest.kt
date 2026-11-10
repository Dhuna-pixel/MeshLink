package com.meshlink.app.data.transport

import org.junit.Assert.*
import org.junit.Test

class BleSessionHealthTest {
    @Test fun deadReadyWorkerRequiresTeardown() {
        assertEquals("ready_without_worker",BleSessionHealth.failure(true,false,null,0))
        assertNull(BleSessionHealth.failure(false,false,null,0))
    }
    @Test fun missingCallbackHasFiniteHealthDeadline() {
        assertNull(BleSessionHealth.failure(true,true,1000,9000))
        assertEquals("write_completion_overdue",BleSessionHealth.failure(true,true,1000,13001))
        assertNull(BleSessionHealth.failure(true,true,null,13001))
    }
    @Test fun idleAndReconnectedSessionAreNotRestarted() {
        assertNull(BleSessionHealth.failure(true,true,null,Long.MAX_VALUE))
        repeat(3) {
            assertEquals("ready_without_worker",BleSessionHealth.failure(true,false,null,0))
            assertNull(BleSessionHealth.failure(true,true,null,0))
        }
    }

    @Test fun asymmetricFailureCannotRemainReadyFromSuccessfulLocalWrites() {
        // Local writes do not refresh authenticated inbound evidence.
        assertEquals("ready_without_worker",BleSessionHealth.failure(true,false,null,1000,0))
        assertNull(BleSessionHealth.failure(true,true,null,99000,0))
        assertEquals("authenticated_rx_silent",BleSessionHealth.failure(true,true,null,100001,0))
        // Fresh authenticated session establishes a fresh inbound timestamp.
        assertNull(BleSessionHealth.failure(true,true,null,101000,101000))
        assertNull(BleSessionHealth.failure(true,true,null,131000,130000))
    }
}
