package com.meshlink.app.domain.network

data class BluetoothReadiness(val supported: Boolean, val permitted: Boolean, val enabled: Boolean)
enum class StartupAction { NONE, REQUEST_PERMISSIONS, REQUEST_ENABLE, START }

/** One user-initiated startup transaction, surviving Activity recreation through its ViewModel. */
class NetworkingStartup {
    enum class Phase { IDLE, PERMISSIONS, ENABLE, WAITING_FOR_ON, STARTING }
    var phase = Phase.IDLE; private set
    var message = ""; private set
    val busy get() = phase != Phase.IDLE
    fun request(state: BluetoothReadiness, running: Boolean = false): StartupAction {
        if (busy || running) return StartupAction.NONE
        message = ""
        return advance(state)
    }
    private fun advance(state: BluetoothReadiness): StartupAction = when {
        !state.supported -> cancel("Bluetooth LE is not supported on this device.")
        !state.permitted -> { phase = Phase.PERMISSIONS; StartupAction.REQUEST_PERMISSIONS }
        !state.enabled -> { phase = Phase.ENABLE; StartupAction.REQUEST_ENABLE }
        else -> { phase = Phase.STARTING; StartupAction.START }
    }
    fun permissionsResult(state: BluetoothReadiness): StartupAction {
        if (phase != Phase.PERMISSIONS) return StartupAction.NONE
        return if (!state.permitted) cancel("Bluetooth permissions are required. Tap Start to try again.") else advance(state)
    }
    fun enableResult(accepted: Boolean, state: BluetoothReadiness): StartupAction {
        if (phase != Phase.ENABLE) return StartupAction.NONE
        if (!accepted) return cancel("Bluetooth is required for BLE networking. The enable request was cancelled.")
        if (!state.permitted) return cancel("Bluetooth permission was revoked. Tap Start to try again.")
        if (state.enabled) { phase = Phase.STARTING; return StartupAction.START }
        phase = Phase.WAITING_FOR_ON
        message = "Waiting for Bluetooth to turn on…"
        return StartupAction.NONE
    }
    fun bluetoothChanged(state: BluetoothReadiness): StartupAction {
        if (phase != Phase.WAITING_FOR_ON || !state.enabled) return StartupAction.NONE
        return if (state.permitted) { phase = Phase.STARTING; StartupAction.START } else cancel("Bluetooth permission is required.")
    }
    fun finished() { phase = Phase.IDLE; message = "" }
    fun cancel(reason: String = ""): StartupAction { phase = Phase.IDLE; message = reason; return StartupAction.NONE }
}
