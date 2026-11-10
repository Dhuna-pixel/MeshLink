package com.meshlink.app.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal class SpeechDraft {
    val audioLevel = SpeechLevel()
    private var listening = false
    private var generation = 0L
    private var token: Long? = null
    private var partial = ""
    var disposed = false
        private set
    val active get() = token != null
    fun start(): Long? {
        if (active || disposed) return null
        partial = ""
        listening = true
        audioLevel.reset()
        return (++generation).also { token = it }
    }
    fun owns(id: Long) = token == id && !disposed
    fun partial(id: Long, text: String) { if (owns(id) && text.isNotBlank()) partial = text }
    fun rms(id: Long, rmsDb: Float, nowMs: Long) {
        if (owns(id) && listening) audioLevel.sample(rmsDb, nowMs)
    }
    fun endSpeech(id: Long) {
        if (owns(id)) { listening = false; audioLevel.reset() }
    }
    fun cancel() { token = null; partial = ""; listening = false; audioLevel.reset() }
    fun dispose() { cancel(); disposed = true }
    fun result(current: String, spoken: String): String =
        token?.let { finish(it, current, spoken) } ?: current
    fun finish(id: Long, current: String, spoken: String? = null): String {
        if (!owns(id)) return current
        val text = spoken?.takeIf { it.isNotBlank() } ?: partial
        cancel()
        return if (text.isBlank()) current else current + (if (current.isEmpty() || current.last().isWhitespace()) "" else " ") + text.trim()
    }
}

@Composable
fun OfflineSpeechInput(value: String, edit: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestValue by rememberUpdatedState(value)
    val latestEdit by rememberUpdatedState(edit)
    val draft = remember { SpeechDraft() }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    fun release() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val old = recognizer
        recognizer = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }
    fun cancel() {
        draft.cancel() // Invalidate before cancel/destroy can deliver callbacks.
        release()
        status = "Canceled"
    }
    fun start() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (draft.active || draft.disposed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val session = draft.start() ?: return
        try {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            draft.cancel()
            status = "Unavailable: on-device speech recognition is not installed. You can keep typing."
            return
        }
            release()
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = engine
            status = "Listening"
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) {
                    if (recognizer === engine) draft.rms(session, rmsdB, android.os.SystemClock.uptimeMillis())
                }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { if (draft.owns(session) && recognizer === engine) { draft.endSpeech(session); status = "Transcribing" } }
                override fun onError(error: Int) {
                    if (!draft.owns(session) || recognizer !== engine) return
                    val preserved = draft.finish(session, latestValue)
                    release()
                    if (preserved != latestValue) latestEdit(preserved)
                    status = when (error) {
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                            "Speech recognition disconnected. Tap the microphone to try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is required for speech input. You can keep typing."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No speech recognized. Tap the microphone to try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "Speech recognition is busy. Tap the microphone to try again."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Unavailable: the offline language model is missing or unsupported. Install it in your device speech settings, or keep typing."
                        else -> "Speech recognition could not finish. Tap the microphone to try again or keep typing."
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (!draft.owns(session) || recognizer !== engine) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val updated = draft.finish(session, latestValue, text)
                    release()
                    if (updated != latestValue) latestEdit(updated)
                    status = if (text.isBlank()) "No speech recognized. Try again or keep typing." else null
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer === engine) draft.partial(session,
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        } catch (_: Exception) {
            draft.cancel()
            release()
            status = "Unavailable: offline speech input could not start. You can keep typing."
        }
    }
    var permissionPending by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = permissionPending
        permissionPending = false
        if (!requested || draft.disposed) return@rememberLauncherForActivityResult
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        else status = "Microphone permission unavailable. You can keep typing."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { permissionPending = false; cancel() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); permissionPending = false; draft.dispose(); release() }
    }
    IconButton(onClick = {
        if (draft.active) cancel()
        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else if (!permissionPending) { permissionPending = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }) { Icon(Icons.Default.Mic, contentDescription = "Offline speech input") }
    status?.let { message ->
        AlertDialog(onDismissRequest = { if (draft.active) cancel() else status = null },
            title = { Text("Offline speech input") }, text = { if (message == "Listening" && draft.active) SpeechWaveform(draft.audioLevel) else Text(message) },
            confirmButton = { TextButton({ if (draft.active) cancel() else status = null }) { Text(if (draft.active) "Cancel" else "OK") } })
    }
}
