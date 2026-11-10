package com.meshlink.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meshlink.app.ui.theme.*

@Composable
fun IdentityAvatar(name: String, group: Boolean = false) {
    Surface(shape = if (group) MaterialTheme.shapes.medium else CircleShape,
        color = if (group) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(MeshSpace.touch), contentAlignment = Alignment.Center) {
            Text(name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "·" },
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun MeshStatus(label: String, active: Boolean, problem: Boolean = false) {
    val color by animateColorAsState(if (problem) MaterialTheme.colorScheme.error
        else if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "Mesh status")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MeshSpace.small)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyConversation(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(MeshSpace.section), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MeshSpace.medium)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.padding(20.dp).size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun MessageComposer(value: String, edit: (String) -> Unit, send: () -> Unit, enabled: Boolean,
    error: Boolean, label: String = "Message") {
    Row(Modifier.fillMaxWidth().padding(vertical = MeshSpace.small),
        horizontalArrangement = Arrangement.spacedBy(MeshSpace.small), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(value, edit, modifier = Modifier.weight(1f), maxLines = 4,
            shape = MaterialTheme.shapes.large, label = { Text(label) }, isError = error,
            trailingIcon = { OfflineSpeechInput(value, edit) })
        Button(send, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = MeshSpace.medium)) { Text("Send") }
    }
}

@Composable
fun DeliveryStatus(label: String) {
    val color = when {
        label.startsWith("Failed") -> MaterialTheme.colorScheme.error
        label.startsWith("Delivered") -> MaterialTheme.colorScheme.secondary
        label == "Queued" || label == "Discovering route" -> MeshAmberWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, Modifier.animateContentSize(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Preview(name = "Dark · phone", widthDp = 360, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Light · tablet", widthDp = 800, showBackground = true)
@Composable
private fun ComponentsPreview() {
    MeshLinkTheme {
        Surface {
            Column(Modifier.padding(MeshSpace.medium), verticalArrangement = Arrangement.spacedBy(MeshSpace.medium)) {
                MeshStatus("Mesh off", false)
                IdentityAvatar("Alex Rivera")
                EmptyConversation("Your conversations start here", "Find a nearby device to start a conversation over the mesh.")
                MessageComposer("", {}, {}, false, false)
            }
        }
    }
}
