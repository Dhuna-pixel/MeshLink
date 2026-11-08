package com.meshlink.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshlink.app.data.repository.MeshRepository
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
    val identity = repository.identity
    val preferences = repository.appPreferences.state
    var notificationPromptPending = false
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.groups.collect { groups -> _uiState.update { it.copy(groups = groups) } } }
        viewModelScope.launch { repository.groupExits.collect { exits -> _uiState.update { it.copy(groupExits = exits) } } }
        viewModelScope.launch { repository.hiddenGroups.collect { hidden -> _uiState.update { it.copy(hiddenGroups = hidden) } } }
        viewModelScope.launch { repository.leftGroups.collect { groups -> _uiState.update { it.copy(leftGroups = groups) } } }
        viewModelScope.launch { repository.bleTransport.rejectedEndpoints.collect { rejected -> _uiState.update { it.copy(rejectedEndpoints = rejected) } } }
        viewModelScope.launch { repository.appPreferences.state.collect { prefs -> _uiState.update { it.copy(preferences = prefs) } } }

        viewModelScope.launch { repository.demoTopology.collect { topology -> _uiState.update { it.copy(demoTopology = topology) } } }
        viewModelScope.launch {
            while (true) {
                refreshDestinations()
                refreshNotificationStatus()
                delay(1000)
            }
        }
        viewModelScope.launch { repository.connectNotice.collect { notice -> _uiState.update { it.copy(connectNotice = notice) } } }
        viewModelScope.launch { repository.connectionProblem.collect { problem -> _uiState.update { it.copy(connectionProblem = problem) } } }
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
            combine(repository.messages, repository.unread, repository.loadingHistory) { messages, unread, loading -> Triple(messages, unread, loading) }.collect { (messageList, unread, loading) ->
                _uiState.update { it.copy(messages = messageList, unread = unread, loadingHistory = loading) }
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
        if (repository.hasReceivedMessage(target)) {
            val groupId = repository.notificationGroupId(target)
            if (groupId != null) openGroup(groupId) else openChat(target.senderId)
        }
        else _uiState.update { it.copy(selectedTab = MeshScreenTab.CHATS, notificationTapMessage = "This message is no longer in local history.") }
    }

    fun openChat(peerId: String) {
        _uiState.update { it.copy(selectedTab = MeshScreenTab.CONVERSATION, peerId = peerId, composeError = "",
            messageInputText = if (it.peerId == peerId) it.messageInputText else "", notificationTapMessage = "") }
    }

    fun openGroup(id: String) {
        _uiState.update { it.copy(selectedTab = MeshScreenTab.GROUP, groupId = id, messageInputText = "", composeError = "") }
    }
    fun updateGroupName(name: String) { _uiState.update { it.copy(groupName = name.take(64), composeError = "") } }
    fun createGroup() {
        val state = _uiState.value
        try {
            val group = repository.createGroup(state.groupName, state.selectedDestinationNodeIds)
            val saved = repository.sendGroup(group.id, "Created the group", created = true)
            openGroup(group.id)
            _uiState.update { it.copy(groupName = "", selectedDestinationNodeIds = emptySet(),
                composeError = if (saved) "" else "Group saved, but invitations could not be saved. Send a message to invite members.") }
        } catch (e: Exception) { _uiState.update { it.copy(composeError = e.message ?: "Could not create group") } }
    }
    fun leaveGroup() { repository.leaveGroup(_uiState.value.groupId) }
    fun inviteBack(member: String) {
        val saved = repository.inviteBack(_uiState.value.groupId, member)
        _uiState.update { it.copy(composeError = if (saved) "Invitation sent to the outgoing queue. Delivery requires a mesh connection." else "This member cannot be added back, or the invitation could not be saved.") }
    }

    fun deleteCurrentChat() {
        val state = _uiState.value
        val deleted = if (state.selectedTab == MeshScreenTab.GROUP) repository.deleteGroup(state.groupId)
            else repository.deleteConversation(state.peerId)
        if (deleted) {
            selectTab(MeshScreenTab.CHATS)
            _uiState.update { it.copy(messageInputText = "", peerId = "", groupId = "") }
        } else _uiState.update { it.copy(composeError = "Could not delete the saved conversation. Try again.") }
    }

    fun markRead(peerId: String, ids: Set<String>) = repository.markRead(peerId, ids)

    fun retry(packet: MessagePacket) = repository.retryMessage(packet.messageId)

    fun selectTab(tab: MeshScreenTab) {
        _uiState.update { it.copy(selectedTab = tab, composeError = "", notificationTapMessage = "",
            selectedDestinationNodeIds = emptySet(), messageInputText = if (tab == MeshScreenTab.COMPOSE || it.selectedTab == MeshScreenTab.COMPOSE) "" else it.messageInputText) }
    }

    fun showIdentityDialog(show: Boolean) {
        _uiState.update { it.copy(isEditingIdentityDialogShown = show) }
    }

    fun updateNodeIdentity(nodeId: String, nodeName: String, about: String) {
        repository.updateNodeIdentity(nodeId, nodeName, about)
        showIdentityDialog(false)
    }

    private fun refreshDestinations() {
        _uiState.update { state ->
            val pins = runCatching { identity.peers() }.getOrDefault(emptyList()).associateBy { it.id }
            val rejected = runCatching { identity.rejectedCards() }.getOrDefault(emptyList()).associateBy { it.id }
            val profiles = identity.profiles()
            val blocked = identity.blocked()
            val contacts = blocked.map { Node(it, lastSeenTimestamp = 0) } + (profiles.values.map { it.profile.card } + pins.values + rejected.values).map { Node(it.id, it.name, lastSeenTimestamp = 0) }
            val options = com.meshlink.app.domain.network.Destinations.list(state.currentNode.nodeId, state.connectedNodes, state.knownNodes + contacts, state.routes, System.currentTimeMillis()).map { option ->
                val profile = profiles[option.id]?.profile
                val label = when {
                    option.id in blocked -> "Blocked"
                    option.id in pins -> "Fingerprint verified"
                    identity.linkCard(option.id) != null -> "Fingerprint unverified"
                    option.id in rejected -> "Rejected"
                    else -> "Awaiting profile"
                }
                option.copy(name = profile?.card?.name ?: pins[option.id]?.name ?: option.name,
                    about = profile?.about.orEmpty(), verified = option.id in pins, status = option.status + " · " + label,
                    blocked = option.id in blocked, canMessage = identity.participant(option.id) != null)
            }
            state.copy(destinations = options, selectedDestinationNodeIds = com.meshlink.app.domain.network.Destinations.retainedSelections(state.selectedDestinationNodeIds,options))
        }
    }

    fun setBlocked(id: String, block: Boolean) {
        repository.setBlocked(id, block)
        refreshDestinations()
    }

    fun messageEveryone() {
        _uiState.update { state ->
            val recent = (state.knownNodes + state.connectedNodes).map { it.nodeId }.toSet()
            state.copy(selectedTab = MeshScreenTab.COMPOSE, messageInputText = "", composeError = "",
                selectedDestinationNodeIds = com.meshlink.app.domain.network.Destinations.everyone(state.destinations, recent))
        }
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
        _uiState.update { it.copy(messageInputText = text, composeError = "") }
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

    fun sendMessage() {
        val state = _uiState.value
        val text = state.messageInputText.trim()
        if (state.selectedTab == MeshScreenTab.GROUP) {
            if (text.isBlank() || !state.isNetworkingActive) return
            val saved = repository.sendGroup(state.groupId, text)
            _uiState.update { it.copy(messageInputText = if (saved) "" else it.messageInputText,
                composeError = if (saved) "" else "Could not save this group message. Check storage and group membership.") }
            return
        }
        val destinations = com.meshlink.app.domain.network.Destinations.retainedSelections(
            if (state.selectedTab == MeshScreenTab.CONVERSATION) setOf(state.peerId) else state.selectedDestinationNodeIds, state.destinations)
        if (text.isEmpty() || destinations.isEmpty() || !state.isNetworkingActive) return

        // Independent unicast packets preserve each recipient's route, ACK and failure status.
        val unsaved = destinations.filter { destinationId ->
            val destination = state.destinations.first { it.id == destinationId }
            repository.sendMessage(
                destinationId = destinationId,
                destinationName = destination.name,
                payload = text
            ) == null
        }

        if (unsaved.isNotEmpty()) {
            _uiState.update { it.copy(selectedDestinationNodeIds = unsaved.toSet(),
                composeError = "Could not save the message for ${unsaved.size} recipient(s). Check device storage and try again.") }
            return
        }
        _uiState.update { it.copy(messageInputText = "", composeError = "") }
        if (destinations.size == 1) openChat(destinations.first()) else selectTab(MeshScreenTab.CHATS)
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
