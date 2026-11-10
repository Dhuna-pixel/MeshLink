package com.meshlink.app.data.transport.framing

import java.nio.ByteBuffer

/** Versioned datagrams: magic, transfer ID, index, count, total length (11 bytes). */
object BleFragments {
    const val HEADER = 11
    const val MAX_FRAME = MeshLinkFrame.MAX_PAYLOAD_SIZE + 6
    fun split(frame: ByteArray, payloadSize: Int, id: Int): List<ByteArray> {
        require(frame.size in 6..MAX_FRAME && payloadSize in 20..512)
        val capacity = payloadSize - HEADER
        val count = (frame.size + capacity - 1) / capacity
        return (0 until count).map { index ->
            val part = frame.copyOfRange(index * capacity, minOf(frame.size, (index + 1) * capacity))
            ByteBuffer.allocate(HEADER + part.size).put(0x4d).putInt(id)
                .putShort(index.toShort()).putShort(count.toShort()).putShort(frame.size.toShort()).put(part).array()
        }
    }
}

class FragmentAssembler(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Transfer(val count: Int, val total: Int, val started: Long, var progressed: Long = started, val parts: MutableMap<Int, ByteArray> = mutableMapOf())
    private val transfers = linkedMapOf<Int, Transfer>()
    @Synchronized fun expire(): Int {
        val before=transfers.size
        transfers.entries.removeAll { now() - it.value.progressed >= 10_000 || now() - it.value.started >= 60_000 }
        return before-transfers.size
    }
    @Synchronized fun accept(bytes: ByteArray): ByteArray? {
        check(expire() == 0) { "Fragment expired: TLS stream lost bytes" }
        require(bytes.size in (BleFragments.HEADER + 1)..512) { "Invalid fragment size" }
        val b = ByteBuffer.wrap(bytes)
        require(b.get() == 0x4d.toByte()) { "Unsupported fragment format" }
        val id = b.int
        val index = b.short.toInt() and 65535
        val count = b.short.toInt() and 65535
        val total = b.short.toInt() and 65535
        require(total in 6..BleFragments.MAX_FRAME && count in 1..456 && index < count && count <= total)
        val transfer = transfers[id] ?: run {
            require(transfers.size < 4) { "Too many incomplete transfers" }
            Transfer(count, total, now()).also { transfers[id] = it }
        }
        require(transfer.count == count && transfer.total == total)
        val part = bytes.copyOfRange(BleFragments.HEADER, bytes.size)
        val old = transfer.parts[index]
        require(old == null || old.contentEquals(part)) { "Conflicting duplicate fragment" }
        if (old == null) transfer.progressed = now()
        transfer.parts[index] = part
        if (transfer.parts.values.sumOf { it.size } > total) {
            transfers.remove(id)
            error("Oversized transfer")
        }
        if (transfer.parts.size != count) return null
        transfers.remove(id)
        val frame = (0 until count).fold(byteArrayOf()) { acc, i -> acc + transfer.parts.getValue(i) }
        require(frame.size == total) { "Incomplete transfer" }
        return frame
    }
}
