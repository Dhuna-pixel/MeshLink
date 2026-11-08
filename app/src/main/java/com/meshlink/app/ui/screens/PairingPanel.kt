package com.meshlink.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.meshlink.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PairingPanel(identity: Identity, id: String, name: String, active: Boolean) {
    var local by remember(id,name) { mutableStateOf<PeerCard?>(null) }
    var peers by remember { mutableStateOf(emptyList<PeerCard>()) }
    var input by remember { mutableStateOf("") }
    var preview by remember(input) { mutableStateOf<PeerCard?>(null) }
    var note by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(id,name) {
        runCatching { withContext(Dispatchers.IO) { identity.own(id,name) to identity.peers() } }
            .onSuccess { local=it.first; peers=it.second }.onFailure { note="Secure identity unavailable" }
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Pairing and security",style=MaterialTheme.typography.titleLarge)
        Text("MeshLink 1.0 · Secure protocol 2")
        Text("Local device: $name · $id")
        local?.let { own ->
            Text("Your fingerprint"); Text(own.fingerprint(),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={ clipboard.setText(AnnotatedString(own.export())); note="Public pairing card copied" }) { Text("Copy public pairing card") }
            Text("Participants can message automatically. To verify someone in person, compare the full fingerprint on their phone.")
            OutlinedTextField(input,{input=it.take(6000)},label={Text("Paste peer’s public card")},maxLines=3,modifier=Modifier.fillMaxWidth())
            if(active) Text("Stop networking to change trust.")
            OutlinedButton(enabled=!active && input.isNotBlank(),onClick={
                runCatching { PeerCard.parse(input) }.onSuccess { preview=it; note="" }.onFailure { note="Rejected: invalid or incompatible pairing card" }
            }) { Text("Inspect peer card") }
            preview?.let { card ->
                Text("Unverified · ${card.name} · ${card.id}")
                Text(card.fingerprint(),style=MaterialTheme.typography.bodySmall)
                Button(enabled=!active,onClick={ runCatching { identity.trust(card,own) }.onSuccess {
                    peers=identity.peers(); input=""; note="Peer trusted"
                }.onFailure { note="Rejected: invalid, conflicting, or unavailable identity" } }) { Text("Fingerprints match · trust peer") }
                TextButton(enabled = !active && card.id != id, onClick={
                    runCatching { identity.reject(card) }.onSuccess { input=""; note="Card rejected" }
                        .onFailure { note="Could not reject this card. Remove any existing trust first." }
                }) { Text("Reject card") }
            }
        }
        peers.forEach { card ->
            com.meshlink.app.ui.components.ParticipantName(card.name, verified = true)
            Text(card.id,style = MaterialTheme.typography.bodySmall)
            TextButton(enabled=!active,onClick={ runCatching { identity.forget(card.id); peers=identity.peers() }
                .onFailure { note="Could not update trust" } }) { Text("Remove trust") }
        }
        if(note.isNotBlank()) Text(note)
    } }
}
