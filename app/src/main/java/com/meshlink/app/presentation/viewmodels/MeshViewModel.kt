package com.meshlink.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshlink.app.data.repository.MeshRepository
import com.meshlink.app.data.repository.TransportMode
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Main ViewModel for MeshLink Review III UI.
 *
 * Exposes observable [MeshUiState] and maps user UI actions to repository, BLE transport & routing logic.
 */
class MeshViewModel(
    private val repository: MeshRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeshUiState(preferences = repository.appPreferences.state.value))
    val preferences = repository.appPreferences.state
    var notificationPromptPending = false
    private var settingsReturnTab = MeshScreenTab.NODE_STATUS
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.appPreferences.state.collect { prefs -> _uiState.update { it.copy(preferences = prefs) } } }

        viewModelScope.launch { repository.demoTopology.collect { topology -> _uiState.update { it.copy(demoTopology = topology) } } }
        viewModelScope.launch {
            while (true) {
                _uiState.update { state ->
                    val options = com.meshlink.app.domain.network.Destinations.list(state.currentNode.nodeId, state.connectedNodes, state.knownNodes, state.routes, System.currentTimeMillis())
                    state.copy(destinations = options, selectedDestinationNodeIds = com.meshlink.app.domain.network.Destinations.retainedSelections(state.selectedDestinationNodeIds,options))
                }
                refreshNotificationStatus()
                delay(1000)
            }
        }
        viewModelScope.launch { repository.bluetoothStatus.collect { status -> _uiState.update { it.copy(bluetoothStatus = status) } } }
        viewModelScope.launch { repository.knownNodes.collect { nodes -> _uiState.update { it.copy(knownNodes = nodes) } } }
        viewModelScope.launch { repository.routes.collect { routes -> _uiState.update { it.copy(routes = routes) } } }
        viewModelScope.launch { repository.deliveryStatuses.collect { statuses -> _uiState.update { it.copy(deliveryStatuses = statuses) } } }
        viewModelScope.launch {
            combine(
                repository.currentNode,
                repository.isNetworkingActive,
                repository.transportMode,
                repository.discoveredNodes
            ) { current, active, mode, discovered ->
                _uiState.update { state ->
                    state.copy(
                        currentNode = current,
                        isNetworkingActive = active,
                        transportMode = mode,
                        discoveredNodes = discovered
                    )
                }
            }.collect {}
        }

        viewModelScope.launch {
            repository.connectedNodes.collect { connected ->
                _uiState.update { it.copy(connectedNodes = connected) }
            }
        }

        viewModelScope.launch {
            repository.messages.collect { messageList ->
                _uiState.update { it.copy(messages = messageList) }
            }
        }

        viewModelScope.launch {
            repository.networkLogs.collect { logList ->
                _uiState.update { it.copy(networkLogs = logList) }
            }
        }
    }

    // =========================================================================
    // UI NAVIGATION & INTERACTION
    // =========================================================================
    fun openSettings() { settingsReturnTab = _uiState.value.selectedTab; selectTab(MeshScreenTab.SETTINGS) }
    fun closeSettings() { selectTab(settingsReturnTab.takeUnless { it == MeshScreenTab.SETTINGS } ?: MeshScreenTab.NODE_STATUS) }
    fun setTheme(mode: com.meshlink.app.data.preferences.ThemeMode) = repository.appPreferences.setTheme(mode)
    fun setNotifications(enabled: Boolean) {
        repository.appPreferences.setNotifications(enabled)
        if (!enabled) repository.messageNotifier.cancelAll()
    }
    fun setPrivatePreview(hide: Boolean) {
        repository.appPreferences.setPrivatePreview(hide)
        repository.messageNotifier.cancelAll()
    }
    fun refreshNotificationStatus() { _uiState.update { it.copy(notificationSystemStatus = repository.messageNotifier.status()) } }
    fun markNotificationPermissionAsked() = repository.appPreferences.markPermissionAsked()
    fun openNotification(target: com.meshlink.app.domain.notifications.NotificationTarget) {
        val packet = repository.showReceivedMessage(target)
        _uiState.update { it.copy(selectedTab = MeshScreenTab.MESSAGING, isInspectingPacket = packet,
            notificationTapMessage = if (packet == null) "This received message is no longer in local history." else "") }
    }
    fun selectTab(tab: MeshScreenTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun showIdentityDialog(show: Boolean) {
        _uiState.update { it.copy(isEditingIdentityDialogShown = show) }
    }

    fun updateNodeIdentity(nodeId: String, nodeName: String) {
        repository.updateNodeIdentity(nodeId, nodeName)
        showIdentityDialog(false)
    }

    fun setTransportMode(mode: TransportMode) {
        if (startup.busy) return
        repository.setTransportMode(mode)
    }

    fun connectToNode(node: Node) {
        repository.connectToNeighbor(node)
    }

    fun disconnectNode(nodeId: String) {
        repository.disconnectNeighbor(nodeId)
    }

    fun updateSelectedDestination(destinationId: String) {
        _uiState.update { state ->
            state.copy(selectedDestinationNodeIds = com.meshlink.app.domain.network.Destinations.toggleSelection(
                state.selectedDestinationNodeIds, destinationId, state.destinations))
        }
    }

    fun updateMessageInput(text: String) {
        _uiState.update { it.copy(messageInputText = text) }
    }

    fun updateTtl(ttl: Int) {
        _uiState.update { it.copy(ttlSliderValue = ttl) }
    }

    fun setLogFilter(level: LogLevel?) {
        _uiState.update { it.copy(logFilterLevel = level) }
    }

    fun inspectPacket(packet: MessagePacket?) {
        _uiState.update { it.copy(isInspectingPacket = packet) }
    }

    fun clearLogs() {
        repository.clearLogs()
    }

    fun clearMessages() {
        repository.clearMessages()
    }

    // =========================================================================
    // CRC ERROR INJECTION CONTROL
    // =========================================================================
    fun toggleCrcErrorSimulation() {
        val newArmed = !_uiState.value.isCrcErrorSimulationArmed
        _uiState.update { it.copy(isCrcErrorSimulationArmed = newArmed) }
        repository.triggerCrcErrorSimulation(newArmed)
    }

    // =========================================================================
    // SEND MESSAGE DISPATCH
    // =========================================================================
    fun sendMessage() {
        val state = _uiState.value
        val text = state.messageInputText.trim()
        val destinations = com.meshlink.app.domain.network.Destinations.retainedSelections(
            state.selectedDestinationNodeIds, state.destinations)
        if (text.isEmpty() || destinations.isEmpty() || !state.isNetworkingActive) return

        // Independent unicast packets preserve each recipient's route, ACK and failure status.
        destinations.forEach { destinationId ->
            val destination = state.destinations.first { it.id == destinationId }
            repository.sendMessage(
                destinationId = destinationId,
                destinationName = destination.name,
                payload = text,
                ttl = state.ttlSliderValue
            )
        }

        // Reset CRC error simulation state after transmission
        _uiState.update { it.copy(messageInputText = "", isCrcErrorSimulationArmed = false) }
    }

    val startup = com.meshlink.app.domain.network.NetworkingStartup()
    fun syncStartup() { _uiState.update { it.copy(startupBusy = startup.busy, startupMessage = startup.message) } }
    fun startApprovedNetworking() { repository.requestNetworkingStart() }
    fun stopNetworking() { repository.requestNetworkingStop() }
    fun configureDemo(enabled: Boolean, text: String, chain: Boolean) {
        try {
            val ids = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            require(ids.all { it.length <= 80 } && ids.size <= 32) { "Use up to 32 node IDs, each at most 80 characters." }
            val topology = if (!enabled) com.meshlink.app.domain.network.DemoTopology()
                else if (chain) com.meshlink.app.domain.network.DemoTopology.chain(_uiState.value.currentNode.nodeId,ids)
                else {
                    require(ids.distinct().size == ids.size && _uiState.value.currentNode.nodeId !in ids) { "Use distinct remote IDs; exclude this phone." }
                    com.meshlink.app.domain.network.DemoTopology(true, ids.toSet())
                }
            repository.configureDemo(topology)
            _uiState.update { it.copy(demoMessage = if (enabled) "Applied. ${topology.permittedIds.size} permitted neighbors; both phones must allow each other." else "Normal BLE Mode restored; all demo restrictions removed.") }
        } catch (e: IllegalArgumentException) { _uiState.update { it.copy(demoMessage = e.message ?: "Invalid topology") } }
    }

    companion object {
        fun provideFactory(repository: MeshRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MeshViewModel(repository) as T
                }
            }
    }
}
