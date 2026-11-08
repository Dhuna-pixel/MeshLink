package com.meshlink.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import com.meshlink.app.data.notifications.AndroidMessageNotifier
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class AppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun appearanceSelectionAndNavigation() {
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        listOf("Light","Dark","System Default").forEach {
            compose.onNodeWithText(it).performClick()
            compose.onNodeWithText(it).assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            listOf("Nearby","Chats").forEach { destination ->
                compose.onNode(hasText(destination) and hasClickAction()).performClick()
                compose.waitForIdle()
            }
            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNodeWithText("Edit my profile").performScrollTo().performClick()
            compose.onNodeWithText("Display name").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Appearance").performScrollTo()
        }
    }
    @Test fun nearbyPairsVerifiedCardAndOpensConversation() {
        val context = compose.activity
        val repo = (context.application as MeshLinkApp).meshRepository
        val peer = com.meshlink.app.security.Identity(context,"pairing-ui-test").own("ui-peer","Nearby test phone")
        repo.identity.forget(peer.id)
        try {
            compose.onNode(hasText("Nearby") and hasClickAction()).performClick()
            compose.onNodeWithText("Pair and verify devices").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Paste peer’s public card").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Paste peer’s public card").performScrollTo().performTextInput(peer.export())
            compose.onNodeWithText("Inspect peer card").performScrollTo().performClick()
            compose.onNodeWithText(peer.fingerprint()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Fingerprints match · trust peer").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chat").performScrollTo().performClick()
            compose.onNodeWithText("Nearby test phone").assertIsDisplayed()
            compose.onNodeWithContentDescription("Verified contact").assertIsDisplayed()
            compose.onNodeWithText("Block").performClick()
            assertNull(repo.identity.participant(peer.id))
            compose.onNodeWithText("Send").assertIsNotEnabled()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Nearby test phone").assertIsDisplayed()
            compose.onNodeWithText("Unblock").performClick()
            assertEquals(peer,repo.identity.participant(peer.id))
        } finally { repo.identity.setBlocked(peer.id,false); repo.identity.forget(peer.id) }
    }
    @Test fun actualNotificationHasPrivacyAndOpensMessages() {
        val context=compose.activity
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        val owner = (context.application as MeshLinkApp).meshRepository.currentNode.value.nodeId
        com.meshlink.app.security.MessageJournal(context).use {
            it.save(owner,com.meshlink.app.domain.models.MessagePacket("test-notification","test-sender",destinationId=owner,payload="Test preview"),"Received")
        }
        val notifier=AndroidMessageNotifier(context)
        notifier.post(com.meshlink.app.domain.notifications.MessageNotification(
            com.meshlink.app.domain.notifications.NotificationTarget("test-notification","test-sender"),"Phone 2","Test preview",true))
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        compose.waitUntil(5000) { manager.activeNotifications.any { it.tag?.startsWith("meshlink://received/") == true } }
        val notification=manager.activeNotifications.first { it.tag?.startsWith("meshlink://received/") == true }.notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility)
        assertEquals("MeshLink • New message",notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertFalse(notification.publicVersion.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty().contains("Test preview"))
        assertTrue(notification.contentIntent.isImmutable)
        val launchIntent=context.intent
        notification.contentIntent.send()
        compose.waitForIdle()
        try {
            compose.onNodeWithText("Message").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        } finally {
            // ActivityScenario matches teardown callbacks against its original launch intent.
            instrumentation.runOnMainSync { context.intent=launchIntent }
            notifier.cancelAll()
        }
    }
    @Test fun notificationTapTargetValidated() {
        val intent=Intent(AndroidMessageNotifier.ACTION_OPEN_MESSAGE).setData(Uri.parse("meshlink://received/phone-2/message-7"))
        assertEquals("phone-2",AndroidMessageNotifier.target(intent)?.senderId)
        assertEquals("message-7",AndroidMessageNotifier.target(intent)?.messageId)
        assertNull(AndroidMessageNotifier.target(Intent().setData(intent.data)))
        assertNull(AndroidMessageNotifier.target(Intent(AndroidMessageNotifier.ACTION_OPEN_MESSAGE).setData(Uri.parse("meshlink://other/a/b"))))
    }
}
