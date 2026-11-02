package com.meshlink.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.domain.models.LogLevel
import com.meshlink.app.domain.models.NodeStatus
import com.meshlink.app.ui.theme.MeshAmberWarning
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.MeshRedError

@Composable
fun NodeStatusBadge(
    status: NodeStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, label) = when (status) {
        NodeStatus.CONNECTED -> Triple(MeshGreenAccent.copy(alpha = 0.2f), MeshGreenAccent, "CONNECTED")
        NodeStatus.CONNECTING -> Triple(MeshAmberWarning.copy(alpha = 0.2f), MeshAmberWarning, "CONNECTING")
        NodeStatus.DISCOVERED -> Triple(MeshCyanPrimary.copy(alpha = 0.2f), MeshCyanPrimary, "DISCOVERED")
        NodeStatus.DISCONNECTED -> Triple(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), MaterialTheme.colorScheme.onSurfaceVariant, "OFFLINE")
    }

    Row(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(textColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun LogLevelBadge(
    level: LogLevel,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, label) = when (level) {
        LogLevel.INFO -> Triple(MeshCyanPrimary.copy(alpha = 0.15f), MeshCyanPrimary, "INFO")
        LogLevel.SUCCESS -> Triple(MeshGreenAccent.copy(alpha = 0.15f), MeshGreenAccent, "SUCCESS")
        LogLevel.WARNING -> Triple(MeshAmberWarning.copy(alpha = 0.15f), MeshAmberWarning, "WARN")
        LogLevel.ERROR -> Triple(MeshRedError.copy(alpha = 0.15f), MeshRedError, "ERROR")
    }

    Text(
        text = label,
        color = textColor,
        fontSize = 9.sp,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
