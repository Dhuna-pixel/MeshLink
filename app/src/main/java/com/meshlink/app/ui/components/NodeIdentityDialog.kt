package com.meshlink.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshBackground
import com.meshlink.app.ui.theme.MeshSurface
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@Composable
fun NodeIdentityDialog(
    initialNodeId: String,
    initialNodeName: String,
    initialAbout: String,
    onConfirm: (nodeId: String, nodeName: String, about: String) -> Unit,
    onDismiss: () -> Unit
) {
    var about by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialAbout) }
    var nodeName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialNodeName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSurface,
        title = {
            Text(
                text = "My profile",
                style = MaterialTheme.typography.titleLarge,
                color = MeshCyanPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Your name and status are visible to people participating in the mesh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = nodeName,
                    onValueChange = { nodeName = it.take(64) },
                    label = { Text("Display name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshCyanPrimary,
                        unfocusedBorderColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(about, { about = it.take(160) }, label = { Text("Status (optional)") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)


            }
        },
        confirmButton = {
            Button(
                enabled = nodeName.isNotBlank(),
                onClick = { onConfirm(initialNodeId, nodeName, about) },
                colors = ButtonDefaults.buttonColors(containerColor = MeshCyanPrimary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
