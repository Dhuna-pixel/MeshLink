package com.meshlink.app.data.transport.framing

import com.meshlink.app.domain.crc.Crc16Ccitt
import com.meshlink.app.domain.models.BleHandshake
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [MeshLinkFrame] and [FrameReassembler].
 *
 * Verifies:
 * 1. Binary frame encoding and decoding roundtrip
 * 2. CRC calculation over header + payload
 * 3. Intentional corruption detection (CrcError)
 * 4. BLE chunked streaming reassembly across various MTUs (20B, 50B, 180B, 512B)
 * 5. Stream recovery after corrupted bytes
 * 6. Multiple consecutive frames in single stream
 */
class MeshLinkFramingTest {

    @Test
    fun test1_routedPacketFrameEncodeDecodeRoundtrip() {
        val packet = MessagePacket(
            messageId = "MSG-100",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Hello over BLE GATT Mesh!",
            ttl = 5,
            hopCount = 0,
            routeHistory = listOf("NODE-A")
        )
        val jsonStr = JsonSerializer.packetToJson(packet)
        val payload = jsonStr.toByteArray(StandardCharsets.UTF_8)

        val frame = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payload
        )

        assertEquals(MeshLinkFrame.PROTOCOL_VERSION, frame[0])
        assertEquals(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, frame[1])
        val length = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(payload.size, length)
        assertEquals(MeshLinkFrame.HEADER_SIZE + payload.size + MeshLinkFrame.CRC_SIZE, frame.size)

        val decodeResult = MeshLinkFrame.decode(frame)
        assertTrue("Frame decode must succeed", decodeResult is MeshLinkFrame.FrameResult.Success)

        val success = decodeResult as MeshLinkFrame.FrameResult.Success
        assertEquals(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, success.frameType)
        assertArrayEquals(payload, success.payload)

        val parsedPacket = JsonSerializer.jsonToPacket(String(success.payload, StandardCharsets.UTF_8))
        assertNotNull(parsedPacket)
        assertEquals("MSG-100", parsedPacket?.messageId)
        assertEquals("NODE-A", parsedPacket?.sourceId)
        assertEquals("NODE-C", parsedPacket?.destinationId)
    }

    @Test
    fun test2_handshakeFrameEncodeDecodeRoundtrip() {
        val handshake = BleHandshake(
            nodeId = "NODE-B-1C73",
            displayName = "Node B",
            isRelayCapable = true
        )
        val jsonStr = BleHandshake.toJson(handshake)
        val payload = jsonStr.toByteArray(StandardCharsets.UTF_8)

        val frame = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_HELLO_HANDSHAKE,
            payload = payload
        )

        val decodeResult = MeshLinkFrame.decode(frame)
        assertTrue(decodeResult is MeshLinkFrame.FrameResult.Success)

        val success = decodeResult as MeshLinkFrame.FrameResult.Success
        assertEquals(MeshLinkFrame.FRAME_TYPE_HELLO_HANDSHAKE, success.frameType)

        val parsedHandshake = BleHandshake.fromJson(String(success.payload, StandardCharsets.UTF_8))
        assertNotNull(parsedHandshake)
        assertEquals("NODE-B-1C73", parsedHandshake?.nodeId)
        assertEquals("Node B", parsedHandshake?.displayName)
    }

    @Test
    fun test3_corruptedFrameIsRejectedWithCrcError() {
        val payload = "Important Financial / Telemetry Data".toByteArray(StandardCharsets.UTF_8)
        val corruptedFrame = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payload,
            corruptForTesting = true // Intentionally corrupts 1 payload byte
        )

        val decodeResult = MeshLinkFrame.decode(corruptedFrame)
        assertTrue("Corrupted frame MUST return CrcError", decodeResult is MeshLinkFrame.FrameResult.CrcError)

        val crcError = decodeResult as MeshLinkFrame.FrameResult.CrcError
        assertEquals(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, crcError.frameType)
        assertEquals(payload.size, crcError.payloadLength)
        assertTrue(crcError.calculatedCrc != crcError.receivedCrc)
    }

    @Test
    fun test4_chunkedStreamReassembly_variousChunkSizes() {
        val packet = MessagePacket(
            messageId = "MSG-CHUNK-TEST",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Payload designed to span multiple BLE GATT ATT chunks with negotiated MTU.",
            ttl = 5,
            hopCount = 0
        )
        val payload = JsonSerializer.packetToJson(packet).toByteArray(StandardCharsets.UTF_8)
        val fullFrame = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, payload)

        val testChunkSizes = listOf(15, 20, 33, 50, 180, 512)

        for (chunkSize in testChunkSizes) {
            val reassembler = FrameReassembler()
            val emittedFrames = mutableListOf<MeshLinkFrame.FrameResult>()

            var offset = 0
            while (offset < fullFrame.size) {
                val length = minOf(chunkSize, fullFrame.size - offset)
                val chunk = ByteArray(length)
                System.arraycopy(fullFrame, offset, chunk, 0, length)

                val results = reassembler.processChunk(chunk)
                emittedFrames.addAll(results)
                offset += length
            }

            assertEquals("Failed for chunk size $chunkSize", 1, emittedFrames.size)
            assertTrue("Emitted frame must be Success", emittedFrames[0] is MeshLinkFrame.FrameResult.Success)

            val success = emittedFrames[0] as MeshLinkFrame.FrameResult.Success
            val decodedPacket = JsonSerializer.jsonToPacket(String(success.payload, StandardCharsets.UTF_8))
            assertEquals("MSG-CHUNK-TEST", decodedPacket?.messageId)
        }
    }

    @Test
    fun test5_multipleConsecutiveFramesInSingleStream() {
        val p1 = MessagePacket(messageId = "MSG-1", sourceId = "NODE-A", destinationId = "NODE-B", payload = "First")
        val p2 = MessagePacket(messageId = "MSG-2", sourceId = "NODE-B", destinationId = "NODE-C", payload = "Second")
        val p3 = MessagePacket(messageId = "MSG-3", sourceId = "NODE-C", destinationId = "NODE-A", payload = "Third")

        val f1 = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, JsonSerializer.packetToJson(p1).toByteArray(StandardCharsets.UTF_8))
        val f2 = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, JsonSerializer.packetToJson(p2).toByteArray(StandardCharsets.UTF_8))
        val f3 = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, JsonSerializer.packetToJson(p3).toByteArray(StandardCharsets.UTF_8))

        val combined = ByteArray(f1.size + f2.size + f3.size)
        System.arraycopy(f1, 0, combined, 0, f1.size)
        System.arraycopy(f2, 0, combined, f1.size, f2.size)
        System.arraycopy(f3, 0, combined, f1.size + f2.size, f3.size)

        val reassembler = FrameReassembler()
        val results = mutableListOf<MeshLinkFrame.FrameResult>()
        var offset = 0
        val chunkSize = 40
        while (offset < combined.size) {
            val length = minOf(chunkSize, combined.size - offset)
            val chunk = ByteArray(length)
            System.arraycopy(combined, offset, chunk, 0, length)
            results.addAll(reassembler.processChunk(chunk))
            offset += length
        }

        assertEquals(3, results.size)
        assertTrue(results.all { it is MeshLinkFrame.FrameResult.Success })

        val packet1 = JsonSerializer.jsonToPacket(String((results[0] as MeshLinkFrame.FrameResult.Success).payload, StandardCharsets.UTF_8))
        val packet2 = JsonSerializer.jsonToPacket(String((results[1] as MeshLinkFrame.FrameResult.Success).payload, StandardCharsets.UTF_8))
        val packet3 = JsonSerializer.jsonToPacket(String((results[2] as MeshLinkFrame.FrameResult.Success).payload, StandardCharsets.UTF_8))

        assertEquals("MSG-1", packet1?.messageId)
        assertEquals("MSG-2", packet2?.messageId)
        assertEquals("MSG-3", packet3?.messageId)
    }

    @Test
    fun test6_garbagePrefixRecovery() {
        val garbage = byteArrayOf(0x00, 0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())
        val validPacket = MessagePacket(messageId = "MSG-VALID", sourceId = "NODE-A", destinationId = "NODE-C", payload = "Valid")
        val validFrame = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, JsonSerializer.packetToJson(validPacket).toByteArray(StandardCharsets.UTF_8))

        val combined = ByteArray(garbage.size + validFrame.size)
        System.arraycopy(garbage, 0, combined, 0, garbage.size)
        System.arraycopy(validFrame, 0, combined, garbage.size, validFrame.size)

        val reassembler = FrameReassembler()
        val results = reassembler.processChunk(combined)

        assertEquals(1, results.size)
        assertTrue(results[0] is MeshLinkFrame.FrameResult.Success)
        val decoded = JsonSerializer.jsonToPacket(String((results[0] as MeshLinkFrame.FrameResult.Success).payload, StandardCharsets.UTF_8))
        assertEquals("MSG-VALID", decoded?.messageId)
    }

    /**
     * Deterministic test verifying the CRC injection workflow:
     * 1. Normal mode: Legitimate frame -> CRC calculated -> Verified as PASS.
     * 2. Injection mode: Legitimate CRC calculated first -> Transmitted byte altered -> Verified as FAIL.
     */
    @Test
    fun test7_crcInjectionWorkflow_corruptedFrameFailsVerificationAndIsRejected() {
        val packet = MessagePacket(
            messageId = "MSG-CRC-INJECT-01",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "Sensitive Transaction Data"
        )
        val payloadBytes = JsonSerializer.packetToJson(packet).toByteArray(StandardCharsets.UTF_8)

        // 1. Normal Frame
        val normalFrame = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = false
        )
        val normalResult = MeshLinkFrame.decode(normalFrame)
        assertTrue("Normal frame must pass CRC", normalResult is MeshLinkFrame.FrameResult.Success)

        // 2. Injected / Corrupted Frame
        val corruptedFrame = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = true
        )
        val corruptedResult = MeshLinkFrame.decode(corruptedFrame)
        assertTrue("Injected frame must fail CRC", corruptedResult is MeshLinkFrame.FrameResult.CrcError)

        val crcError = corruptedResult as MeshLinkFrame.FrameResult.CrcError
        assertTrue(
            "Calculated CRC (${crcError.calculatedCrc}) must not equal received CRC (${crcError.receivedCrc})",
            crcError.calculatedCrc != crcError.receivedCrc
        )
    }
}
