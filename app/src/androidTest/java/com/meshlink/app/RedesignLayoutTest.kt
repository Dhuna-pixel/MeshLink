package com.meshlink.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.data.preferences.ThemeMode
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.network.DestinationOption
import com.meshlink.app.presentation.viewmodels.*
import com.meshlink.app.ui.components.MeshStatus
import com.meshlink.app.ui.screens.*
import com.meshlink.app.ui.theme.MeshLinkTheme
import com.meshlink.app.ui.theme.MeshSpace
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Presentation fixtures only: no messages, peers or connections are added to the repository. */
@OptIn(ExperimentalMaterial3Api::class)
class RedesignLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewLayoutsAndOfflineComposer() {
        val time = 1_794_290_400_000L
        val messages = listOf(
            MessagePacket("review-1", "alex", "Alex Rivera", "me", "You", "Made it to the meeting point. Let me know when you’re nearby.", timestamp = time),
            MessagePacket("review-2", "me", "You", "alex", "Alex Rivera", "On my way. I’ll meet you by the north entrance.", timestamp = time + 60_000)
        )
        var dark by mutableStateOf(true)
        var screen by mutableStateOf("Chats")
        var input by mutableStateOf("")
        val fixture = MeshUiState(currentNode = Node("me", "Your device"), loadingHistory = false,
            peerId = "alex", selectedTab = MeshScreenTab.CONVERSATION,
            destinations = listOf(DestinationOption("alex", "Alex Rivera", "Unavailable", verified = true)),
            messages = messages, deliveryStatuses = mapOf("review-2" to "Queued"))
        compose.setContent {
            MeshLinkTheme(darkTheme = dark) {
                Scaffold(topBar = { TopAppBar(title = { Column {
                    Text(if (screen == "Conversation") "Alex Rivera" else screen)
                    MeshStatus("Mesh off", false)
                } }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = MeshSpace.contentWidth).fillMaxSize()) {
                            when (screen) {
                                "Chats" -> ChatsScreen(fixture, mapOf("alex" to messages), {}, {}, {})
                                "Conversation" -> ConversationScreen(fixture.copy(messageInputText = input), messages, { input = it }, {}, {}, { _, _ -> }, {})
                                "Nearby" -> NeighborsScreen(fixture.copy(destinations = emptyList()), {}, {}, {}, {}, {}, {}, { _, _ -> }, { null })
                                "Settings" -> SettingsScreen(fixture.copy(preferences = fixture.preferences.copy(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)), {}, {}, {}, {}, {}, {}, {}, {})
                                "Events" -> LogScreen(fixture, {}, {})
                            }
                        }
                    }
                }
            }
        }
        for (night in listOf(true, false)) {
            compose.runOnIdle { dark = night }
            for (destination in listOf("Chats", "Conversation", "Nearby", "Settings", "Events")) {
                compose.runOnIdle { screen = destination }
                compose.waitForIdle()
                if (destination == "Conversation") {
                    compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
                    compose.onNodeWithText("Record voice message").assertIsDisplayed()
                    compose.onNodeWithText("Queued").assertIsDisplayed()
                }
                if (destination == "Events") compose.onNodeWithText("Copy transport diagnostics").assertIsDisplayed()
                capture("${if (night) "dark" else "light"}-${destination.lowercase()}")
            }
        }
        compose.runOnIdle { screen = "Conversation" }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Checking keyboard layout")
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        capture("keyboard")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val size = InstrumentationRegistry.getArguments().getString("reviewSize") ?: "phone"
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$size-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
