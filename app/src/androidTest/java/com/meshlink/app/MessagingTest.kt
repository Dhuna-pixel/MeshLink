package com.meshlink.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.domain.network.DemoTopology
import androidx.compose.ui.test.junit4.createComposeRule
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.network.DestinationOption
import com.meshlink.app.presentation.viewmodels.*
import com.meshlink.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessagingTest {
    @get:Rule val compose = createComposeRule()
    private val outgoing = MessagePacket("out", "self", destinationId = "peer", payload = "First attempt")
    private val incoming = MessagePacket("in", "peer", "Taylor", "self", payload = "Hello")

    @Test fun disconnectOffersConnectOnTheSamePeerBeforeAndAfterRediscovery() {
        val peer = Node("peer", "Taylor", endpointId = "AA:BB:CC:DD:EE:FF")
        val disconnected = peer.copy(status = NodeStatus.DISCONNECTED)
        val state = mutableStateOf(MeshUiState(isNetworkingActive = true, connectedNodes = listOf(peer),
            destinations = listOf(DestinationOption(peer.nodeId, peer.nodeName, "Directly connected · Trusted"))))
        var target: Node? = null
        compose.setContent {
            NeighborsScreen(state.value, {}, { target = it }, {
                assertEquals(peer.nodeId, it)
                state.value = state.value.copy(connectedNodes = emptyList(), discoveredNodes = listOf(disconnected))
            }, {}, {}, {}, { _, _ -> }, { null })
        }
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Connect").performClick()
        assertEquals(disconnected, target)
        val token = DemoTopology.identityToken(peer.nodeId).joinToString("") { "%02x".format(it) }
        val advertisement = peer.copy(nodeId = "discovered-$token", nodeName = "MeshLink peer",
            status = NodeStatus.DISCOVERED, endpointId = "11:22:33:44:55:66", lastSeenTimestamp = peer.lastSeenTimestamp + 1)
        compose.runOnIdle { state.value = state.value.copy(discoveredNodes = listOf(disconnected, advertisement)) }
        compose.onNodeWithText("Connect").performClick()
        assertEquals(advertisement.endpointId, target?.endpointId)
        assertEquals(peer.nodeId, target?.nodeId)
        compose.onNodeWithText("MeshLink peer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(discoveredNodes = emptyList()) }
        compose.onNodeWithText("Connect").assertDoesNotExist()
    }

    @Test fun firstContactCanChatWithoutBadgeAndVerificationAddsBadge() {
        var opened = ""
        val contact = DestinationOption("peer", "Taylor", "Known remote")
        val state = mutableStateOf(MeshUiState(destinations = listOf(contact)))
        compose.setContent { NeighborsScreen(state.value, { opened = it }, {}, {}, {}, {}, {}, { _, _ -> }, { null }) }
        compose.onNodeWithContentDescription("Verified contact").assertDoesNotExist()
        compose.onNodeWithText("Chat").performClick()
        assertEquals("peer",opened)
        compose.runOnIdle { state.value = state.value.copy(destinations = listOf(contact.copy(verified = true))) }
        compose.onNodeWithContentDescription("Verified contact").assertIsDisplayed()
    }

    @Test fun emptyChatsOffersDiscovery() {
        var discover = false
        compose.setContent { ChatsScreen(MeshUiState(loadingHistory = false), emptyMap(), {}, { discover = true }) }
        compose.onNodeWithText("No conversations yet").assertIsDisplayed()
        compose.onNodeWithText("New conversation").performClick()
        assertTrue(discover)
    }

    @Test fun groupedChatOpensPeerAndShowsUnreadPreview() {
        var opened = ""
        val state = MeshUiState(currentNode = com.meshlink.app.domain.models.Node("self"),
            unread = setOf("peer" to "in"))
        compose.setContent { ChatsScreen(state, mapOf("peer" to listOf(outgoing,incoming)), { opened = it }, {}) }
        compose.onNodeWithText("1 unread").assertIsDisplayed()
        compose.onNodeWithText("Hello").performClick()
        assertEquals("peer", opened)
    }

    @Test fun conversationActionsUseSelectedMessageAndRecipient() {
        val state = MeshUiState(currentNode = com.meshlink.app.domain.models.Node("self"),
            peerId = "peer", selectedTab = MeshScreenTab.CONVERSATION, isNetworkingActive = true,
            destinations = listOf(DestinationOption("peer","Taylor","Directly connected",true)),
            deliveryStatuses = mapOf("out" to "Failed"), messageInputText = "Reply")
        var sent = false
        var retried: MessagePacket? = null
        compose.setContent { ConversationScreen(state, listOf(outgoing,incoming), {}, { sent = true }, { retried = it }, { _,_ -> }, {}) }
        compose.onNodeWithText("Taylor").assertIsDisplayed()
        compose.onNodeWithText("First attempt").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Send").performClick()
        assertEquals(outgoing, retried)
        assertTrue(sent)
    }
}
