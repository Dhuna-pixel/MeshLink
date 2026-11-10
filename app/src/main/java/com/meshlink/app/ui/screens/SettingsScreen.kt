package com.meshlink.app.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import com.meshlink.app.ui.components.IdentityAvatar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meshlink.app.data.preferences.ThemeMode
import com.meshlink.app.presentation.viewmodels.MeshUiState

@Composable
fun SettingsScreen(state: MeshUiState, onBack: () -> Unit, onTheme: (ThemeMode) -> Unit,
    onNotifications: (Boolean) -> Unit, onPrivatePreview: (Boolean) -> Unit,
    onPermission: () -> Unit, onSystemSettings: () -> Unit, onIdentity: () -> Unit, navigate: (com.meshlink.app.presentation.viewmodels.MeshScreenTab) -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IdentityAvatar(state.currentNode.nodeName)
                Text("Your preferences",style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onBack) { Text("Done") }
        }
        OutlinedButton(onClick = onIdentity, modifier = Modifier.fillMaxWidth()) { Text("Edit my profile") }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp).selectableGroup()) {
                Text("Appearance",style = MaterialTheme.typography.titleLarge)
                Text("Applies immediately across MeshLink. System Default follows your device.",style = MaterialTheme.typography.bodyMedium)
                ThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = state.preferences.theme == mode,onClick = { onTheme(mode) },role = Role.RadioButton).padding(vertical = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.preferences.theme == mode,onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.titleSmall)
                            Text(when (mode.label) { "Dark" -> "Charcoal with soft teal accents"; "Light" -> "Warm whites and graphite text"; else -> "Follow your device appearance" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Message notifications",style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable message notifications",Modifier.weight(1f))
                    Switch(checked = state.preferences.notificationsEnabled,onCheckedChange = onNotifications)
                }
                Text(state.notificationSystemStatus,style = MaterialTheme.typography.bodyMedium)
                Text("Notifications are sent only for valid BLE messages delivered to this phone. Relays and routing traffic never notify.",style = MaterialTheme.typography.bodySmall)
                if (Build.VERSION.SDK_INT >= 33 && !state.preferences.notificationPermissionAsked) {
                    OutlinedButton(onClick = onPermission,enabled = state.preferences.notificationsEnabled) { Text("Allow Android notifications") }
                }
                OutlinedButton(onClick = onSystemSettings) { Text("Android notification settings") }
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide message previews on lock screen",Modifier.weight(1f))
                    Switch(checked = state.preferences.hideLockScreenPreview,onCheckedChange = onPrivatePreview)
                }
                Text("Android's channel, lock-screen and Do Not Disturb settings take priority. Changing preview privacy clears existing MeshLink notifications.",style = MaterialTheme.typography.bodySmall)
                Text("Start mesh networking to receive while the screen is locked. The mesh service has its own status notification.",style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Advanced",style = MaterialTheme.typography.titleMedium)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { navigate(com.meshlink.app.presentation.viewmodels.MeshScreenTab.DIAGNOSTICS) }) { Text("Diagnostics") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { navigate(com.meshlink.app.presentation.viewmodels.MeshScreenTab.PHYSICAL) }) { Text("Physical BLE Test Configuration") }
    }
}
