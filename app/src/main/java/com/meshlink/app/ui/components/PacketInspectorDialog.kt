package com.meshlink.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.ui.theme.CodeLogBackground
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.MeshGreenAccent
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary
import com.meshlink.app.utils.JsonSerializer

@Composable
fun PacketInspectorDialog(
    packet: MessagePacket,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshDarkSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Packet Inspection",
                    style = MaterialTheme.typography.titleLarge,
                    color = MeshCyanPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                PacketFieldRow(label = "Message ID", value = packet.messageId)
                PacketFieldRow(label = "Source Node", value = "${packet.sourceName} (${packet.sourceId})")
                PacketFieldRow(label = "Destination Node", value = "${packet.destinationName} (${packet.destinationId})")
                PacketFieldRow(label = "Time-To-Live (TTL)", value = packet.ttl.toString())
                PacketFieldRow(label = "Hop Count", value = packet.hopCount.toString())
                PacketFieldRow(
                    label = "Route Traversed",
                    value = (packet.routeHistory + if (packet.destinationId.isNotEmpty()) listOf(packet.destinationId) else emptyList()).joinToString(" ➔ ")
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Payload Content:",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(CodeLogBackground, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = packet.payload,
                        color = MeshGreenAccent,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Raw Wire JSON:",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(CodeLogBackground, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = JsonSerializer.packetToJson(packet),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MeshCyanPrimary, contentColor = MeshDarkBackground),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun PacketFieldRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
    Divider(color = MeshDarkBackground, thickness = 1.dp)
}
