package com.meshlink.app.ui.components

import android.media.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.*
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.security.bytes64
import kotlinx.coroutines.*
import java.io.File

@Composable
fun VoiceBubble(packet: MessagePacket) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val focus = remember { AudioManager.OnAudioFocusChangeListener { if (it < 0) { player?.pause(); playing = false } } }
    fun stop() {
        player?.release(); player = null; playing = false
        file?.delete(); file = null
        @Suppress("DEPRECATION") audio.abandonAudioFocus(focus)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            progress = runCatching { player!!.currentPosition.toFloat() / player!!.duration.coerceAtLeast(1) }.getOrDefault(0f).coerceIn(0f,1f)
            delay(100)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice message", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledIconButton(modifier = Modifier.size(48.dp), enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    if (player == null) {
                        val temp = withContext(Dispatchers.IO) {
                            File.createTempFile("voice-play-", ".m4a", context.cacheDir).apply {
                                try { writeBytes(requireNotNull(packet.voiceAudio) { "Audio unavailable" }.bytes64()) }
                                catch (e: Exception) { delete(); throw e }
                            }
                        }
                        file = temp
                        val media = MediaPlayer(); player = media
                        media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        media.setDataSource(temp.absolutePath)
                        withContext(Dispatchers.IO) { media.prepare() }
                        media.setOnCompletionListener {
                            playing = false; progress = 1f
                            @Suppress("DEPRECATION")
                            audio.abandonAudioFocus(focus)
                        }
                        media.setOnErrorListener { _, _, _ -> stop(); error = "Playback failed or audio unavailable"; true }
                    }
                    if (playing) {
                        player!!.pause(); playing = false
                        @Suppress("DEPRECATION")
                        audio.abandonAudioFocus(focus)
                    }
                    else {
                        @Suppress("DEPRECATION")
                        check(audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        player!!.start(); playing = true
                    }
                } catch (_: Exception) { stop(); error = "Playback failed or audio unavailable" }
                finally { busy = false }
            }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            val seconds = (packet.voice?.durationMs ?: 0) / 1000
            Text("${(progress * seconds).toInt()}s / ${seconds}s", style = MaterialTheme.typography.labelMedium)
        }
        }
        if (error.isNotEmpty()) Text(error)
    }
}
