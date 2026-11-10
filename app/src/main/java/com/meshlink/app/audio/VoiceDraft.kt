package com.meshlink.app.audio

import android.content.Context
import android.media.*
import android.os.SystemClock
import android.util.Log
import java.io.File
import com.meshlink.app.domain.voice.VoiceManifest

/** Commands serialized on the screen's worker; callbacks only signal the current generation. */
class VoiceDraft(context: Context) : AutoCloseable {
    private val directory = File(context.cacheDir, "voice-drafts").apply { mkdirs() }
    private var file: File? = null
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private val state = RecordingState()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var interrupted = false
        private set
    private val focus = AudioManager.OnAudioFocusChangeListener {
        if (it < 0) { interrupted = true; state.signal(state.generation, RecordingState.Reason.INTERRUPTION) }
    }
    var startedAt = 0L; private set
    var durationMs = 0; private set
    val recording get() = state.phase == RecordingState.Phase.RECORDING
    val ready get() = state.phase == RecordingState.Phase.READY
    val stopReason get() = state.reason
    val position get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val playing get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    private fun trace(event: String) {
        Log.i("VoiceDraft", "$event elapsedMs=${if(startedAt == 0L) 0 else SystemClock.elapsedRealtime()-startedAt} bytes=${file?.length() ?: 0} phase=${state.phase}")
    }
    private fun acquireFocus() {
        interrupted = false
        @Suppress("DEPRECATION")
        check(audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is busy. Try again." }
    }
    @Suppress("DEPRECATION") private fun releaseFocus() { audio.abandonAudioFocus(focus) }
    fun start() {
        discard("new recording")
        directory.listFiles()?.forEach { Log.i("VoiceDraft", "delete abandoned draft bytes=${it.length()}"); it.delete() }
        check(directory.usableSpace >= 1024 * 1024) { "Insufficient storage" }
        acquireFocus()
        val token = state.start()
        try {
            file = File.createTempFile("draft-", ".m4a", directory)
            @Suppress("DEPRECATION") val next = MediaRecorder()
            recorder = next
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1); next.setAudioSamplingRate(16000); next.setAudioEncodingBitRate(16000)
            next.setMaxDuration(VoiceManifest.MAX_DURATION_MS.toInt())
            next.setMaxFileSize(VoiceManifest.MAX_ENCRYPTED_BYTES.toLong() - 1024)
            next.setOnInfoListener { _, what, extra ->
                trace("recorder info=$what extra=$extra token=$token")
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> state.signal(token, RecordingState.Reason.DURATION)
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> state.signal(token, RecordingState.Reason.SIZE)
                }
            }
            next.setOnErrorListener { _, what, extra -> trace("recorder error=$what extra=$extra token=$token"); state.signal(token, RecordingState.Reason.ERROR) }
            next.setOutputFile(file!!.absolutePath); next.prepare(); next.start()
            startedAt = SystemClock.elapsedRealtime(); trace("started")
        } catch (e: Exception) { trace("start failed ${e.javaClass.simpleName}"); discard("start failure"); throw e }
    }
    fun finish(reason: RecordingState.Reason = RecordingState.Reason.MANUAL) {
        if (!state.beginFinish(reason)) { trace("finish ignored reason=$reason"); return }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        trace("finish requested reason=$reason manual=${reason == RecordingState.Reason.MANUAL}")
        val current = recorder; recorder = null
        var stopFailed = false
        try {
            current?.setOnInfoListener(null); current?.setOnErrorListener(null)
            try { current?.stop(); trace("stop succeeded") }
            catch (e: RuntimeException) { stopFailed = true; trace("stop threw ${e.javaClass.simpleName}") }
        } finally { current?.release(); releaseFocus(); interrupted = false }
        val draft = file
        if (draft == null || draft.length() !in 1..(VoiceManifest.MAX_ENCRYPTED_BYTES - 1024).toLong()) {
            trace("validation failed empty or oversized output"); discard("invalid output size")
            error("Recording was empty or exceeded the file-size limit. Please record again.")
        }
        durationMs = RecordingState.duration(0,elapsed)
        // A preparation failure must not destroy a finalized file; Play can retry initialization.
        try { preparePlayer() }
        catch (e: Exception) {
            trace("preview prepare failed ${e.javaClass.simpleName} stopFailed=$stopFailed")
            player?.release(); player = null
            if (stopFailed) { discard("stop and media validation failed"); error("Recording could not be finalized. Please record again.") }
        }
        state.ready(); trace("finalized durationMs=$durationMs")
    }
    private fun preparePlayer(): MediaPlayer {
        player?.let { return it }
        val draft = file?.takeIf { it.isFile && it.length() > 0 } ?: error("Recording file is unavailable. Please record again.")
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setDataSource(draft.absolutePath); media.prepare()
            val metadata = runCatching { media.duration }.getOrDefault(0)
            trace("duration metadataMs=$metadata")
            durationMs = RecordingState.duration(metadata,durationMs.toLong())
            media.setOnCompletionListener { releaseFocus() }
            media.setOnErrorListener { _, what, extra -> trace("player error=$what extra=$extra"); interrupted = true; true }
            player = media; return media
        } catch (e: Exception) { media.release(); throw e }
    }
    fun completedFile(): File { check(ready) { "Recording is not ready yet" }; return file?.takeIf { it.isFile } ?: error("Recording file is unavailable") }
    fun toggle() {
        check(ready) { "Recording is not ready yet" }
        try {
            val media = preparePlayer()
            if (media.isPlaying) { media.pause(); releaseFocus() }
            else { acquireFocus(); if (media.currentPosition >= durationMs) media.seekTo(0); media.start() }
        } catch (e: Exception) {
            trace("play failed ${e.javaClass.simpleName}"); player?.release(); player = null; releaseFocus()
            error("Preview could not play. Your recording is preserved; try Play again.")
        }
    }
    fun pause() { runCatching { if (playing) player?.pause() }; releaseFocus(); interrupted = false }
    fun discard(reason: String) {
        state.clear()
        recorder?.let { it.setOnInfoListener(null); it.setOnErrorListener(null); runCatching { it.stop() }; it.release() }; recorder = null
        player?.release(); player = null; releaseFocus()
        trace("delete reason=$reason")
        file?.let { trace("delete result=${it.delete()}") }; file = null; durationMs = 0; startedAt = 0; interrupted = false
    }
    override fun close() = discard("close")
}
