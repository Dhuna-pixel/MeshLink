package com.meshlink.app.ui.screens

import com.meshlink.app.ui.components.ParticipantName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshlink.app.presentation.viewmodels.*
import com.meshlink.app.presentation.viewmodels.MeshScreenTab.*
import com.meshlink.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MeshViewModel, onToggleNetworking: () -> Unit, onNotificationPermission: () -> Unit, onSystemNotificationSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val tab = state.selectedTab
    var confirmDelete by remember(tab, state.peerId, state.groupId) { mutableStateOf(false) }
    val parent = when (tab) {
        EVENTS -> DIAGNOSTICS
        DIAGNOSTICS, PHYSICAL -> SETTINGS
        PAIRING -> NEARBY
        else -> CHATS
    }
    val back = { viewModel.selectTab(parent) }
    BackHandler(enabled = tab != CHATS, onBack = back)
    val contact = state.destinations.find { it.id == state.peerId }
    val history = remember(state.messages, state.currentNode.nodeId) { conversations(state.currentNode.nodeId, state.messages) }
    val title = when (tab) {
        CHATS -> "Chats"
        NEARBY -> "Nearby"
        SETTINGS -> "Settings"
        CONVERSATION -> contact?.name ?: history[state.peerId]?.lastOrNull()?.peerName(state.currentNode.nodeId) ?: state.peerId
        COMPOSE -> "New message"
        CREATE_GROUP -> "New group"
        GROUP -> state.groups.find { it.id == state.groupId }?.name ?: "Group"
        EVENTS -> "Network events"
        DIAGNOSTICS -> "Diagnostics"
        PHYSICAL -> "Physical BLE Test Configuration"
        PAIRING -> "Pairing and security"
    }
    val tabs = listOf(CHATS, NEARBY, SETTINGS)
    val status = when {
        state.startupBusy -> "Starting"
        state.isNetworkingActive && state.connectedNodes.isNotEmpty() -> "Connected · ${state.connectedNodes.size}"
        state.connectionProblem -> "Connection problem"
        state.isNetworkingActive -> "Discovering devices"
        state.startupMessage.isNotBlank() || state.bluetoothStatus.startsWith("Networking stopped") -> "Connection problem"
        else -> "Mesh off"
    }
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            ParticipantName(title, tab == CONVERSATION && contact?.verified == true)
            Text(status, style = MaterialTheme.typography.labelMedium)
        } }, navigationIcon = {
            if (tab !in tabs) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            if (tab == CONVERSATION) TextButton({ viewModel.setBlocked(state.peerId, contact?.blocked != true) }) {
                Text(if (contact?.blocked == true) "Unblock" else "Block")
            }
            if (tab == CONVERSATION || tab == GROUP) IconButton({ confirmDelete = true }) {
                Icon(Icons.Default.Delete, if (tab == GROUP) "Delete group" else "Delete conversation")
            }
            if (tab == NEARBY || tab == SETTINGS) TextButton(onClick = onToggleNetworking, enabled = !state.startupBusy) {
                Text(if (state.isNetworkingActive) "Stop mesh" else "Start mesh")
            }
        })
    }, bottomBar = {
        if (tab in tabs) NavigationBar {
            tabs.forEach { item ->
                val (label, icon) = when (item) {
                    CHATS -> "Chats" to Icons.AutoMirrored.Filled.Chat
                    NEARBY -> "Nearby" to Icons.Default.Bluetooth
                    else -> "Settings" to Icons.Default.Settings
                }
                NavigationBarItem(tab == item, { viewModel.selectTab(item) }, { Icon(icon, label) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (state.connectionProblem && tab == NEARBY) Text("Check Bluetooth and nearby device permissions, then restart the mesh.", Modifier.padding(12.dp))
            if (state.startupMessage.isNotBlank()) Text(state.startupMessage, Modifier.padding(12.dp))
            when (tab) {
                CHATS -> ChatsScreen(state, history, viewModel::openChat, { viewModel.selectTab(NEARBY) },
                    { viewModel.selectTab(CREATE_GROUP) }, viewModel::openGroup)
                CREATE_GROUP -> CreateGroupScreen(state, viewModel::updateGroupName, viewModel::updateSelectedDestination, viewModel::createGroup)
                GROUP -> GroupConversationScreen(state, viewModel::updateMessageInput, viewModel::sendMessage,
                    viewModel::retry, viewModel::markRead, viewModel::leaveGroup, viewModel::inviteBack)
                CONVERSATION, COMPOSE -> ConversationScreen(state, history[state.peerId].orEmpty(), viewModel::updateMessageInput,
                    viewModel::sendMessage, viewModel::retry, viewModel::markRead, viewModel::updateSelectedDestination)
                NEARBY -> NeighborsScreen(state, viewModel::openChat, viewModel::connectToNode, viewModel::disconnectNode,
                    { viewModel.selectTab(PAIRING) }, { viewModel.selectTab(COMPOSE) }, viewModel::messageEveryone, viewModel::setBlocked) { id ->
                        (viewModel.identity.linkCard(id) ?: viewModel.identity.rejectedCards().find { it.id == id })?.fingerprint()
                    }
                SETTINGS -> SettingsScreen(state, back, viewModel::setTheme, viewModel::setNotifications, viewModel::setPrivatePreview,
                    onNotificationPermission, onSystemNotificationSettings, { viewModel.showIdentityDialog(true) }, viewModel::selectTab)
                PHYSICAL -> LazyColumn { item { DemoTopologyPanel(state.currentNode.nodeId,state.demoTopology,state.demoMessage,state.startupBusy,viewModel::configureDemo) } }
                PAIRING -> LazyColumn { item { PairingPanel(viewModel.identity,state.currentNode.nodeId,state.currentNode.nodeName,state.isNetworkingActive) } }
                EVENTS -> LogScreen(state, viewModel::setLogFilter, viewModel::clearLogs)
                DIAGNOSTICS -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Bluetooth · ${state.bluetoothStatus}\nDirect neighbors · ${state.connectedNodes.size}") }
                    item { TextButton({ viewModel.selectTab(EVENTS) }) { Text("Network events") } }
                    item { Text("Routing table", style = MaterialTheme.typography.titleMedium) }
                    if (state.routes.isEmpty()) item { Text("No routes recorded") }
                    items(state.routes) { Text("${it.destination} via ${it.nextHop} · ${it.hops} hops") }
                    item { Text("Message details", style = MaterialTheme.typography.titleMedium) }
                    items(state.messages) { TextButton({ viewModel.inspectPacket(it) }) { Text(it.messageId) } }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(if (tab == GROUP) "Delete group chat?" else "Delete conversation?") },
        text = { Text(if (tab == GROUP)
            "Remove this group chat and its saved messages from this phone? This does not exit the group or reset your exit count. If you are still a member, new group messages can make it appear again. Exit first to stop receiving messages. Other members keep their messages."
            else "Delete the saved messages in this conversation from this phone? The other person keeps their messages. New messages can start the conversation again.") },
        confirmButton = { TextButton({ confirmDelete = false; viewModel.deleteCurrentChat() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (state.isEditingIdentityDialogShown) NodeIdentityDialog(state.currentNode.nodeId, state.currentNode.nodeName, state.currentNode.about,
        viewModel::updateNodeIdentity) { viewModel.showIdentityDialog(false) }
    state.isInspectingPacket?.let { PacketInspectorDialog(it) { viewModel.inspectPacket(null) } }
}
