package com.meshlink.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.meshlink.app.audio.VoiceDraft
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/** Tap controls deliberately support TalkBack and avoid press/hold gesture conflicts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceDraftControls(recipient: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dispatcher = remember { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    val scope = remember { CoroutineScope(SupervisorJob() + dispatcher) }
    val draft = remember { VoiceDraft(context.applicationContext) }
    var mode by remember { mutableStateOf("idle") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var elapsed by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    fun action(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching(block)
            withContext(Dispatchers.Main) {
                result.onSuccess { mode = it }.onFailure { notice = it.message ?: "Audio failed. Please try again."; mode = if (draft.ready) "preview" else "idle" }
                busy = false
            }
        }
    }
    fun start() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        notice = ""
        action { draft.start(); "recording" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else notice = "Microphone permission denied. You can still send text messages."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                scope.launch { draft.discard("screen paused"); withContext(Dispatchers.Main) { mode = "idle"; playing = false; notice = "Audio draft discarded when leaving the screen." } }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            scope.launch { draft.discard("screen disposed"); scope.cancel(); dispatcher.close() }
        }
    }
    LaunchedEffect(mode) {
        while (mode != "idle") {
            delay(100)
            withContext(dispatcher) {
                if (!busy && draft.recording && (draft.stopReason != null || SystemClock.elapsedRealtime() - draft.startedAt >= 29_500)) {
                    val reason = draft.stopReason ?: com.meshlink.app.audio.RecordingState.Reason.DURATION
                    val result = runCatching { draft.finish(reason) }
                    withContext(Dispatchers.Main) {
                        mode = if (draft.ready) "preview" else "idle"
                        notice = if (result.isFailure) result.exceptionOrNull()?.message ?: "Recording failed" else when (reason) {
                            com.meshlink.app.audio.RecordingState.Reason.DURATION -> "Recording reached the duration limit. Preview before sending."
                            com.meshlink.app.audio.RecordingState.Reason.SIZE -> "Recording reached the file-size limit. Preview before sending."
                            com.meshlink.app.audio.RecordingState.Reason.ERROR -> "The recorder reported an error. Preview the recovered recording."
                            else -> "Recording interrupted. Preview before sending."
                        }
                    }
                } else if (mode == "preview" && draft.interrupted) draft.pause()
                val time = if (mode == "recording") (SystemClock.elapsedRealtime() - draft.startedAt).toInt() else draft.position
                val active = draft.playing
                withContext(Dispatchers.Main) { elapsed = time; playing = active }
            }
        }
    }
    val recordingAlpha by animateFloatAsState(if (mode == "recording" && elapsed / 1000 % 2 == 0) 0.6f else 1f, label = "Recording pulse")
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodySmall)
        when (mode) {
            "idle" -> TextButton(enabled = !busy, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else explain = true
            }) { Text("Record voice message") }
            "recording" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("●", Modifier.alpha(recordingAlpha), color = MaterialTheme.colorScheme.error)
                    Text("Recording · ${elapsed / 1000}s / 30s", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = { (elapsed / 30_000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { mode = "finalizing"; action { draft.finish(); "preview" } }) { Text("Stop") }
                    TextButton(enabled = !busy, onClick = { action { draft.discard("user canceled or discarded"); "idle" }; notice = "Recording canceled" }) { Text("Cancel") }
                }
            }
            "finalizing" -> Text("Finishing recording…")
            "preview" -> {
                Text("Voice preview · ${elapsed / 1000}s / ${draft.durationMs / 1000}s")
                LinearProgressIndicator(progress = { (elapsed.toFloat() / draft.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                FlowRow {
                    Button(enabled = !busy, onClick = { action { draft.toggle(); "preview" } }) { Text(if (playing) "Pause" else "Play") }
                    TextButton(enabled = !busy, onClick = { action { draft.discard("user canceled or discarded"); "idle" } }) { Text("Discard") }
                }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            val repo = (context.applicationContext as com.meshlink.app.MeshLinkApp).meshRepository
                            repo.sendVoiceDraft(draft.completedFile(), recipient, draft.durationMs.toLong())
                        }
                        if (result.isSuccess) draft.discard("submitted to repository")
                        withContext(Dispatchers.Main) {
                            busy = false
                            if (result.isSuccess) mode = "idle"
                            else notice = result.exceptionOrNull()?.message ?: "Voice could not be sent"
                        }
                    }
                }) { Text("Send voice") }
            }
        }
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("Microphone permission") },
        text = { Text("MeshLink needs microphone access to record your voice preview. Recording starts only when you choose Record and stops when you leave this screen.") },
        confirmButton = { TextButton({ explain = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
        dismissButton = { TextButton({ explain = false }) { Text("Cancel") } })
}
