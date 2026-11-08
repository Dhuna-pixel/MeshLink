package com.meshlink.app.domain.transport

import com.meshlink.app.data.transport.framing.encode

import com.meshlink.app.data.transport.framing.FrameReassembler
import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.domain.crc.Crc16Ccitt
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests verifying Review III binary framing, chunk fragmentation, CRC validation,
 * and corruption rejection over BLE GATT transport.
 */
class BleFramingTest {

    @Test
    fun testSinglePacketBinaryFramingAndReassembly() {
        val packet = MessagePacket(
            messageId = "MSG-TEST-001",
            sourceId = "NODE-A",
            sourceName = "Pixel 7",
            destinationId = "NODE-C",
            destinationName = "Node C",
            payload = "Hello over BLE GATT Mesh with CRC-16!",
            ttl = 5,
            hopCount = 0,
            timestamp = 1700000000000L,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        val framedBytes = MeshLinkFrame.encode(MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET, payloadBytes)

        // Simulate chunked arrival (50 bytes per BLE write)
        val chunkSize = 50
        val reassembler = FrameReassembler()
        val reassembledPackets = mutableListOf<MessagePacket>()

        var offset = 0
        while (offset < framedBytes.size) {
            val length = minOf(chunkSize, framedBytes.size - offset)
            val chunk = ByteArray(length)
            System.arraycopy(framedBytes, offset, chunk, 0, length)

            val results = reassembler.processChunk(chunk)
            for (res in results) {
                if (res is MeshLinkFrame.FrameResult.Success) {
                    val decoded = JsonSerializer.jsonToPacket(String(res.payload, StandardCharsets.UTF_8))
                    if (decoded != null) {
                        reassembledPackets.add(decoded)
                    }
                }
            }
            offset += length
        }

        assertEquals(1, reassembledPackets.size)
        val result = reassembledPackets[0]
        assertEquals("MSG-TEST-001", result.messageId)
        assertEquals("NODE-A", result.sourceId)
        assertEquals("NODE-C", result.destinationId)
        assertEquals("Hello over BLE GATT Mesh with CRC-16!", result.payload)
        assertEquals(5, result.ttl)
        assertEquals(0, result.hopCount)
    }

    @Test
    fun testCorruptedFrameIsRejectedBeforeRouting() {
        val packet = MessagePacket(
            messageId = "MSG-CORRUPT-TEST",
            sourceId = "NODE-A",
            destinationId = "NODE-C",
            payload = "This payload will be corrupted in transmission"
        )

        val jsonStr = JsonSerializer.packetToJson(packet)
        val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        // Corrupt frame intentionally
        val corruptedFrame = MeshLinkFrame.encode(
            frameType = MeshLinkFrame.FRAME_TYPE_ROUTED_PACKET,
            payload = payloadBytes,
            corruptForTesting = true
        )

        val reassembler = FrameReassembler()
        val results = reassembler.processChunk(corruptedFrame)

        assertEquals(1, results.size)
        assertTrue("Corrupted frame must return CrcError", results[0] is MeshLinkFrame.FrameResult.CrcError)

        val crcError = results[0] as MeshLinkFrame.FrameResult.CrcError
        assertTrue(crcError.calculatedCrc != crcError.receivedCrc)
    }
}
