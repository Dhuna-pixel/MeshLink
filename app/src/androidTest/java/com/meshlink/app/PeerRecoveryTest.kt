package com.meshlink.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run on two isolated test installations with peerRole=driver/responder and the same runId. */
class PeerRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun restoreChatAndReconnect() {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("peerRole")
        assumeTrue(role in listOf("driver", "responder"))
        val run = requireNotNull(args.getString("runId"))
        val repo = (compose.activity.application as MeshLinkApp).meshRepository
        fun await(label: String, seconds: Int = 80, condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(200)
            assertTrue("$label: " + repo.networkLogs.value.take(24).joinToString { it.tag + "/" + it.message },condition())
        }
        fun received(text: String) = repo.messages.value.any { it.sourceId != repo.currentNode.value.nodeId && it.payload == text }
        fun delivered(text: String) = repo.messages.value.any {
            it.payload == text && repo.deliveryStatuses.value[it.messageId]?.startsWith("Delivered") == true
        }
        compose.runOnUiThread { repo.requestNetworkingStart() }
        try {
            await("First authenticated link") { repo.connectedNodes.value.isNotEmpty() }
            val peer = repo.connectedNodes.value.single()
            if (role == "responder") {
                for (stage in listOf("initial", "unblocked", "send-after-disconnect", "reconnected", "long-" + "x".repeat(1800))) {
                    val text = "$run-$stage"
                    await("Receive $stage", 120) { received(text) }
                    compose.runOnUiThread { repo.sendMessage(peer.nodeId,peer.nodeName,"reply-$text") }
                    await("Reply ACK $stage") { delivered("reply-$text") }
                }
                compose.runOnUiThread { repo.sendMessage(peer.nodeId,peer.nodeName,"finished-$run") }
                await("Final confirmation") { delivered("finished-$run") }
            } else {
                compose.onNode(hasText("Nearby") and hasClickAction()).performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Chat").performScrollTo().performClick()
                fun exchange(stage: String) {
                    val text = "$run-$stage"
                    compose.onNodeWithText("Message").performTextInput(text)
                    compose.onNodeWithText("Send").assertIsEnabled().performClick()
                    await("Destination ACK $stage") { delivered(text) }
                    await("Incoming reply $stage") { received("reply-$text") }
                }
                exchange("initial")
                compose.onNodeWithText("Block").performClick()
                assertNull(repo.identity.participant(peer.nodeId))
                compose.onNodeWithText("Unblock").performClick()
                exchange("unblocked")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNode(hasText("Nearby") and hasClickAction()).performClick()
                compose.onNodeWithText("Disconnect").performScrollTo().performClick()
                await("Disconnected",10) { repo.connectedNodes.value.isEmpty() }
                Thread.sleep(1200)
                compose.onNodeWithText("Chat").performScrollTo().performClick()
                exchange("send-after-disconnect")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNode(hasText("Nearby") and hasClickAction()).performClick()
                compose.onNodeWithText("Disconnect").performScrollTo().performClick()
                await("Disconnected again",10) { repo.connectedNodes.value.isEmpty() }
                Thread.sleep(1200)
                compose.onNodeWithText("Connect").performScrollTo().performClick()
                await("Authenticated reconnect") { repo.connectedNodes.value.any { it.nodeId == peer.nodeId } }
                compose.onNodeWithText("Chat").performScrollTo().performClick()
                exchange("reconnected")
                exchange("long-" + "x".repeat(1800))
                await("Both reply ACKs") { received("finished-$run") }
                Thread.sleep(3000)
            }
        } finally { compose.runOnUiThread { repo.requestNetworkingStop() } }
    }
}
