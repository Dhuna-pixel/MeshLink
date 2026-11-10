package com.meshlink.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.presentation.viewmodels.MeshUiState
import com.meshlink.app.security.GroupChat
import com.meshlink.app.ui.components.MessageComposer
import com.meshlink.app.ui.theme.*
import androidx.compose.animation.animateContentSize
import com.meshlink.app.ui.components.DestinationPicker
import com.meshlink.app.ui.components.ParticipantName

@Composable
fun CreateGroupScreen(state: MeshUiState, name: (String) -> Unit, select: (String) -> Unit, create: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.groupName, name, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Choose up to ${GroupChat.MAX_MEMBERS - 1} other people. You are included automatically.")
        DestinationPicker(state.selectedDestinationNodeIds, state.destinations, select)
        Text("Members receive an invitation through the mesh and can reply to everyone. Each recipient has their own delivery confirmation.")
        if (!state.isNetworkingActive) Text("The group can be saved now. Start the mesh and retry the invitation to reach members.", style = MaterialTheme.typography.bodySmall)
        Text("This group's member list is fixed. Create a new group to use a different member list.", style = MaterialTheme.typography.bodySmall)
        if (state.composeError.isNotBlank()) Text(state.composeError, color = MaterialTheme.colorScheme.error)
        Button(create, enabled = state.groupName.isNotBlank() && state.selectedDestinationNodeIds.size in 1 until GroupChat.MAX_MEMBERS) { Text("Create group") }
    }
}

@Composable
fun GroupConversationScreen(state: MeshUiState, edit: (String) -> Unit, send: () -> Unit,
    retry: (MessagePacket) -> Unit, read: (String, Set<String>) -> Unit, leave: () -> Unit, invite: (String) -> Unit = {}) {
    val group = state.groups.find { it.id == state.groupId } ?: return
    val owner = state.currentNode.nodeId
    val packets = state.messages.filter { it.group?.chat?.id == group.id }
    val messages = packets.groupBy { it.sourceId to it.group!!.messageId }.values.sortedByDescending { it.first().timestamp }
    val left = group.id in state.leftGroups
    val exits = state.groupExits[group.id].orEmpty()
    val ownExits = exits[owner]?.count ?: 0
    val activeRecipients = group.members.count { it.profile.card.id != owner && exits[it.profile.card.id]?.left != true }
    var members by remember(group.id) { mutableStateOf(false) }
    var confirmLeave by remember(group.id) { mutableStateOf(false) }
    val unread = packets.filter { it.sourceId to it.messageId in state.unread }.groupBy { it.sourceId }
        .mapValues { (_, values) -> values.map { it.messageId }.toSet() }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(group.id, unread, lifecycle) {
        if (unread.isNotEmpty()) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            unread.forEach { (sender, ids) -> read(sender, ids) }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(messages.firstOrNull()?.first()?.group?.messageId) { if (messages.isNotEmpty()) list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row {
            TextButton({ members = true }) { Text("Members (${group.members.size})") }
            if (!left) TextButton({ confirmLeave = true }) { Text("Exit group") }
        }
        LazyColumn(Modifier.weight(1f), state = list, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(messages, key = { it.first().sourceId + ":" + it.first().group!!.messageId }) { copies ->
                val packet = copies.first()
                val outgoing = packet.sourceId == owner
                val sender = state.destinations.find { it.id == packet.sourceId }
                var details by remember(packet.group!!.messageId) { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(modifier = Modifier.animateContentSize(), shape = messageShape(outgoing), color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.widthIn(max = MeshSpace.bubbleWidth).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ParticipantName(if (outgoing) "You" else sender?.name ?: packet.sourceName,
                                !outgoing && sender?.verified == true, MaterialTheme.typography.labelMedium)
                            Text(packet.payload)
                            Text(stamp(packet.timestamp), style = MaterialTheme.typography.labelSmall)
                            if (outgoing) {
                                val delivered = copies.count { state.deliveryStatuses[it.messageId]?.startsWith("Delivered") == true }
                                Text("Delivered to $delivered/${copies.size}", style = MaterialTheme.typography.labelSmall)
                                TextButton({ details = !details }) { Text(if (details) "Hide details" else "Delivery details") }
                                if (details) copies.forEach { copy ->
                                    Text("${copy.destinationName}: ${state.deliveryStatuses[copy.messageId] ?: "Status unavailable"}", style = MaterialTheme.typography.bodySmall)
                                    if (state.deliveryStatuses[copy.messageId]?.startsWith("Delivered") == true) {
                                        Text("Path: " + copy.routeHistory.joinToString(" → ") { id ->
                                            if (id == owner) "You" else state.destinations.find { it.id == id }?.name ?: group.member(id)?.name ?: id.take(8)
                                        }, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                val failed = copies.filter { state.deliveryStatuses[it.messageId]?.startsWith("Failed") == true }
                                if (failed.isNotEmpty()) TextButton({ failed.forEach(retry) }, enabled = !left && state.isNetworkingActive) { Text("Retry failed") }
                            }
                        }
                    }
                }
            }
        }
        if (state.composeError.isNotBlank()) Text(state.composeError, color = MaterialTheme.colorScheme.error)
        when {
            left -> Text(if (ownExits >= 3) "You exited 3 times. You cannot be added back to this group." else "You left this group. Exits: $ownExits/3. An unblocked member can add you back.", Modifier.padding(12.dp))
            else -> {
                if (activeRecipients == 0) Text("No other active members. Open Members to add someone back.")
                if (!state.isNetworkingActive) Text("Start mesh networking in Nearby to send.", style = MaterialTheme.typography.bodySmall)
                val tooLong = state.messageInputText.trim().toByteArray().size > 2000
                if (tooLong) Text("Message exceeds 2,000 UTF-8 bytes")
                MessageComposer(state.messageInputText, edit, send,
                    enabled = activeRecipients > 0 && state.isNetworkingActive && state.messageInputText.isNotBlank() && !tooLong,
                    error = tooLong, label = "Message group")
            }
        }
    }
    if (members) AlertDialog(onDismissRequest = { members = false }, title = { Text(group.name) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(group.members) { signed ->
                val member = signed.profile.card
                val contact = state.destinations.find { it.id == member.id }
                Column {
                    ParticipantName(if (member.id == owner) "You" else contact?.name ?: member.name, contact?.verified == true)
                    val exit = exits[member.id]
                    Text(when {
                        exit?.left == true && exit.count >= 3 -> "Exited 3 times · Cannot be added back"
                        exit?.left == true -> "Exited ${exit.count}/3 times"
                        contact?.blocked == true -> "Personal messages blocked · Group messages allowed"
                        member.id == group.creator -> "Creator"
                        else -> "Member"
                    }, style = MaterialTheme.typography.bodySmall)
                    if (member.id != owner && !left && exit?.left == true && exit.count < 3)
                        TextButton({ invite(member.id); members = false }, enabled = contact?.blocked != true) { Text("Add back ${member.name}") }
                }
            }

        }
    }, confirmButton = { TextButton({ members = false }) { Text("Done") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Exit ${group.name}?") },
        text = { Text("This is exit ${ownExits + 1}/3. You will stop receiving group messages and keep your history. " + if (ownExits >= 2) "After this exit, nobody can add you back to this group." else "An unblocked member can add you back. After three exits, re-adding is permanently disabled for this group.") },
        confirmButton = { TextButton({ leave(); confirmLeave = false }) { Text("Exit group") } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Cancel") } })
}
