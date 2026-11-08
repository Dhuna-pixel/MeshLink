package com.meshlink.app.data.repository

import android.content.Context
import com.meshlink.app.data.transport.AndroidBleGattTransport
import com.meshlink.app.data.transport.MeshTransport
import com.meshlink.app.security.*
import com.meshlink.app.data.transport.TransportEvent
import com.meshlink.app.domain.models.ForwardingDecision
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.domain.routing.RoutingEngine
import com.meshlink.app.utils.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Transport execution modes supported by MeshLink.
 */
enum class TransportMode {
    /** Primary Review III Transport: Hardware BLE GATT Dual-Role mesh */
    BLE_GATT
}

/**
 * Central repository managing the MeshLink node state, network discovery, message routing,
 * and event logging.
 *
 * Coordinates between the [RoutingEngine] (pure domain logic) and the active [MeshTransport].
 */
class MeshRepository(
    private val context: Context,
    private val routingEngine: RoutingEngine = RoutingEngine()
) {
    val appPreferences = com.meshlink.app.data.preferences.AppPreferencesStore(
        com.meshlink.app.data.preferences.AndroidPreferenceStorage(context,"mesh_settings"))
    val messageNotifier = com.meshlink.app.data.notifications.AndroidMessageNotifier(context)
    val identity = Identity(context)
    private val journal = MessageJournal(context)
    private val receivedInbox by lazy {
        val vault = Vault(context,"mesh_inbox_v2")
        val legacy = context.getSharedPreferences("mesh_received",Context.MODE_PRIVATE)
        legacy.getString("received",null)?.let { old ->
            if (vault.get("received") == null) vault.put("received",old)
            check(legacy.edit().remove("received").commit())
        }
        com.meshlink.app.domain.notifications.ReceivedMessageStore(vault)
    }
    private val deliveryNotifications by lazy {
        com.meshlink.app.domain.notifications.DeliveredMessageNotifications(
            localId = { _currentNode.value.nodeId }, preferences = { appPreferences.state.value },
            systemAllowed = messageNotifier::permitted, inbox = receivedInbox,
            senderName = { id -> identity.participant(id)?.name },
            post = messageNotifier::post)
    }
    fun hasReceivedMessage(target: com.meshlink.app.domain.notifications.NotificationTarget): Boolean {
        val owner = _currentNode.value.nodeId
        return target.senderId != owner && (journal.contains(owner,target.senderId,target.messageId) || receivedInbox.find(owner,target) != null)
    }
    fun notificationGroupId(target: com.meshlink.app.domain.notifications.NotificationTarget): String? {
        val packet = messages.value.find { it.sourceId == target.senderId && it.messageId == target.messageId }
            ?: journal.load(currentNode.value.nodeId).firstOrNull { it.first.sourceId == target.senderId && it.first.messageId == target.messageId }?.first
            ?: receivedInbox.find(currentNode.value.nodeId, target)
        return packet?.group?.chat?.id
    }
    private val repositoryScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val preferences = context.getSharedPreferences("mesh_identity", Context.MODE_PRIVATE)
    private val stableId = preferences.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
        preferences.edit().putString("id", it).apply()
    }
    private val groupStore = GroupStore(Vault(context, "mesh_groups_v1"), stableId)
    val groups = MutableStateFlow(groupStore.chats())
    val leftGroups = MutableStateFlow(groupStore.left())
    val hiddenGroups = MutableStateFlow(groupStore.hidden())
    val groupExits = MutableStateFlow(groupStore.exits())
    private fun refreshGroups() {
        groups.value = groupStore.chats()
        hiddenGroups.value = groupStore.hidden()
        leftGroups.value = groupStore.left()
        groupExits.value = groupStore.exits()
    }

    private fun packetGroup(packet: MessagePacket): GroupContext? = packet.group ?: messages.value.find {
        if (packet.ackFor != null) it.messageId == packet.ackFor && it.sourceId == packet.destinationId && it.destinationId == packet.sourceId
        else it.messageId == packet.messageId && it.sourceId == packet.sourceId && it.destinationId == packet.destinationId
    }?.group

    internal fun outgoingPeer(packet: MessagePacket): PeerCard? {
        val group = packetGroup(packet)
        if (group == null) return identity.participant(packet.destinationId)
        if (packet.ackFor == null && group.chat.id in leftGroups.value && group.kind != "exit") return null
        if (group.kind in setOf("created", "invite") && packet.destinationId in identity.blocked()) return null
        return identity.linkCard(packet.destinationId)?.takeIf { group.chat.member(it.id)?.sameKeys(it) == true }
    }

    internal fun incomingAllowed(packet: MessagePacket): Boolean {
        packet.group?.let { return groupStore.canReceive(it, packet.sourceId, identity.blocked()) }
        if (packet.ackFor != null) {
            val original = messages.value.find { it.sourceId == packet.destinationId && it.destinationId == packet.sourceId && it.messageId == packet.ackFor }
            if (original?.group != null) return true
        }
        return packet.sourceId !in identity.blocked()
    }

    fun createGroup(name: String, memberIds: Set<String>): GroupChat {
        require(name.trim().isNotEmpty() && name.trim().length <= 64)
        require(memberIds.size in 1 until GroupChat.MAX_MEMBERS && stableId !in memberIds)
        val members = listOf(identity.profile(currentNode.value)) + memberIds.sorted().map {
            requireNotNull(identity.participant(it)) { "A selected participant is unavailable or blocked" }
            requireNotNull(identity.profiles()[it]) { "Wait for this participant's signed profile" }
        }
        val chat = GroupChat(java.util.UUID.randomUUID().toString(), name.trim(), stableId, members).sign(identity.signing())
        chat.verify()
        groupStore.remember(chat)
        groups.value = groupStore.chats()
        return chat
    }

    fun leaveGroup(id: String) {
        if (id in leftGroups.value) return
        val exit = groupStore.leave(id)
        refreshGroups()
        messages.value.filter { it.sourceId == stableId && it.group?.chat?.id == id }.forEach {
            aodv?.cancelMessage(it.messageId, "Failed: you left the group")
        }
        sendGroupEvent(id, "Left the group (${exit.count}/3 exits)", "exit", exit = exit)
    }

    fun inviteBack(id: String, member: String): Boolean {
        val exit = groupStore.exit(id, member)
        if (!exit.left || exit.count >= 3 || member in identity.blocked() || id in leftGroups.value) return false
        return sendGroupEvent(id, "Invited you back to the group", "invite", member, exit)
    }

    internal fun deliverIncoming(packet: MessagePacket) {
        require(incomingAllowed(packet))
        acceptGroup(packet)
        val local = currentNode.value.nodeId
        if (!journal.deliverOnce(local, packet)) return
        packet.group?.let { groupStore.show(it.chat.id); refreshGroups() }
        _messages.update { (listOf(packet) + it.filterNot { old -> old.sourceId == packet.sourceId && old.messageId == packet.messageId }).take(500) }
        unread.value = journal.unread(local)
        runCatching { deliveryNotifications.onDelivered(packet) }
        log("DELIVER", "Message delivered: ${packet.getSummary()}", LogLevel.SUCCESS)
    }

    private fun acceptGroup(packet: MessagePacket) {
        val chat = packet.group?.chat ?: return
        require(incomingAllowed(packet))
        val local = identity.profile(currentNode.value).profile.card
        require(chat.member(local.id)?.sameKeys(local) == true)
        groups.value.find { it.id == chat.id }?.let { require(it == chat) }
        chat.members.filter { it.profile.card.id != local.id }.forEach { identity.learn(it, local) }
        groupStore.remember(chat)
        val rejoining = packet.group.kind == "invite" && chat.id in leftGroups.value
        groupStore.applyEvent(packet.group, packet.sourceId)
        refreshGroups()
        if (rejoining) sendGroupEvent(chat.id, "Rejoined the group", "joined", exit = groupStore.exit(chat.id, stableId))
    }

    fun sendGroup(id: String, text: String, created: Boolean = false): Boolean =
        sendGroupEvent(id, text, if (created) "created" else "message")

    private fun sendGroupEvent(id: String, text: String, kind: String, only: String? = null, exit: GroupExit = GroupExit()): Boolean {
        val chat = groups.value.find { it.id == id } ?: return false
        if ((id in leftGroups.value && kind != "exit") || text.toByteArray().size !in 1..2000) return false
        val current = currentNode.value
        // A normal message carries the author's rejoin generation, repairing a missed joined notice.
        val generation = if (kind == "message") groupStore.exit(id, current.nodeId) else exit
        val group = GroupContext(chat, java.util.UUID.randomUUID().toString(), kind, generation.count, generation.token)
        val packets = chat.members.map { it.profile.card }.filter { it.id != current.nodeId && (only == null || it.id == only) &&
            (kind in setOf("exit", "invite", "joined") || !groupStore.exit(id, it.id).left) }.map { member ->
            routingEngine.createLocalPacket(IdGenerator.generateMessageId(), current.nodeId, current.nodeName,
                member.id, member.name, text, 16).copy(group = group)
        }
        if (packets.isEmpty()) return false
        return try {
            journal.saveBatch(current.nodeId, packets)
            _messages.update { (packets + it).take(500) }
            packets.forEach(::dispatch)
            true
        } catch (_: Exception) { storageFailure(); false }
    }

    private var aodv: com.meshlink.app.domain.routing.AodvEngine? = null
    val connectNotice = MutableStateFlow("")
    val connectionProblem = MutableStateFlow(false)
    val bluetoothStatus = MutableStateFlow("Checking")
    val knownNodes = MutableStateFlow<List<Node>>(emptyList())
    val routes = MutableStateFlow<List<com.meshlink.app.domain.routing.AodvRoute>>(emptyList())
    val deliveryStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    // Transports
    val bleTransport: AndroidBleGattTransport by lazy {
        AndroidBleGattTransport(context, identity)
    }


    private var activeTransport: MeshTransport = bleTransport

    // =========================================================================
    // STATE FLOWS
    // =========================================================================
    private val _currentNode = MutableStateFlow(
        Node(
            nodeId = stableId,
            about = preferences.getString("about", "").orEmpty(),
            nodeName = preferences.getString("name", null) ?: "Mesh-${stableId.take(6)}",
            status = NodeStatus.DISCONNECTED
        )
    )
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<Node>>(emptyList())
    val discoveredNodes: StateFlow<List<Node>> = _discoveredNodes.asStateFlow()

    private val _connectedNodes = MutableStateFlow<List<Node>>(emptyList())
    val connectedNodes: StateFlow<List<Node>> = _connectedNodes.asStateFlow()

    private val _messages = MutableStateFlow<List<MessagePacket>>(emptyList())
    val messages: StateFlow<List<MessagePacket>> = _messages.asStateFlow()

    private val _networkLogs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val networkLogs: StateFlow<List<NetworkLogEntry>> = _networkLogs.asStateFlow()

    private val _isNetworkingActive = MutableStateFlow(false)
    val isNetworkingActive: StateFlow<Boolean> = _isNetworkingActive.asStateFlow()

    // Default transport is BLE GATT for Review III
    private val _transportMode = MutableStateFlow(TransportMode.BLE_GATT)
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    val demoTopology = MutableStateFlow(com.meshlink.app.domain.network.DemoTopology())

    @Synchronized fun configureDemo(topology: com.meshlink.app.domain.network.DemoTopology) {
        val wasActive = _isNetworkingActive.value
        if (wasActive) stopNetworking()
        bleTransport.configureDemo(topology)
        demoTopology.value = topology
        log("DEMO", if (topology.enabled) "Controlled BLE Demo Mode: allowed neighbors ${topology.permittedIds.joinToString()}" else "Normal BLE Mode: restrictions disabled")
        if (wasActive) startNetworking(_transportMode.value)
    }

    private fun storageFailure() { log("STORAGE", "Local message history could not be read or saved", LogLevel.ERROR) }
    val unread = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    val loadingHistory = MutableStateFlow(true)
    fun markRead(peer: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        runCatching {
            journal.markRead(currentNode.value.nodeId, peer, ids)
            unread.update { previous -> previous.filterNot { it.first == peer && it.second in ids }.toSet() }
        }.onFailure { storageFailure() }
    }
    private val historyLock = Any()
    private val deletedOutgoingIds = mutableSetOf<String>()
    private var historyGeneration = 0L

    fun deleteConversation(peer: String): Boolean = deleteHistory { packet ->
        packet.group == null && ((packet.sourceId == stableId && packet.destinationId == peer) ||
            (packet.sourceId == peer && packet.destinationId == stableId))
    }

    fun deleteGroup(id: String): Boolean {
        if (groups.value.none { it.id == id }) return false
        // Keep only unsent departure notices as protocol outbox records; never discard exit counters.
        val deleted = deleteHistory { packet -> packet.group?.chat?.id == id &&
            !(packet.sourceId == stableId && packet.group.kind == "exit" && deliveryStatuses.value[packet.messageId]?.startsWith("Delivered") != true) }
        if (!deleted) return false
        return runCatching { groupStore.hide(id); refreshGroups(); true }.getOrElse { storageFailure(); false }
    }

    private fun deleteHistory(matches: (MessagePacket) -> Boolean): Boolean {
        val owner = currentNode.value.nodeId
        return try {
            val removed = synchronized(historyLock) {
                val inboxMessages = receivedInbox.messages(owner).filter(matches)
                // Remove the fallback inbox first, so startup migration cannot restore deleted history.
                receivedInbox.removeMessages(owner, matches)
                journal.deleteMatching(owner, matches) + inboxMessages + messages.value.filter(matches)
            }.distinctBy { it.sourceId to it.messageId }
            val outgoing = removed.filter { it.sourceId == owner }.map { it.messageId }.toSet()
            outgoing.forEach { aodv?.cancelMessage(it, "Failed: conversation deleted") }
            deletedOutgoingIds.addAll(outgoing)
            historyGeneration++
            _messages.update { it.filterNot(matches) }
            deliveryStatuses.update { it - outgoing }
            unread.value = journal.unread(owner)
            messageNotifier.cancelMessages(removed.map { com.meshlink.app.domain.notifications.NotificationTarget(it.messageId, it.sourceId) }.toSet())
            if (loadingHistory.value) restoreHistory(owner)
            true
        } catch (_: Exception) { storageFailure(); false }
    }
    private fun restoreHistory(owner: String) {
        loadingHistory.value = true
        unread.value = emptySet()
        val generation = ++historyGeneration
        _messages.value = emptyList()
        deliveryStatuses.value = emptyMap()
        repositoryScope.launch {
            runCatching {
                val saved = kotlinx.coroutines.withContext(Dispatchers.IO) { synchronized(historyLock) {
                    val records = journal.load(owner)
                    val ids = records.map { it.first.sourceId to it.first.messageId }.toSet()
                    val legacy = receivedInbox.messages(owner).filter { (it.sourceId to it.messageId) !in ids }
                    legacy.forEach { journal.save(owner,it,"Received") }
                    records + legacy.map { it to "Received" }
                } }
                if (generation != historyGeneration || currentNode.value.nodeId != owner) return@launch
                _messages.update { live -> (live + saved.map { it.first }).distinctBy { it.sourceId to it.messageId }
                    .sortedByDescending { it.timestamp }.take(500) }
                unread.value = journal.unread(owner)
                val restored = saved.mapNotNull { (packet, status) ->
                    if (status == null || packet.sourceId != owner) null else packet.messageId to
                        if (status.startsWith("Delivered") || status.startsWith("Failed")) status else "Failed: session ended before confirmation"
                }.toMap()
                deliveryStatuses.update { restored + it }
            }.onFailure { storageFailure() }
            if (generation == historyGeneration) loadingHistory.value = false
        }
    }

    private fun deliveryStatus(id: String, status: String) {
        if (id in deletedOutgoingIds) return
        deliveryStatuses.update { (it + (id to status)).entries.toList().takeLast(500).associate { entry -> entry.toPair() } }
        runCatching { journal.status(currentNode.value.nodeId,id,status) }.onFailure { storageFailure() }
    }

    init {
        restoreHistory(stableId)
        repositoryScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                bluetoothStatus.value = try {
                    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                    if (adapter == null) "Unavailable" else if (adapter.isEnabled) "On" else "Off"
                } catch (_: SecurityException) { "Permission required" }
                aodv?.tick()
                routes.value = aodv?.snapshot() ?: emptyList()
                knownNodes.update { known -> known.filter { node -> _connectedNodes.value.any { it.nodeId == node.nodeId } || System.currentTimeMillis() - node.lastSeenTimestamp < 300_000 } }
                if (_transportMode.value == TransportMode.BLE_GATT) {
                    _isNetworkingActive.value = bleTransport.isRunning.value
                    if (!bleTransport.isRunning.value) { _connectedNodes.value = emptyList(); aodv = null; routes.value = emptyList() }
                }
            }
        }
        // Collect transport events from all transports in background coroutines
        repositoryScope.launch {
            bleTransport.events.collect { if (_transportMode.value == TransportMode.BLE_GATT) handleTransportEvent(it) }
        }


        log(
            tag = "INIT",
            message = "MeshLink 1.0 initialized with ID '${_currentNode.value.nodeId}' (${_currentNode.value.nodeName}) | Primary Transport: BLE GATT with CRC-16-CCITT Integrity",
            level = LogLevel.INFO
        )
    }

    // =========================================================================
    // NODE IDENTITY CONFIGURATION
    // =========================================================================
    fun updateNodeIdentity(nodeId: String, nodeName: String, about: String) {
        val updated = _currentNode.value.copy(
            about = about.trim().take(160),
            nodeId = nodeId.trim().take(80).ifEmpty { IdGenerator.generateNodeId() },
            nodeName = nodeName.trim().take(64).ifEmpty { "Node" }
        )
        val changedId = updated.nodeId != _currentNode.value.nodeId
        val wasActive = _isNetworkingActive.value
        if (changedId && wasActive) stopNetworking()
        _currentNode.value = updated
        if (changedId) restoreHistory(updated.nodeId)
        preferences.edit().putString("id", updated.nodeId).putString("name", updated.nodeName).putString("about", updated.about).apply()

        log(
            tag = "CONFIG",
            message = "Node identity updated to '${updated.nodeName}' [${updated.nodeId}]",
            level = LogLevel.INFO,
            nodeId = updated.nodeId
        )

        if (wasActive && changedId) startNetworking()
        else if (wasActive) {
            bleTransport.updateProfile(updated)
            aodv?.rename(updated.nodeName, identity.profile(updated).export())
        }
    }

    // =========================================================================
    // TRANSPORT SELECTION & NETWORKING LIFECYCLE
    // =========================================================================
    fun setTransportMode(mode: TransportMode) {
        val wasActive = _isNetworkingActive.value
        if (wasActive) {
            stopNetworking()
        }
        _transportMode.value = mode
        log(
            tag = "CONFIG",
            message = "Transport mode selected: ${getTransportModeDescription(mode)}",
            level = LogLevel.INFO
        )
        if (wasActive) {
            startNetworking(mode)
        }
    }

    fun requestNetworkingStart() {
        androidx.core.content.ContextCompat.startForegroundService(context,
            android.content.Intent(context, com.meshlink.app.service.MeshNetworkingService::class.java))
    }
    fun requestNetworkingStop() {
        connectionProblem.value = false
        context.stopService(android.content.Intent(context, com.meshlink.app.service.MeshNetworkingService::class.java))
    }

    @Synchronized fun startNetworking(mode: TransportMode) {
        if (_isNetworkingActive.value || activeTransport.isRunning.value) return
        connectionProblem.value = false
        _transportMode.value = mode
        activeTransport = when (mode) {
            TransportMode.BLE_GATT -> bleTransport
        }

        _isNetworkingActive.value = true
        _currentNode.update { it.copy(status = NodeStatus.CONNECTED) }

        log(
            tag = "ENGINE",
            message = "Starting networking in ${getTransportModeDescription(mode)} mode...",
            level = LogLevel.INFO
        )

        if (mode == TransportMode.BLE_GATT) {
            val current = _currentNode.value
            aodv = com.meshlink.app.domain.routing.AodvEngine(current.nodeId, current.nodeName,
                profile = identity.profile(current).export(),
                transmit = transmit@{ nextHop, packet ->
                    if (packet.control == null && packet.sourceId == current.nodeId && outgoingPeer(packet) == null) return@transmit false
                    val endpoint = _connectedNodes.value.find { it.nodeId == nextHop && demoTopology.value.permits(nextHop) }?.endpointId
                    if (endpoint == null) false else runCatching {
                        val outgoing = if (packet.sourceId == current.nodeId && packet.ackFor != null) {
                            EndToEnd.seal(packet,identity.signing(),requireNotNull(outgoingPeer(packet)).wrappingKey())
                        } else packet
                        bleTransport.sendPacket(if (outgoing.control == null && outgoing.sourceId == current.nodeId) outgoing.copy(senderProfile = identity.profile(currentNode.value).export()) else outgoing,endpoint)
                    }.getOrDefault(false)
                },
                delivered = { packet -> deliverIncoming(packet) },
                status = ::deliveryStatus,
                learned = { id, name -> knownNodes.update { (it.filter { n -> n.nodeId != id } + Node(id, name, status = NodeStatus.DISCONNECTED)).takeLast(512) } },
                log = { log("AODV", it) },
                acknowledged = { messageId, path ->
                    _messages.update { messages -> messages.map { packet ->
                        if (packet.messageId == messageId && packet.sourceId == current.nodeId) packet.copy(routeHistory = path, hopCount = path.size - 1, ttl = packet.ttl - (path.size - 1)).also { updated -> runCatching { journal.save(current.nodeId,updated,deliveryStatuses.value[messageId]) }.onFailure { storageFailure() } } else packet
                    } }
                })
        }
        activeTransport.start(_currentNode.value)
        _isNetworkingActive.value = activeTransport.isRunning.value
        if (_isNetworkingActive.value) leftGroups.value.forEach { id ->
            val notices = messages.value.filter { it.sourceId == stableId && it.group?.chat?.id == id && it.group.kind == "exit" }
            if (notices.isEmpty()) {
                val exit = groupStore.exit(id, stableId)
                sendGroupEvent(id, "Left the group (${exit.count}/3 exits)", "exit", exit = exit)
            } else notices.filter { deliveryStatuses.value[it.messageId]?.startsWith("Failed") == true }.forEach { retryMessage(it.messageId) }
        }
    }

    fun startNetworking() = startNetworking(_transportMode.value)

    @Synchronized fun stopNetworking() {
        aodv = null
        routes.value = emptyList()
        activeTransport.stop()
        _isNetworkingActive.value = false
        _currentNode.update { it.copy(status = NodeStatus.DISCONNECTED) }
        deliveryStatuses.value.filterValues { !it.startsWith("Delivered") && !it.startsWith("Failed") }.keys.forEach { deliveryStatus(it,"Failed: networking stopped") }
        _discoveredNodes.value = emptyList()
        _connectedNodes.value = emptyList()

        log(
            tag = "ENGINE",
            message = "Mesh networking stopped. Disconnected from all peers.",
            level = LogLevel.INFO
        )
    }

    fun setBlocked(id: String, block: Boolean) {
        identity.setBlocked(id, block)
        if (block) {
            messages.value.filter { it.sourceId == stableId && it.destinationId == id &&
                (it.group == null || it.group.kind in setOf("created", "invite")) }.forEach {
                aodv?.cancelMessage(it.messageId, "Failed: participant blocked")
            }
            messageNotifier.cancelFrom(id)
        } else {
            // Restore routing membership only for a still-authenticated direct link.
            val neighbor = connectedNodes.value.find { it.nodeId == id }
            if (neighbor != null && bleTransport.isReadyPeer(id, neighbor.endpointId)) aodv?.neighborUp(id)
        }
    }

    fun connectToNode(targetNode: Node) {
        log(
            tag = "CONNECT",
            message = "Connecting to neighbor '${targetNode.nodeName}' [${targetNode.nodeId}]...",
            level = LogLevel.INFO,
            nodeId = targetNode.nodeId
        )
        connectNotice.value = bleTransport.connectManually(targetNode)
    }

    fun disconnectNode(nodeId: String) {
        connectNotice.value = ""
        bleTransport.disconnectManually(nodeId)
    }

    fun startMesh() = requestNetworkingStart()
    fun stopMesh() = requestNetworkingStop()
    fun connectToNeighbor(targetNode: Node) = connectToNode(targetNode)
    fun disconnectNeighbor(nodeId: String) = disconnectNode(nodeId)

    private fun getTransportModeDescription(mode: TransportMode): String {
        return when (mode) {
            TransportMode.BLE_GATT -> "BLE GATT (Dual-Role Android Mesh + CRC-16 Integrity)"
        }
    }

    // =========================================================================
    // MESSAGING & ROUTING ENGINE DISPATCH
    // =========================================================================
    fun sendMessage(
        destinationId: String,
        destinationName: String = "",
        payload: String,
        ttl: Int = 5
    ): MessagePacket? {
        val current = _currentNode.value
        val messageId = IdGenerator.generateMessageId()

        val packet = routingEngine.createLocalPacket(
            messageId = messageId,
            sourceId = current.nodeId,
            sourceName = current.nodeName,
            destinationId = destinationId,
            destinationName = destinationName.ifEmpty { destinationId },
            payload = payload,
            initialTtl = ttl
        )

        log(
            tag = "SEND [LOCAL]",
            message = "Created message '$messageId' for destination '$destinationId' with initial TTL $ttl (Hops: 0, Route: [${current.nodeId}])",
            level = LogLevel.INFO,
            packetSummary = packet.getSummary(),
            nodeId = destinationId
        )

        try {
            journal.save(current.nodeId,packet,"Queued")
            _messages.update { (listOf(packet) + it).take(500) }
            dispatch(packet)
        } catch (e: Exception) { storageFailure(); return null }
        return packet
    }

    fun retryMessage(messageId: String) {
        val owner = currentNode.value.nodeId
        val packet = messages.value.find { it.sourceId == owner && it.messageId == messageId } ?: return
        if (!isNetworkingActive.value || deliveryStatuses.value[messageId]?.startsWith("Failed") != true) return
        // Keep the original journal entry and ID. A new signed attempt needs a fresh validity window.
        dispatch(packet.copy(timestamp = System.currentTimeMillis()))
    }

    private fun dispatch(packet: MessagePacket) {
        val messageId = packet.messageId
        try {
            deliveryStatus(messageId,"Queued")
            require(_isNetworkingActive.value && packet.payload.toByteArray().size in 1..2000) { "Networking stopped or message exceeds 2000 bytes" }
            val peer = requireNotNull(outgoingPeer(packet)) { "Recipient unavailable or blocked" }
            val engine = requireNotNull(aodv)
            // Sending is an explicit request to communicate, including after a manual disconnect.
            if (connectedNodes.value.none { it.nodeId == peer.id }) {
                val token = com.meshlink.app.domain.network.DemoTopology.identityToken(peer.id)
                    .joinToString("") { "%02x".format(it) }
                val nearby = discoveredNodes.value.lastOrNull { it.nodeId == peer.id || it.nodeId == "discovered-$token" }
                bleTransport.connectForMessage(peer.id, peer.name, nearby?.endpointId, packet.group != null)
            }
            deliveryStatus(messageId,"Encrypting")
            repositoryScope.launch {
                try {
                    val sealed = kotlinx.coroutines.withContext(Dispatchers.IO) { EndToEnd.seal(packet, identity.signing(), peer.wrappingKey()) }
                    require(messageId !in deletedOutgoingIds && engine === aodv && outgoingPeer(packet)?.sameKeys(peer) == true) { "Networking or recipient trust changed" }
                    while (!engine.hasSendCapacity()) {
                        kotlinx.coroutines.delay(250)
                        require(messageId !in deletedOutgoingIds && engine === aodv && outgoingPeer(packet)?.sameKeys(peer) == true)
                    }
                    engine.send(sealed)
                } catch (e: Exception) {
                    if (currentNode.value.nodeId == packet.sourceId) deliveryStatus(messageId,"Failed: secure transmission could not start")
                }
            }
        } catch (e: Exception) { deliveryStatus(messageId,"Failed: " + (e.message ?: "secure storage unavailable")) }
    }

    private suspend fun handleIncomingPacket(packet: MessagePacket, fromEndpointId: String) {
        val neighbor = _connectedNodes.value.find { it.endpointId == fromEndpointId } ?: return
        if (!bleTransport.isReadyPeer(neighbor.nodeId, fromEndpointId) || identity.linkCard(neighbor.nodeId) == null) return
        val engine = aodv ?: return
        try {
            val local = currentNode.value.nodeId
            if (packet.control == null) {
                require(packet.routeHistory.firstOrNull() == packet.sourceId && packet.routeHistory.lastOrNull() == neighbor.nodeId)
                require(packet.routeHistory.size == packet.hopCount + 1 && packet.routeHistory.distinct().size == packet.routeHistory.size)
            }
            val announcement = packet.control
            if (announcement?.kind == "ANNOUNCE" && announcement.origin != local) {
                if (announcement.profile != null) {
                    val signed = SignedProfile.parse(announcement.profile)
                    require(signed.profile.card.id == announcement.origin)
                    identity.learn(signed, identity.profile(currentNode.value).profile.card)
                } else requireNotNull(identity.linkCard(announcement.origin))
            }
            val incoming = if (packet.control == null && packet.destinationId == local) {
                packet.senderProfile?.let {
                    val signed = SignedProfile.parse(it)
                    require(signed.profile.card.id == packet.sourceId)
                    identity.learn(signed, identity.profile(currentNode.value).profile.card)
                }
                val pin = requireNotNull(identity.linkCard(packet.sourceId))
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val opened = EndToEnd.open(packet,identity.decrypting(),pin.cert().publicKey,System.currentTimeMillis())
                    if (packet.ackFor != null) check(journal.consume(packet.sourceId, packet.messageId, packet.timestamp)) { "Replayed ACK" }
                    opened.copy(senderProfile = null)
                }
            } else packet
            if (packet.control == null && packet.destinationId == local && !incomingAllowed(incoming)) return
            if (engine === aodv && bleTransport.isReadyPeer(neighbor.nodeId,fromEndpointId)) engine.receive(incoming,neighbor.nodeId)
        } catch (e: Exception) { log("SECURITY","Rejected inbound packet: " + e.javaClass.simpleName,LogLevel.WARNING) }
    }

    // =========================================================================
    // TRANSPORT EVENT HANDLING
    // =========================================================================
    private suspend fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.NodeDiscovered -> {
                val node = event.node
                _discoveredNodes.update { list ->
                    val filtered = list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                    filtered + node
                }
            }
            is TransportEvent.NodeLost -> {
                _discoveredNodes.update { list ->
                    list.filter { it.endpointId != event.endpointId }
                }
            }
            is TransportEvent.NodeConnected -> {
                val node = event.node
                if (_transportMode.value == TransportMode.BLE_GATT && (!demoTopology.value.permits(node.nodeId) || !bleTransport.isReadyPeer(node.nodeId, node.endpointId))) return
                _discoveredNodes.update { list ->
                    list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                }
                _connectedNodes.update { list ->
                    val filtered = list.filter { it.nodeId != node.nodeId && it.endpointId != node.endpointId }
                    filtered + node
                }
                connectNotice.value = ""
                connectionProblem.value = false
                aodv?.neighborUp(node.nodeId)
                knownNodes.update { (it.filter { n -> n.nodeId != node.nodeId } + node).takeLast(512) }
            }
            is TransportEvent.NodeDisconnected -> {
                // Keep a recently authenticated neighbor selectable during the gap before its next advertisement.
                val previous = connectedNodes.value.find { it.nodeId == event.nodeId && it.endpointId == event.endpointId }
                if (previous != null && bleTransport.isRunning.value) {
                    val nearby = previous.copy(status = NodeStatus.DISCONNECTED, lastSeenTimestamp = System.currentTimeMillis())
                    _discoveredNodes.update { nodes -> nodes.filterNot { it.nodeId == nearby.nodeId || it.endpointId == nearby.endpointId } + nearby }
                }
                if (previous != null && !bleTransport.isReadyPeer(previous.nodeId, event.endpointId)) {
                    aodv?.neighborDown(previous.nodeId)
                    _connectedNodes.update { nodes -> nodes.filterNot { it.nodeId == previous.nodeId && it.endpointId == previous.endpointId } }
                }
                _discoveredNodes.update { nodes -> nodes.map {
                    if (it.endpointId == event.endpointId && it.status == NodeStatus.CONNECTING) it.copy(status = NodeStatus.DISCONNECTED) else it
                } }
                if (previous == null && connectNotice.value.isNotEmpty()) connectNotice.value = "Connection ended. You can try Connect again."
            }
            is TransportEvent.PacketReceived -> {
                handleIncomingPacket(event.packet, event.fromEndpointId)
            }
            is TransportEvent.PacketSent -> {
                if (_transportMode.value == TransportMode.BLE_GATT && event.packet.sourceId == _currentNode.value.nodeId && event.packet.control == null && event.packet.ackFor == null) {
                    aodv?.transmitted(event.packet.messageId)
                    deliveryStatuses.update { statuses ->
                        if (statuses[event.packet.messageId]?.startsWith("Delivered") == true || statuses[event.packet.messageId]?.startsWith("Failed") == true) statuses
                        else statuses + (event.packet.messageId to "Next-hop ATT receipt; awaiting destination ACK")
                    }
                }
                deliveryStatuses.value[event.packet.messageId]?.let { state -> runCatching { journal.status(currentNode.value.nodeId,event.packet.messageId,state) }.onFailure { storageFailure() } }
                log(
                    tag = "TX-CONFIRM",
                    message = "Frame containing '${event.packet.messageId}' successfully transmitted to endpoint '${event.toEndpointId}'",
                    level = LogLevel.INFO
                )
            }
            is TransportEvent.LogEmitted -> {
                appendLog(event.entry)
            }
            is TransportEvent.ErrorOccurred -> {
                connectionProblem.value = true
                log(
                    tag = "ERROR",
                    message = event.message,
                    level = LogLevel.ERROR
                )
            }
            is TransportEvent.ConnectionInitiated -> {
                _discoveredNodes.update { nodes -> nodes.map { if (it.endpointId == event.endpointId) it.copy(status = NodeStatus.CONNECTING) else it } }
            }
        }
    }

    // =========================================================================
    // LOGGING HELPERS
    // =========================================================================
    private fun log(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        packetSummary: String? = null,
        nodeId: String? = null
    ) {
        val entry = NetworkLogEntry(
            tag = tag,
            message = message,
            level = level,
            packetSummary = packetSummary,
            relatedNodeId = nodeId
        )
        appendLog(entry)
    }

    private fun appendLog(entry: NetworkLogEntry) {
        _networkLogs.update { (listOf(entry) + it).take(200) }
    }

    fun clearLogs() {
        _networkLogs.value = emptyList()
        log("LOG", "Network event log cleared", LogLevel.INFO)
    }

    fun clearMessages() {
        historyGeneration++
        runCatching { journal.clear(currentNode.value.nodeId); receivedInbox.clearMessages(currentNode.value.nodeId) }.onFailure { storageFailure(); return }
        _messages.value = emptyList()
    }

    fun resetRoutingState() {
        routingEngine.resetSeenCache()
        log("ROUTING", "Routing engine duplicate cache reset", LogLevel.INFO)
    }
}
