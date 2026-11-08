package com.meshlink.app.data.transport.framing

import com.meshlink.app.domain.crc.Crc16Ccitt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic binary application framing for MeshLink BLE GATT communication.
 *
 * Frame Wire Format (Big-Endian):
 * +----------------+--------------------+----------------------+--------------------------+--------------------+
 * | VERSION (1B)   | FRAME_TYPE (1B)    | PAYLOAD_LENGTH (2B)  | PAYLOAD (N Bytes)        | CRC-16-CCITT (2B)  |
 * +----------------+--------------------+----------------------+--------------------------+--------------------+
 * | 0x01           | 0x01 (Packet)      | 0x00 0x8A            | UTF-8 JSON Bytes...      | 0x29 0xB1          |
 * |                | 0x02 (Handshake)   |                      |                          |                    |
 * |                | 0x03 (Heartbeat)   |                      |                          |                    |
 * +----------------+--------------------+----------------------+--------------------------+--------------------+
 *
 * Header Size: 4 bytes (Version + Type + Length)
 * Trailing CRC: 2 bytes (CRC-16-CCITT over Header + Payload)
 * Total Frame Overhead: 6 bytes
 *
 * CRC Verification Rule:
 * CRC is calculated over the complete logical frame (Version + Type + Length + Payload),
 * excluding the 2-byte CRC field itself.
 */
object MeshLinkFrame {

    const val PROTOCOL_VERSION: Byte = 0x01

    const val FRAME_TYPE_ROUTED_PACKET: Byte = 0x01
    const val FRAME_TYPE_HELLO_HANDSHAKE: Byte = 0x02
    const val FRAME_TYPE_HEARTBEAT: Byte = 0x03

    const val FRAME_TYPE_TLS: Byte = 0x04

    const val HEADER_SIZE = 4
    const val CRC_SIZE = 2
    const val MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE // 6 bytes (0-length payload)
    const val MAX_PAYLOAD_SIZE = 4096 // 4 KB safe limit for BLE frames

    /**
     * Encodes a payload into a complete MeshLink binary frame with appended CRC-16-CCITT.
     *
     * @param frameType Frame category (e.g. [FRAME_TYPE_ROUTED_PACKET] or [FRAME_TYPE_HELLO_HANDSHAKE])
     * @param payload Raw payload bytes (e.g. serialized JSON)
     * @return Full wire-ready byte array containing [Header + Payload + CRC16]
     */
    fun encode(
        frameType: Byte,
        payload: ByteArray,
    ): ByteArray {
        val payloadLength = payload.size
        if (payloadLength > MAX_PAYLOAD_SIZE) {
            throw IllegalArgumentException("Payload size ($payloadLength) exceeds max allowable ($MAX_PAYLOAD_SIZE)")
        }

        val totalSize = HEADER_SIZE + payloadLength + CRC_SIZE
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // 1. Header (4 bytes)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(frameType)
        buffer.putShort(payloadLength.toShort())

        // 2. Payload (N bytes)
        buffer.put(payload)

        // 3. Compute CRC over Header + Payload (first HEADER_SIZE + payloadLength bytes)
        val dataToCrc = buffer.array()
        val calculatedCrc = Crc16Ccitt.compute(dataToCrc, offset = 0, length = HEADER_SIZE + payloadLength)

        // 4. Append 2-byte CRC (Big Endian)
        buffer.putShort(calculatedCrc.toShort())

        return buffer.array()
    }

    /**
     * Result returned when parsing a frame.
     */
    sealed class FrameResult {
        data class Success(
            val frameType: Byte,
            val payload: ByteArray,
            val crc: Int
        ) : FrameResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Success
                if (frameType != other.frameType) return false
                if (!payload.contentEquals(other.payload)) return false
                return crc == other.crc
            }

            override fun hashCode(): Int {
                var result = frameType.toInt()
                result = 31 * result + payload.contentHashCode()
                result = 31 * result + crc
                return result
            }
        }

        data class CrcError(
            val frameType: Byte,
            val payloadLength: Int,
            val receivedCrc: Int,
            val calculatedCrc: Int
        ) : FrameResult()

        data class CorruptedHeader(
            val reason: String
        ) : FrameResult()
    }

    /**
     * Decodes a single standalone frame byte array.
     */
    fun decode(frameBytes: ByteArray): FrameResult {
        if (frameBytes.size < MIN_FRAME_SIZE) {
            return FrameResult.CorruptedHeader("Frame size ${frameBytes.size} is less than minimum $MIN_FRAME_SIZE bytes")
        }

        val version = frameBytes[0]
        if (version != PROTOCOL_VERSION) {
            return FrameResult.CorruptedHeader("Unsupported protocol version: $version (expected $PROTOCOL_VERSION)")
        }

        val frameType = frameBytes[1]
        if (frameType !in FRAME_TYPE_ROUTED_PACKET..FRAME_TYPE_TLS) return FrameResult.CorruptedHeader("Unsupported frame type")
        val payloadLength = ((frameBytes[2].toInt() and 0xFF) shl 8) or (frameBytes[3].toInt() and 0xFF)

        if (payloadLength > MAX_PAYLOAD_SIZE) {
            return FrameResult.CorruptedHeader("Frame payload length $payloadLength exceeds limit $MAX_PAYLOAD_SIZE")
        }

        val expectedTotalSize = HEADER_SIZE + payloadLength + CRC_SIZE
        if (frameBytes.size != expectedTotalSize) {
            return FrameResult.CorruptedHeader("Truncated frame: expected $expectedTotalSize bytes, got ${frameBytes.size}")
        }

        // Extract received CRC (last 2 bytes)
        val crcOffset = HEADER_SIZE + payloadLength
        val receivedCrc = ((frameBytes[crcOffset].toInt() and 0xFF) shl 8) or (frameBytes[crcOffset + 1].toInt() and 0xFF)

        // Calculate CRC over Header + Payload
        val calculatedCrc = Crc16Ccitt.compute(frameBytes, offset = 0, length = HEADER_SIZE + payloadLength)

        if (calculatedCrc != receivedCrc) {
            return FrameResult.CrcError(
                frameType = frameType,
                payloadLength = payloadLength,
                receivedCrc = receivedCrc,
                calculatedCrc = calculatedCrc
            )
        }

        val payload = ByteArray(payloadLength)
        System.arraycopy(frameBytes, HEADER_SIZE, payload, 0, payloadLength)

        return FrameResult.Success(
            frameType = frameType,
            payload = payload,
            crc = calculatedCrc
        )
    }
}

/**
 * Thread-safe stream reassembler that buffers incoming BLE chunks,
 * detects frame boundaries, validates CRC, and emits complete valid frames.
 */
class FrameReassembler {

    private val stream = ByteArrayOutputStream()

    /**
     * Appends newly received BLE chunk bytes and extracts all complete frames.
     *
     * @param chunk Incoming bytes from BLE GATT write/notify
     * @return List of [MeshLinkFrame.FrameResult] decoded from the stream
     */
    @Synchronized
    fun processChunk(chunk: ByteArray): List<MeshLinkFrame.FrameResult> {
        stream.write(chunk)
        val results = mutableListOf<MeshLinkFrame.FrameResult>()

        var currentBytes = stream.toByteArray()
        var readIndex = 0

        while (readIndex + MeshLinkFrame.MIN_FRAME_SIZE <= currentBytes.size) {
            // Find protocol version byte
            if (currentBytes[readIndex] != MeshLinkFrame.PROTOCOL_VERSION) {
                readIndex++
                continue
            }

            // Check if we have at least full header (4 bytes)
            if (readIndex + MeshLinkFrame.HEADER_SIZE > currentBytes.size) {
                break
            }

            val frameType = currentBytes[readIndex + 1]
            if (frameType !in MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET..MeshLinkFrame.FRAME_TYPE_TLS) {
                // Invalid frame type, skip this byte and resync
                readIndex++
                continue
            }

            val payloadLength = ((currentBytes[readIndex + 2].toInt() and 0xFF) shl 8) or
                    (currentBytes[readIndex + 3].toInt() and 0xFF)

            if (payloadLength > MeshLinkFrame.MAX_PAYLOAD_SIZE) {
                // Invalid length, skip and resync
                readIndex++
                continue
            }

            val totalFrameSize = MeshLinkFrame.HEADER_SIZE + payloadLength + MeshLinkFrame.CRC_SIZE
            if (readIndex + totalFrameSize > currentBytes.size) {
                // Full frame hasn't arrived yet; wait for remaining BLE chunks
                break
            }

            // Extract candidate frame bytes
            val frameBytes = ByteArray(totalFrameSize)
            System.arraycopy(currentBytes, readIndex, frameBytes, 0, totalFrameSize)

            val decodeResult = MeshLinkFrame.decode(frameBytes)
            results.add(decodeResult)

            readIndex += totalFrameSize
        }

        // Discard consumed bytes from buffer
        stream.reset()
        if (readIndex < currentBytes.size) {
            val remaining = currentBytes.size - readIndex
            stream.write(currentBytes, readIndex, remaining)
        }

        return results
    }

    /**
     * Clears internal stream buffer.
     */
    @Synchronized
    fun reset() {
        stream.reset()
    }
}
