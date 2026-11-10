package com.meshlink.app.ui.screens

import com.meshlink.app.ui.components.*
import com.meshlink.app.ui.theme.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.util.Calendar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    status == "Canceled" -> status
    else -> "Sending"
}

internal fun stamp(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatsScreen(state: MeshUiState, history: Map<String,List<MessagePacket>>, open: (String) -> Unit, newChat: () -> Unit, newGroup: () -> Unit = {}, openGroup: (String) -> Unit = {}) {
    LazyColumn(contentPadding = PaddingValues(vertical = MeshSpace.medium)) {
        item { FlowRow(Modifier.padding(horizontal = MeshSpace.medium), horizontalArrangement = Arrangement.spacedBy(MeshSpace.small)) {
            FilledTonalButton(newChat) { Text("New conversation") }
            TextButton(newGroup) { Text("New group") }
        } }
        items(state.groups.filterNot { it.id in state.hiddenGroups }.sortedByDescending { group -> state.messages.firstOrNull { it.group?.chat?.id == group.id }?.timestamp ?: 0 }, key = { "group-" + it.id }) { group ->
            val messages = state.messages.filter { it.group?.chat?.id == group.id }
            val latest = messages.maxByOrNull { it.timestamp }
            ConversationRow(group.name, false, latest?.let { if (it.voice != null) "Voice message" else it.payload } ?: "No messages yet",
                latest?.timestamp, messages.count { it.sourceId to it.messageId in state.unread },
                if (group.id in state.leftGroups) "Group · You left" else "Group · ${group.members.size} members", true) { openGroup(group.id) }
        }
        if (state.notificationTapMessage.isNotEmpty()) item { Text(state.notificationTapMessage, Modifier.padding(MeshSpace.medium)) }
        if (history.isEmpty() && state.groups.none { it.id !in state.hiddenGroups }) item {
            if (state.loadingHistory) LinearProgressIndicator(Modifier.fillMaxWidth().padding(MeshSpace.medium))
            else EmptyConversation("No conversations yet", "Discover nearby devices to start chatting. Your conversations will appear here.")
        }
        items(history.entries.sortedByDescending { it.value.last().timestamp }, key = { it.key }) { (peer, messages) ->
            val latest = messages.last()
            val contact = state.destinations.find { it.id == peer }
            ConversationRow(contact?.name ?: latest.peerName(state.currentNode.nodeId), contact?.verified == true,
                if (latest.voice != null) "Voice message" else latest.payload, latest.timestamp,
                messages.count { it.sourceId to it.messageId in state.unread },
                if (latest.sourceId == state.currentNode.nodeId) deliveryLabel(state.deliveryStatuses[latest.messageId]) else null) { open(peer) }
        }
    }
}

@Composable
private fun ConversationRow(name: String, verified: Boolean, preview: String, timestamp: Long?, unread: Int,
    metadata: String?, group: Boolean = false, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(MeshSpace.medium),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        IdentityAvatar(name, group)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MeshSpace.xs)) {
            ParticipantName(name, verified, MaterialTheme.typography.titleMedium)
            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            metadata?.let { DeliveryStatus(it) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MeshSpace.small)) {
            timestamp?.let { Text((if (dayKey(it) == dayKey(System.currentTimeMillis())) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateInstance(DateFormat.SHORT)).format(Date(it)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (unread > 0) Badge(Modifier.semantics { contentDescription = "$unread unread messages" }) { Text("$unread", modifier = Modifier.padding(horizontal = MeshSpace.xs)) }
        }
    }
    HorizontalDivider(Modifier.padding(start = 76.dp, end = MeshSpace.medium), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private fun dayKey(time: Long): Triple<Int, Int, Int> = Calendar.getInstance().apply { timeInMillis = time }.let {
    Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH))
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
            if (displayed.isNotEmpty() && (list.firstVisibleItemIndex < 2 || displayed.last().sourceId == state.currentNode.nodeId)) list.animateScrollToItem(0)
        }
        if (displayed.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { EmptyConversation("Start the conversation", "Type a message or use offline speech input. Messages travel through the nearby mesh.", Modifier.verticalScroll(rememberScrollState())) }
        else LazyColumn(Modifier.weight(1f), state = list, reverseLayout = true, contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(displayed.asReversed(), key = { _, packet -> "${packet.sourceId.length}:${packet.sourceId}${packet.messageId}" }) { index, packet ->
                val previous = displayed.getOrNull(displayed.lastIndex - index - 1)
                var entered by remember(packet.messageId) { mutableStateOf(index != 0) }
                LaunchedEffect(packet.messageId) { entered = true }
                AnimatedVisibility(visible = entered, enter = fadeIn() + slideInVertically { it / 8 }) {
                Column {
                if (previous == null || dayKey(previous.timestamp) != dayKey(packet.timestamp)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(packet.timestamp)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val outgoing = packet.sourceId == state.currentNode.nodeId
                val status = deliveryLabel(state.deliveryStatuses[packet.messageId])
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(modifier = Modifier.widthIn(max = minOf(maxWidth * 0.88f, MeshSpace.bubbleWidth)).animateContentSize(), shape = messageShape(outgoing), color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val sender = state.destinations.find { it.id == packet.sourceId }
                            ParticipantName(if (outgoing) "You" else sender?.name ?: packet.sourceName.ifBlank { packet.sourceId },
                                !outgoing && sender?.verified == true, MaterialTheme.typography.labelMedium)
                            if (packet.voice != null) Text("Historical voice message · audio retained")
                            else Text(packet.payload)
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(packet.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (outgoing && packet.voice == null) DeliveryStatus(status)
                            if (outgoing && packet.voice == null && status == "Failed") {
                                Text(state.deliveryStatuses[packet.messageId]?.removePrefix("Failed: ") ?: "Delivery unconfirmed", style = MaterialTheme.typography.bodySmall)
                                TextButton({ retry(packet) }, enabled = canSend && (packet.voice != null || state.isNetworkingActive)) { Text("Retry") }
                            }
                        }
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
        key(state.peerId, state.selectedTab) {
        MessageComposer(state.messageInputText, edit, send,
            enabled = canSend && state.isNetworkingActive && state.messageInputText.isNotBlank() && !tooLong,
            error = tooLong)
        }
    }
}
