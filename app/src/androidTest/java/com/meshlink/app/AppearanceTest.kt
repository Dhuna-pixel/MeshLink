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
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        listOf("Light","Dark","System Default").forEach {
            compose.onNodeWithText(it).performClick()
            compose.onNodeWithText(it).assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            listOf("Status","Neighbors (0)","Messages","Logs").forEach { destination ->
                compose.onNodeWithText(destination).performClick()
                compose.waitForIdle()
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Edit local node identity").performScrollTo().performClick()
            compose.onNodeWithText("Node Display Name").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Appearance").performScrollTo()
        }
    }
    @Test fun actualNotificationHasPrivacyAndOpensMessages() {
        val context=compose.activity
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        val notifier=AndroidMessageNotifier(context)
        notifier.post(com.meshlink.app.domain.notifications.MessageNotification(
            com.meshlink.app.domain.notifications.NotificationTarget("test-notification","test-sender"),"Phone 2","Test preview",true))
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        val notification=manager.activeNotifications.single().notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility)
        assertEquals("MeshLink • New message",notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertFalse(notification.publicVersion.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty().contains("Test preview"))
        assertTrue(notification.contentIntent.isImmutable)
        val launchIntent=context.intent
        notification.contentIntent.send()
        compose.waitForIdle()
        try {
            compose.onNodeWithText("Messages").assertIsSelected()
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
