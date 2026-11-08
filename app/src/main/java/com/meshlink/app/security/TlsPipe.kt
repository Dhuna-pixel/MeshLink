package com.meshlink.app.security

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus.*
import javax.net.ssl.SSLEngineResult.Status.*

/** Serialized by the GATT session worker. Handles arbitrary segmentation of a TLS byte stream. */
class TlsPipe(private val engine: SSLEngine, private val wire: suspend (ByteArray) -> Unit,
    private val authenticated: () -> Unit, private val received: (ByteArray) -> Unit) {
    private val input = ByteBuffer.allocate(40_000)
    private val output = ByteBuffer.allocate(32_768)
    private val application = ByteBuffer.allocate(65_536)
    private val empty = ByteBuffer.allocate(0)
    private var established = false

    suspend fun begin() { engine.beginHandshake(); pump() }
    suspend fun receive(bytes: ByteArray) { input.put(bytes); pump() }
    suspend fun send(bytes: ByteArray) {
        require(established && bytes.size in 1..32_000)
        val source = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes)
        source.flip()
        while (source.hasRemaining()) wrap(source)
    }
    private suspend fun wrap(source: ByteBuffer) {
        output.clear()
        val result = engine.wrap(source, output)
        check(result.status == OK) { "TLS wrap closed or exceeded its record buffer" }
        if (output.position() > 0) wire(output.array().copyOf(output.position()))
        else check(!source.hasRemaining() || result.bytesConsumed() > 0) { "TLS write stalled" }
    }
    private suspend fun pump() {
        repeat(128) {
            when (engine.handshakeStatus) {
                NEED_TASK -> { val task = engine.delegatedTask ?: error("TLS task unavailable"); task.run() }
                NEED_WRAP -> wrap(empty)
                NOT_HANDSHAKING, FINISHED, NEED_UNWRAP -> {
                    if (engine.handshakeStatus == NOT_HANDSHAKING && !established) {
                        established = true; authenticated()
                    }
                    if (input.position() == 0) return
                    input.flip()
                    val result = engine.unwrap(input, application)
                    input.compact()
                    if (result.status == BUFFER_UNDERFLOW) return
                    check(result.status == OK)
                    application.flip()
                    while (application.remaining() >= 4) {
                        application.mark()
                        val size = application.int
                        require(size in 1..32_000)
                        if (application.remaining() < size) { application.reset(); break }
                        check(established)
                        received(ByteArray(size).also(application::get))
                    }
                    application.compact()
                }
                else -> error("Unsupported TLS state")
            }
        }
        error("TLS progress limit")
    }
}
