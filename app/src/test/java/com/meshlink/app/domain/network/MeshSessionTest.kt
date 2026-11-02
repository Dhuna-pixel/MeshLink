package com.meshlink.app.domain.network

import org.junit.Assert.*
import org.junit.Test

class MeshSessionTest {
    @Test fun duplicateServiceStartsShareOneTransport() {
        var starts = 0; var stops = 0
        val session = MeshSession({ starts++ }, { stops++ })
        session.start(); session.start()
        assertEquals(1, starts); assertTrue(session.active)
        session.stop(); session.stop()
        assertEquals(1, stops); assertFalse(session.active)
    }
    @Test fun startFailureCleansResourcesAndAllowsExplicitRetry() {
        var stops = 0; var fail = true
        val session = MeshSession({ if (fail) error("Bluetooth lost") }, { stops++ })
        try { session.start(); fail("expected failure") } catch (_: IllegalStateException) { }
        assertFalse(session.active); assertEquals(1, stops)
        fail = false; session.start(); assertTrue(session.active)
        session.stop(); assertEquals(2, stops)
    }
    @Test fun explicitStopAndBluetoothLossUseSameIdempotentCleanup() {
        var resources = 0
        val session = MeshSession({ resources++ }, { resources-- })
        repeat(3) { session.start(); assertEquals(1, resources); session.stop(); session.stop(); assertEquals(0, resources) }
    }
}
