package com.meshlink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.NetworkLogEntry
import com.meshlink.app.presentation.viewmodels.MeshUiState
import com.meshlink.app.ui.components.LogLevelBadge
import com.meshlink.app.ui.theme.CodeLogBackground
import com.meshlink.app.ui.theme.MeshAmberWarning
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshBackground
import com.meshlink.app.ui.theme.MeshSurface
import com.meshlink.app.ui.theme.MeshSurfaceVariant
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshRedError
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@Composable
fun LogScreen(
    uiState: MeshUiState,
    onFilterChanged: (LogLevel?) -> Unit,
    onClearLogs: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val filteredLogs = if (uiState.logFilterLevel == null) {
        uiState.networkLogs
    } else {
        uiState.networkLogs.filter { it.level == uiState.logFilterLevel }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
            val safeTags=setOf("VOICE","AODV","BLE-HEALTH","MESH-HEALTH","BLE-WRITE","BLE-QUEUE","BLE-WORKER","FRAGMENT")
            val text=uiState.networkLogs.filter { it.tag in safeTags }.take(100).reversed()
                .joinToString("\n") { "${it.timestamp} ${it.tag} ${it.message}" }
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        }) { Text("Copy transport diagnostics") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Event stream", style = MaterialTheme.typography.titleLarge)
                Text("Routing, connections and transfers", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            IconButton(onClick = onClearLogs) { Icon(Icons.Default.Delete, "Clear Logs", tint = TextSecondary) }
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = uiState.logFilterLevel == null, onClick = { onFilterChanged(null) }, label = { Text("All (${uiState.networkLogs.size})") }) }
            items(LogLevel.entries.toList()) { level ->
                FilterChip(selected = uiState.logFilterLevel == level,
                    onClick = { onFilterChanged(if (uiState.logFilterLevel == level) null else level) },
                    label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        if (filteredLogs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No log events matching filter.", color = TextSecondary)
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredLogs) { LogEntryItem(it) }
        }
    }
}

@Composable
private fun LogEntryItem(entry: NetworkLogEntry) {
    val messageColor = when (entry.level) {
        LogLevel.INFO -> TextPrimary
        LogLevel.SUCCESS -> MeshGreenAccent
        LogLevel.WARNING -> MeshAmberWarning
        LogLevel.ERROR -> MeshRedError
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSurface.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                LogLevelBadge(level = entry.level)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[${entry.tag}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshCyanPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            Text(
                text = entry.getFormattedTime(),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = entry.message,
            color = messageColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp
        )

        entry.packetSummary?.let { summary ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
