package com.meshlink.app.domain.network
import org.junit.Assert.*
import org.junit.Test

class NetworkingStartupTest {
    private val off = BluetoothReadiness(true,true,false)
    @Test fun disabledRequestsSystemEnableOnce() {
        val gate=NetworkingStartup()
        assertEquals(StartupAction.REQUEST_ENABLE,gate.request(off))
        repeat(5) { assertEquals(StartupAction.NONE,gate.request(off)) }
        assertTrue(gate.busy)
    }
    @Test fun acceptedContinuesExactlyOnce() {
        val gate=NetworkingStartup();gate.request(off)
        assertEquals(StartupAction.START,gate.enableResult(true,off.copy(enabled=true)))
        assertEquals(StartupAction.NONE,gate.enableResult(true,off.copy(enabled=true)))
        assertEquals(StartupAction.NONE,gate.request(off.copy(enabled=true)))
        gate.finished();assertFalse(gate.busy)
    }
    @Test fun declinedDoesNotStartOrReprompt() {
        val gate=NetworkingStartup();gate.request(off)
        assertEquals(StartupAction.NONE,gate.enableResult(false,off));assertFalse(gate.busy)
        assertTrue(gate.message.contains("required"))
        assertEquals(StartupAction.NONE,gate.bluetoothChanged(off.copy(enabled=true)))
    }
    @Test fun permissionsPrecedeBluetoothAccess() {
        val gate=NetworkingStartup()
        assertEquals(StartupAction.REQUEST_PERMISSIONS,gate.request(off.copy(permitted=false)))
        assertEquals(StartupAction.REQUEST_ENABLE,gate.permissionsResult(off))
    }
    @Test fun denialIsClearAndAllowsUserRetry() {
        val gate=NetworkingStartup();gate.request(off.copy(permitted=false))
        assertEquals(StartupAction.NONE,gate.permissionsResult(off.copy(permitted=false)))
        assertTrue(gate.message.contains("permissions"));assertFalse(gate.busy)
        assertEquals(StartupAction.REQUEST_PERMISSIONS,gate.request(off.copy(permitted=false)))
    }
    @Test fun supportedEnabledStartsImmediatelyButNotWhenAlreadyRunning() {
        assertEquals(StartupAction.START,NetworkingStartup().request(off.copy(enabled=true)))
        assertEquals(StartupAction.NONE,NetworkingStartup().request(off.copy(enabled=true),running=true))
        val gate=NetworkingStartup();assertEquals(StartupAction.NONE,gate.request(off.copy(supported=false)))
        assertTrue(gate.message.contains("not supported"))
    }
    @Test fun delayedBluetoothBroadcastAndTimeoutCancellation() {
        val gate=NetworkingStartup();gate.request(off);gate.enableResult(true,off)
        assertEquals(NetworkingStartup.Phase.WAITING_FOR_ON,gate.phase)
        assertEquals(StartupAction.START,gate.bluetoothChanged(off.copy(enabled=true)))
        gate.cancel("Timed out");assertFalse(gate.busy)
        assertEquals(StartupAction.NONE,gate.bluetoothChanged(off.copy(enabled=true)))
    }
    @Test fun revocationDuringPromptFailsClosed() {
        val gate=NetworkingStartup();gate.request(off)
        assertEquals(StartupAction.NONE,gate.enableResult(true,off.copy(enabled=true,permitted=false)))
        assertFalse(gate.busy)
    }
}
