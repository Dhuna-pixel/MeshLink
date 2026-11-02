package com.meshlink.app.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
    onPermission: () -> Unit, onSystemSettings: () -> Unit, onIdentity: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Settings",style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Done") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Appearance",style = MaterialTheme.typography.titleLarge)
                Text("Applies immediately across MeshLink. System Default follows your device.",style = MaterialTheme.typography.bodyMedium)
                ThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.preferences.theme == mode,onClick = { onTheme(mode) },role = Role.RadioButton).padding(vertical = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.preferences.theme == mode,onClick = null)
                        Spacer(Modifier.width(12.dp)); Text(mode.label)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
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
                Text("Foreground only: keep MeshLink open and networking active to receive. Notifications do not keep BLE running when the app is closed or stopped.",style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onIdentity,modifier = Modifier.fillMaxWidth()) { Text("Edit local node identity") }
        Spacer(Modifier.height(16.dp))
    }
}
