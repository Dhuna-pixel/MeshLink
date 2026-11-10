package com.meshlink.app.domain.voice

import com.meshlink.app.security.*
import com.meshlink.app.domain.models.MessagePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@Serializable
data class VoiceWire(val version: Int = 1, val kind: String, val transfer: String,
    val index: Int = -1, val size: Int = 0, val count: Int = 0,
    val metadata: VoicePayloadMetadata? = null, val data: String = "") {
    fun validate() {
        require(version == 1 && transfer.length in 1..80)
        require(kind in setOf("START", "DATA", "END", "ACK", "ERROR"))
        require(size in 1..2_000_000 && count == (size + BLOCK - 1) / BLOCK)
        require(if (kind == "DATA") index in 0 until count && data.bytes64().size == minOf(BLOCK, size - index * BLOCK)
            else index == -1 && data.isEmpty())
        require((kind == "START") == (metadata != null))
        metadata?.let { it.validate(); require(it.transferId == transfer) }
    }
    fun encode(): String { validate(); return PREFIX + Json.encodeToString(this).also { require(it.toByteArray().size <= 1900) } }
    companion object {
        const val BLOCK = 768
        const val PREFIX = "MESHLINK-VOICE/1:"
        fun decode(text: String): VoiceWire {
            require(text.startsWith(PREFIX) && text.toByteArray().size <= 2000)
            return Json.decodeFromString<VoiceWire>(text.removePrefix(PREFIX)).also { it.validate() }
        }
    }
}

/** Endpoint-only coordinator. submit routes ordinary E2E packets through production AODV. */
class VoiceTransfer(private val directory: File, private val scope: CoroutineScope,
    private val local: () -> String, private val trusted: (String) -> Boolean,
    private val submit: suspend (String, VoiceWire) -> Unit,
    private val commit: suspend (VoicePayloadService.Payload, MessagePacket) -> Unit,
    private val receipt: suspend (String, String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {}) {
    val states = MutableStateFlow<Map<String,String>>(emptyMap())
    private val inbox = Channel<MessagePacket>(8)
    private data class Incoming(val peer: String, val start: VoiceWire, val file: File,
        val parts: MutableSet<Int> = mutableSetOf(), val began: Long)
    private val receiving = mutableMapOf<String,Incoming>()
    private data class Outgoing(val peer: String, val size: Int, val count: Int, val result: CompletableDeferred<Boolean>, var ending: Boolean = false)
    private val jobs = mutableMapOf<String, Job>()
    fun stop() { jobs.values.toList().forEach { it.cancel() }; diagnostic("Voice stop active=${jobs.size}") }
    private val outgoing = mutableMapOf<String,Outgoing>()
    init {
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        scope.launch {
            while (isActive) {
                val packet = withTimeoutOrNull(1000) { inbox.receive() }
                receiving.values.filter { now() - it.began > 240_000 }.toList().forEach {
                    it.file.delete(); receiving.remove(it.start.transfer)
                }
                if (packet != null) runCatching { receive(packet) }.onFailure { error ->
                    diagnostic("Voice packet=${packet.messageId} rejected: ${error.javaClass.simpleName} at ${error.stackTrace.take(8).joinToString(" <- ")}")
                    val wire = runCatching { VoiceWire.decode(packet.payload) }.getOrNull()
                    if (wire != null && wire.kind in setOf("START", "DATA", "END") && packet.destinationId == local() && trusted(packet.sourceId)) {
                        receiving[wire.transfer]?.takeIf { it.peer == packet.sourceId }?.let { it.file.delete(); receiving.remove(wire.transfer) }
                        scope.launch { runCatching { submit(packet.sourceId, wire.copy(kind="ERROR",metadata=null,index=-1,data="")) } }
                    }
                }
            }
        }
    }
    fun accept(packet: MessagePacket) { check(inbox.trySend(packet).isSuccess) { "Voice receive queue full" } }
    private fun state(id: String, value: String) { diagnostic("Voice $id: $value"); states.value = (states.value + (id to value)).entries.toList().takeLast(64).associate { it.toPair() } }
    suspend fun awaitFinished(id: String) { jobs[id]?.join() }
    fun requireSendCapacity() {
        check(outgoing.isEmpty()) { "A voice message is already sending. Wait for it to finish, then tap Send again." }
    }
    fun send(payload: VoicePayloadService.Payload) {
        val m = payload.metadata
        requireSendCapacity()
        require(m.sourceId == local()) { "Voice sender identity changed. Record again." }
        require(trusted(m.destinationId)) { "Verify this recipient before sending voice." }
        require(payload.encryptedBytes in 1..2_000_000) { "Encrypted recording is empty or too large." }
        val result = CompletableDeferred<Boolean>()
        require(!outgoing.containsKey(m.transferId)) { "This recording is already sending." }
        outgoing[m.transferId] = Outgoing(m.destinationId, payload.encryptedBytes.toInt(), (payload.encryptedBytes.toInt() + VoiceWire.BLOCK - 1) / VoiceWire.BLOCK, result)
        state(m.transferId, "Queued")
        jobs[m.transferId] = scope.launch {
            try {
                withTimeout(240_000) {
                    val size = payload.encryptedBytes.toInt(); val count = (size + VoiceWire.BLOCK - 1) / VoiceWire.BLOCK
                    val start = VoiceWire(kind = "START", transfer = m.transferId, size = size, count = count, metadata = m)
                    submit(m.destinationId, start)
                    check(!result.isCompleted || result.await()) { "Destination rejected START" }
                    RandomAccessFile(payload.encryptedFile, "r").use { file ->
                        repeat(count) { index ->
                            val data = withContext(io) { ByteArray(minOf(VoiceWire.BLOCK, size - index * VoiceWire.BLOCK)).also { file.readFully(it) } }
                            state(m.transferId, "Sending ${index + 1}/$count")
                            submit(m.destinationId, start.copy(kind = "DATA", metadata = null, index = index, data = data.b64()))
                            check(!result.isCompleted || result.await()) { "Destination rejected DATA" }
                            delay(25) // One outstanding packet; yield capacity to text.
                        }
                    }
                    state(m.transferId, "Waiting for destination confirmation")
                    outgoing[m.transferId]?.ending = true
                    submit(m.destinationId, start.copy(kind = "END", metadata = null))
                    check(result.await()) { "Destination verification failed" }
                    state(m.transferId, "Delivered")
                }
            } catch (error: Exception) {
                state(m.transferId, when (error) {
                    is TimeoutCancellationException -> "Failed: transfer deadline exceeded"
                    is CancellationException -> "Failed: transfer canceled"
                    else -> "Failed: transfer rejected or route unavailable"
                })
                diagnostic("Voice ${m.transferId} failure: ${error.javaClass.simpleName} at ${error.stackTrace.take(8).joinToString(" <- ")}")
            }
            finally { outgoing.remove(m.transferId); jobs.remove(m.transferId); diagnostic("Voice ${m.transferId} slot=released") }
        }
    }
    private suspend fun receive(packet: MessagePacket) {
        require(packet.destinationId == local() && packet.group == null && trusted(packet.sourceId))
        val w = VoiceWire.decode(packet.payload)
        if (w.kind == "ACK" || w.kind == "ERROR") {
            outgoing[w.transfer]?.takeIf { it.peer == packet.sourceId && (it.ending || w.kind == "ERROR") && it.size == w.size && it.count == w.count }?.result?.complete(w.kind == "ACK")
            return
        }
        if (w.kind == "START") {
            require(w.metadata!!.sourceId == packet.sourceId && w.metadata.destinationId == local())
            receiving[w.transfer]?.let {
                require(it.peer == packet.sourceId)
                if (it.start == w) return
                require(w.metadata.createdAt > it.start.metadata!!.createdAt) { "Stale retry" }
                it.file.delete(); receiving.remove(w.transfer)
            }
            require(receiving.size < 2 && directory.usableSpace > 4_000_000)
            val file = withContext(io) { File.createTempFile("incoming-", ".bin", directory) }
            receiving[w.transfer] = Incoming(packet.sourceId,w,file, began = now())
            return
        }
        val entry = receiving[w.transfer] ?: return
        require(entry.peer == packet.sourceId && entry.start.size == w.size && entry.start.count == w.count)
        if (w.kind == "DATA") {
            val bytes = w.data.bytes64()
            withContext(io) {
                RandomAccessFile(entry.file,"rw").use {
                    it.seek(w.index.toLong() * VoiceWire.BLOCK)
                    if (w.index in entry.parts) {
                        val old = ByteArray(bytes.size); it.readFully(old); require(old.contentEquals(bytes))
                    } else it.write(bytes)
                }
            }
            entry.parts.add(w.index)
        } else if (w.kind == "END") {
            val success = runCatching {
                require(entry.parts.size == w.count)
                if (!receipt(packet.sourceId,w.transfer)) commit(VoicePayloadService.Payload(entry.start.metadata!!,entry.file), packet)
            }.isSuccess
            entry.file.delete(); receiving.remove(w.transfer)
            scope.launch { runCatching { submit(packet.sourceId,w.copy(kind = if (success) "ACK" else "ERROR")) } }
        }
    }
}
