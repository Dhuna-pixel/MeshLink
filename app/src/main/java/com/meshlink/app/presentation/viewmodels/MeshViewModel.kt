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
 * Main ViewModel for MeshLink UI.
 *
 * Exposes observable [MeshUiState] and maps user UI actions to repository & routing logic.
 */
class MeshViewModel(
    private val repository: MeshRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    init {
        // Combine repository flows into single unified UI state
        viewModelScope.launch {
            combine(
                repository.currentNode,
                repository.isNetworkingActive,
                repository.isSimulationMode,
                repository.transportMode,
                repository.discoveredNodes
            ) { current, active, sim, mode, discovered ->
                _uiState.update { state ->
                    state.copy(
                        currentNode = current,
                        isNetworkingActive = active,
                        isSimulationMode = sim,
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

    fun toggleNetworking() {
        if (_uiState.value.isNetworkingActive) {
            repository.stopMesh()
        } else {
            repository.startNetworking(_uiState.value.transportMode)
        }
    }

    fun setTransportMode(mode: TransportMode) {
        _uiState.update { it.copy(transportMode = mode, isSimulationMode = (mode == TransportMode.SIMULATION)) }
        repository.setTransportMode(mode)
    }

    fun setSimulationMode(isSim: Boolean) {
        val mode = if (isSim) TransportMode.SIMULATION else TransportMode.NEARBY_CONNECTIONS
        setTransportMode(mode)
    }

    fun connectToNode(node: Node) {
        repository.connectToNeighbor(node)
    }

    fun disconnectNode(nodeId: String) {
        repository.disconnectNeighbor(nodeId)
    }

    fun updateSelectedDestination(destinationId: String) {
        _uiState.update { it.copy(selectedDestinationNodeId = destinationId) }
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
    // SEND MESSAGE DISPATCH
    // =========================================================================
    fun sendMessage() {
        val state = _uiState.value
        val text = state.messageInputText.trim()
        val destinationId = state.selectedDestinationNodeId.trim()

        if (text.isEmpty() || destinationId.isEmpty()) return

        val destNode = (state.connectedNodes + state.discoveredNodes).find { it.nodeId == destinationId }
        val destName = destNode?.nodeName ?: destinationId

        repository.sendMessage(
            destinationId = destinationId,
            destinationName = destName,
            payload = text,
            ttl = state.ttlSliderValue
        )

        // Clear input field after sending
        _uiState.update { it.copy(messageInputText = "") }
    }

    // =========================================================================
    // 3-NODE MULTI-HOP SIMULATION DEMONSTRATION
    // =========================================================================
    fun runThreeNodeSimulationDemo() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunningSimulationAnimation = true,
                    simulationStepDescription = "Step 1/3: Node A generating message for Node C (TTL: 5, Hops: 0)..."
                )
            }

            // Ensure simulation mode is active
            if (!_uiState.value.isSimulationMode || !_uiState.value.isNetworkingActive) {
                setSimulationMode(true)
            }

            delay(800)

            _uiState.update {
                it.copy(simulationStepDescription = "Step 2/3: Node A transmitting packet to Relay Node B...")
            }

            repository.sendMessage(
                destinationId = "NODE-C",
                destinationName = "Node C (Destination)",
                payload = "Hello Node C! This is a multi-hop test from Node A via relay Node B.",
                ttl = 5
            )

            delay(1200)

            _uiState.update {
                it.copy(simulationStepDescription = "Step 3/3: Relay Node B processed packet, updated TTL to 4, Hops to 1, forwarded to Node C!")
            }

            delay(1500)

            _uiState.update {
                it.copy(
                    isRunningSimulationAnimation = false,
                    simulationStepDescription = "Demo complete! Node C successfully delivered the packet."
                )
            }
        }
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
