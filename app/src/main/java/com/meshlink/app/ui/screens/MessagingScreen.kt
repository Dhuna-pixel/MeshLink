package com.meshlink.app.ui.screens

import com.meshlink.app.ui.components.ParticipantName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.presentation.viewmodels.*
import com.meshlink.app.ui.components.DestinationPicker
import java.text.DateFormat
import androidx.lifecycle.repeatOnLifecycle
import java.util.Date

internal fun conversations(owner: String, packets: List<MessagePacket>) = packets.asSequence()
    .filter { it.group == null && (it.sourceId == owner || it.destinationId == owner) }
    .sortedWith(compareBy<MessagePacket> { it.timestamp }.thenBy { it.messageId })
    .groupBy { if (it.sourceId == owner) it.destinationId else it.sourceId }

internal fun MessagePacket.peerName(owner: String) =
    if (owner == sourceId) destinationName.ifBlank { destinationId } else sourceName.ifBlank { sourceId }

internal fun deliveryLabel(status: String?) = when {
    status == null -> "Status unavailable"
    status.startsWith("Delivered") -> "Delivered"
    status.startsWith("Failed") -> "Failed"
    status.startsWith("Next-hop") -> "Sent to next hop"
    status == "Discovering route" -> status
    status == "Queued" -> status
    else -> "Sending"
}

internal fun stamp(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

@Composable
fun ChatsScreen(state: MeshUiState, history: Map<String,List<MessagePacket>>, open: (String) -> Unit, newChat: () -> Unit, newGroup: () -> Unit = {}, openGroup: (String) -> Unit = {}) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(newChat) { Text("New conversation") }
            OutlinedButton(newGroup) { Text("New group") }
        } }
        items(state.groups.filterNot { it.id in state.hiddenGroups }.sortedByDescending { group -> state.messages.firstOrNull { it.group?.chat?.id == group.id }?.timestamp ?: 0 }, key = { "group-" + it.id }) { group ->
            val messages = state.messages.filter { it.group?.chat?.id == group.id }
            val latest = messages.maxByOrNull { it.timestamp }
            val unread = messages.count { it.sourceId to it.messageId in state.unread }
            ListItem(modifier = Modifier.clickable { openGroup(group.id) },
                headlineContent = { Text(group.name) },
                overlineContent = { Text(if (group.id in state.leftGroups) "Group · You left" else "Group · ${group.members.size} members") },
                supportingContent = { Text(latest?.payload ?: "No messages yet", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (unread > 0) Text("$unread unread") })
            HorizontalDivider()
        }
        if (state.notificationTapMessage.isNotEmpty()) item { Text(state.notificationTapMessage) }
        if (history.isEmpty() && state.groups.none { it.id !in state.hiddenGroups }) item {
            if (state.loadingHistory) LinearProgressIndicator() else Column(Modifier.padding(vertical = 32.dp)) {
                Text("No conversations yet", style = MaterialTheme.typography.headlineSmall)
                Text("Discover nearby devices to start chatting.")
            }
        }
        items(history.entries.sortedByDescending { it.value.last().timestamp }, key = { it.key }) { (peer, messages) ->
            val latest = messages.last()
            val outgoing = latest.sourceId == state.currentNode.nodeId
            val unread = messages.count { it.sourceId to it.messageId in state.unread }
            ListItem(modifier = Modifier.clickable { open(peer) },
                headlineContent = { val contact = state.destinations.find { it.id == peer }
                    ParticipantName(contact?.name ?: latest.peerName(state.currentNode.nodeId), contact?.verified == true) },
                supportingContent = { Text(latest.payload, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                overlineContent = { Text(stamp(latest.timestamp)) },
                trailingContent = { Column {
                    if (unread > 0) Text("$unread unread")
                    if (outgoing) Text(deliveryLabel(state.deliveryStatuses[latest.messageId]))
                } })
            HorizontalDivider()
        }
    }
}

@Composable
fun ConversationScreen(state: MeshUiState, messages: List<MessagePacket>, edit: (String) -> Unit,
    send: () -> Unit, retry: (MessagePacket) -> Unit, read: (String,Set<String>) -> Unit, select: (String) -> Unit) {
    val composing = state.selectedTab == MeshScreenTab.COMPOSE
    val canSend = if (composing) state.selectedDestinationNodeIds.isNotEmpty() else state.destinations.any { it.id == state.peerId && it.canMessage }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (composing) DestinationPicker(state.selectedDestinationNodeIds, state.destinations, select)
        val list = key(state.peerId) { rememberLazyListState() }
        val displayed = if (composing) emptyList() else messages
        val unreadIds = displayed.filter { it.sourceId == state.peerId && it.sourceId to it.messageId in state.unread }.map { it.messageId }.toSet()
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(state.peerId, unreadIds, lifecycle) {
            if (unreadIds.isNotEmpty()) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                read(state.peerId, unreadIds)
            }
        }
        LaunchedEffect(state.peerId, displayed.lastOrNull()?.messageId) {
            if (displayed.isNotEmpty() && (list.firstVisibleItemIndex < 2 || displayed.last().sourceId == state.currentNode.nodeId)) list.scrollToItem(0)
        }
        if (displayed.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Start the conversation") }
        else LazyColumn(Modifier.weight(1f), state = list, reverseLayout = true, contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(displayed.asReversed(), key = { "${it.sourceId.length}:${it.sourceId}${it.messageId}" }) { packet ->
                val outgoing = packet.sourceId == state.currentNode.nodeId
                val status = deliveryLabel(state.deliveryStatuses[packet.messageId])
                Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(shape = RoundedCornerShape(16.dp), color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.widthIn(max = 300.dp).padding(12.dp)) {
                            val sender = state.destinations.find { it.id == packet.sourceId }
                            ParticipantName(if (outgoing) "You" else sender?.name ?: packet.sourceName.ifBlank { packet.sourceId },
                                !outgoing && sender?.verified == true, MaterialTheme.typography.labelMedium)
                            Text(packet.payload)
                            Text(stamp(packet.timestamp) + if (outgoing) " · $status" else "", style = MaterialTheme.typography.labelSmall)
                            if (outgoing && status == "Failed") {
                                Text(state.deliveryStatuses[packet.messageId]?.removePrefix("Failed: ") ?: "Delivery unconfirmed", style = MaterialTheme.typography.bodySmall)
                                TextButton({ retry(packet) }, enabled = canSend && state.isNetworkingActive) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
        val tooLong = state.messageInputText.trim().toByteArray().size > 2000
        val hint = when {
            state.composeError.isNotEmpty() -> state.composeError
            tooLong -> "Message exceeds 2,000 UTF-8 bytes"
            !state.isNetworkingActive -> "Start mesh networking in Nearby to send."
            !composing && state.destinations.any { it.id == state.peerId && it.blocked } -> "You blocked this participant."
            !canSend && !composing -> "Waiting for this participant’s public profile."
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(state.messageInputText, edit, modifier = Modifier.weight(1f), maxLines = 4,
                label = { Text("Message") }, isError = tooLong)
            Spacer(Modifier.width(8.dp))
            Button(send, enabled = canSend && state.isNetworkingActive && state.messageInputText.isNotBlank() && !tooLong) { Text("Send") }
        }
    }
}
