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
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshDarkSurfaceVariant
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
    val filteredLogs = if (uiState.logFilterLevel == null) {
        uiState.networkLogs
    } else {
        uiState.networkLogs.filter { it.level == uiState.logFilterLevel }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MeshDarkBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Live Engineering Event Log",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "Chronological routing & multi-hop trace",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            IconButton(onClick = onClearLogs) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear Logs",
                    tint = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = uiState.logFilterLevel == null,
                onClick = { onFilterChanged(null) },
                label = { Text("ALL (${uiState.networkLogs.size})", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshCyanPrimary,
                    selectedLabelColor = MeshDarkBackground,
                    containerColor = MeshDarkSurfaceVariant,
                    labelColor = TextPrimary
                )
            )
            FilterChip(
                selected = uiState.logFilterLevel == LogLevel.SUCCESS,
                onClick = { onFilterChanged(if (uiState.logFilterLevel == LogLevel.SUCCESS) null else LogLevel.SUCCESS) },
                label = { Text("SUCCESS", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshGreenAccent,
                    selectedLabelColor = MeshDarkBackground,
                    containerColor = MeshDarkSurfaceVariant,
                    labelColor = MeshGreenAccent
                )
            )
            FilterChip(
                selected = uiState.logFilterLevel == LogLevel.INFO,
                onClick = { onFilterChanged(if (uiState.logFilterLevel == LogLevel.INFO) null else LogLevel.INFO) },
                label = { Text("INFO", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshCyanPrimary,
                    selectedLabelColor = MeshDarkBackground,
                    containerColor = MeshDarkSurfaceVariant,
                    labelColor = MeshCyanPrimary
                )
            )
            FilterChip(
                selected = uiState.logFilterLevel == LogLevel.WARNING,
                onClick = { onFilterChanged(if (uiState.logFilterLevel == LogLevel.WARNING) null else LogLevel.WARNING) },
                label = { Text("WARN", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshAmberWarning,
                    selectedLabelColor = MeshDarkBackground,
                    containerColor = MeshDarkSurfaceVariant,
                    labelColor = MeshAmberWarning
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Terminal Log Console
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CodeLogBackground, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No log events matching filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs) { logEntry ->
                        LogEntryItem(logEntry)
                    }
                }
            }
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
            .background(MeshDarkSurface.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogLevelBadge(level = entry.level)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[${entry.tag}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshCyanPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Text(
                text = entry.getFormattedTime(),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = entry.message,
            color = messageColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        )

        entry.packetSummary?.let { summary ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary,
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
