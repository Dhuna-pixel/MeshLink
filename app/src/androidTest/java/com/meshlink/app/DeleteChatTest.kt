package com.meshlink.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.notifications.NotificationTarget
import com.meshlink.app.domain.notifications.ReceivedMessageStore
import com.meshlink.app.security.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DeleteChatTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun deletingChatsIsLocalSelectiveAndKeepsExitAndReplayProtection() {
        val repo = (compose.activity.application as MeshLinkApp).meshRepository
        compose.waitUntil(10000) { !repo.loadingHistory.value }
        val owner = repo.currentNode.value.nodeId
        val suffix = UUID.randomUUID().toString()
        val identity = Identity(compose.activity, "delete-$suffix")
        val peer = identity.profile(Node(UUID.randomUUID().toString(), "Delete test peer"))
        repo.identity.learn(peer, repo.identity.profile(repo.currentNode.value).profile.card)
        lateinit var group: GroupChat
        val incoming = MessagePacket(UUID.randomUUID().toString(), peer.profile.card.id, "Delete test peer", owner,
            payload = "Personal incoming $suffix", hopCount = 1, routeHistory = listOf(peer.profile.card.id, owner))
        val other = incoming.copy(messageId = UUID.randomUUID().toString(), sourceId = "other-$suffix", sourceName = "Unrelated participant", payload = "Unrelated chat", routeHistory = listOf("other-$suffix", owner))
        compose.runOnUiThread {
            group = repo.createGroup("Delete group ${suffix.take(6)}", setOf(peer.profile.card.id))
            assertTrue(repo.sendGroup(group.id, "Old group message"))
            repo.sendMessage(peer.profile.card.id, "Delete test peer", "Personal outgoing")
            repo.deliverIncoming(incoming)
            repo.deliverIncoming(other)
        }
        compose.onNodeWithText("Delete test peer").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete conversation").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(repo.messages.value.any { it.messageId == incoming.messageId })
        compose.onNodeWithContentDescription("Delete conversation").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { repo.messages.value.none { it.messageId == incoming.messageId } }
        assertTrue(repo.messages.value.any { it.payload == "Old group message" })
        assertTrue(repo.messages.value.any { it.messageId == other.messageId })
        assertFalse(repo.hasReceivedMessage(NotificationTarget(incoming.messageId, incoming.sourceId)))
        assertFalse(ReceivedMessageStore(Vault(compose.activity,"mesh_inbox_v2")).messages(owner).any { it.messageId == incoming.messageId })
        MessageJournal(compose.activity).use { journal ->
            assertFalse(journal.load(owner).any { it.first.messageId == incoming.messageId })
            assertTrue(journal.load(owner).any { it.first.messageId == other.messageId })
        }
        compose.runOnUiThread { repo.deliverIncoming(incoming) }
        assertFalse(repo.messages.value.any { it.messageId == incoming.messageId })
        val fresh = incoming.copy(messageId = UUID.randomUUID().toString(), payload = "New personal message")
        compose.runOnUiThread { repo.deliverIncoming(fresh) }
        compose.onNodeWithText(fresh.payload).assertIsDisplayed()

        compose.onNodeWithText(group.name).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete group").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { group.id in repo.hiddenGroups.value }
        assertFalse(group.id in repo.leftGroups.value)
        assertFalse(repo.messages.value.any { it.payload == "Old group message" })
        compose.onNodeWithText(group.name).assertDoesNotExist()
        assertTrue(GroupStore(Vault(compose.activity, "mesh_groups_v1"), owner).hidden().contains(group.id))
        val groupIncoming = incoming.copy(messageId = UUID.randomUUID().toString(), payload = "Fresh group message",
            group = GroupContext(group, UUID.randomUUID().toString()))
        compose.runOnUiThread { repo.deliverIncoming(groupIncoming) }
        compose.waitUntil(5000) { group.id !in repo.hiddenGroups.value }
        compose.onNodeWithText(group.name).performScrollTo().performClick()
        compose.onNodeWithText("Fresh group message").assertIsDisplayed()
        compose.onNodeWithText("Old group message").assertDoesNotExist()
        compose.onNodeWithText("Exit group").performClick()
        compose.onAllNodesWithText("Exit group").onLast().performClick()
        compose.onNodeWithContentDescription("Delete group").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { group.id in repo.hiddenGroups.value }
        val reopened = GroupStore(Vault(compose.activity,"mesh_groups_v1"), owner)
        assertTrue(group.id in reopened.hidden())
        assertTrue(group.id in reopened.left())
        assertEquals(1, reopened.exit(group.id, owner).count)
        assertTrue(repo.messages.value.any { it.messageId == fresh.messageId })
        assertFalse(repo.hasReceivedMessage(NotificationTarget(groupIncoming.messageId, groupIncoming.sourceId)))
    }
}
