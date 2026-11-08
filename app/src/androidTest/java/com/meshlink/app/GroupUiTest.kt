package com.meshlink.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshlink.app.domain.models.Node
import com.meshlink.app.security.Identity
import com.meshlink.app.ui.screens.conversations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class GroupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createGroupFanoutDetailsAndLeave() {
        val repo = (compose.activity.application as MeshLinkApp).meshRepository
        val suffix = UUID.randomUUID().toString()
        val name = "Supply team ${suffix.take(6)}"
        val local = repo.identity.profile(repo.currentNode.value).profile.card
        listOf("Group member one", "Group member two").forEach { display ->
            val id = UUID.randomUUID().toString()
            val peer = Identity(compose.activity, "$suffix-$id").profile(Node(id, display))
            repo.identity.learn(peer, local)
        }
        compose.onNodeWithText("New group").performClick()
        compose.onNodeWithText("Group name").performTextInput(name)
        compose.waitUntil(10000) { compose.onAllNodesWithText("Choose recipients").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose recipients").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Group member one").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Group member one").performScrollTo().performClick()
        compose.onNodeWithText("Group member two").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Create group").assertIsEnabled().performClick()
        compose.waitUntil(10000) { repo.groups.value.any { it.name == name } }
        val group = repo.groups.value.single { it.name == name }
        val copies = repo.messages.value.filter { it.group?.chat?.id == group.id }
        assertEquals(2, copies.size)
        assertEquals(1, copies.map { it.group!!.messageId }.distinct().size)
        assertEquals(2, copies.map { it.messageId }.distinct().size)
        assertTrue(conversations(local.id, copies).isEmpty())
        compose.onNodeWithText("Created the group").assertIsDisplayed()
        compose.onNodeWithText("Delivered to 0/2").assertIsDisplayed()
        compose.onNodeWithText("Delivery details").performClick()
        compose.onNodeWithText("Members (3)").performClick()
        compose.onNodeWithText("Group member one").assertIsDisplayed()
        compose.onNodeWithText("Group member two").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Exit group").performClick()
        compose.onAllNodesWithText("Exit group").onLast().performClick()
        compose.onNodeWithText("You left this group.", substring = true).assertIsDisplayed()
        assertTrue(group.id in repo.leftGroups.value)
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onAllNodesWithText("Group · You left").onFirst().assertIsDisplayed()
    }
}
