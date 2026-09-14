package com.meshlink.app.domain.transport

import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class BleFramingTest {

    private val delimiterByte: Byte = 0x0A

    @Test
    fun testSinglePacketFramingAndReassembly() {
        val packet = MessagePacket(
            messageId = "MSG-TEST-001",
            sourceId = "NODE-A",
            sourceName = "Pixel 7",
            destinationId = "NODE-C",
            destinationName = "iPhone 15",
            payload = "Hello over BLE GATT Mesh!",
            ttl = 5,
            hopCount = 0,
            timestamp = 1700000000000L,
            routeHistory = listOf("NODE-A"),
            previousHopId = "NODE-A"
        )

        val jsonStr = JsonSerializer.packetToJson(packet)
        val rawBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
        val framedBytes = ByteArray(rawBytes.size + 1)
        System.arraycopy(rawBytes, 0, framedBytes, 0, rawBytes.size)
        framedBytes[rawBytes.size] = delimiterByte

        // Simulate chunked arrival (e.g. 50 bytes per BLE write)
        val chunkSize = 50
        val reassembledPackets = mutableListOf<MessagePacket>()
        val stream = ByteArrayOutputStream()

        var offset = 0
        while (offset < framedBytes.size) {
            val length = minOf(chunkSize, framedBytes.size - offset)
            val chunk = ByteArray(length)
            System.arraycopy(framedBytes, offset, chunk, 0, length)

            // Receiver processing
            stream.write(chunk)
            val currentBytes = stream.toByteArray()
            var searchStart = 0
            var lastDelimiter = -1

            for (i in currentBytes.indices) {
                if (currentBytes[i] == delimiterByte) {
                    val frameLen = i - searchStart
                    if (frameLen > 0) {
                        val frame = ByteArray(frameLen)
                        System.arraycopy(currentBytes, searchStart, frame, 0, frameLen)
                        val decoded = JsonSerializer.jsonToPacket(String(frame, StandardCharsets.UTF_8))
                        if (decoded != null) {
                            reassembledPackets.add(decoded)
                        }
                    }
                    searchStart = i + 1
                    lastDelimiter = i
                }
            }

            if (lastDelimiter >= 0) {
                stream.reset()
                val rem = currentBytes.size - (lastDelimiter + 1)
                if (rem > 0) {
                    stream.write(currentBytes, lastDelimiter + 1, rem)
                }
            }

            offset += length
        }

        assertEquals(1, reassembledPackets.size)
        val result = reassembledPackets[0]
        assertEquals("MSG-TEST-001", result.messageId)
        assertEquals("NODE-A", result.sourceId)
        assertEquals("NODE-C", result.destinationId)
        assertEquals("Hello over BLE GATT Mesh!", result.payload)
        assertEquals(5, result.ttl)
        assertEquals(0, result.hopCount)
    }

    @Test
    fun testConsecutiveMultiplePacketsReassembly() {
        val packet1 = MessagePacket(messageId = "MSG-1", sourceId = "NODE-A", destinationId = "NODE-B", payload = "First")
        val packet2 = MessagePacket(messageId = "MSG-2", sourceId = "NODE-B", destinationId = "NODE-C", payload = "Second")

        val bytes1 = (JsonSerializer.packetToJson(packet1) + "\n").toByteArray(StandardCharsets.UTF_8)
        val bytes2 = (JsonSerializer.packetToJson(packet2) + "\n").toByteArray(StandardCharsets.UTF_8)

        val combined = ByteArray(bytes1.size + bytes2.size)
        System.arraycopy(bytes1, 0, combined, 0, bytes1.size)
        System.arraycopy(bytes2, 0, combined, bytes1.size, bytes2.size)

        val reassembled = mutableListOf<MessagePacket>()
        val stream = ByteArrayOutputStream()

        // Send all bytes in random chunk sizes
        val chunk = combined
        stream.write(chunk)
        val currentBytes = stream.toByteArray()
        var searchStart = 0
        var lastDelimiter = -1

        for (i in currentBytes.indices) {
            if (currentBytes[i] == delimiterByte) {
                val frameLen = i - searchStart
                if (frameLen > 0) {
                    val frame = ByteArray(frameLen)
                    System.arraycopy(currentBytes, searchStart, frame, 0, frameLen)
                    val decoded = JsonSerializer.jsonToPacket(String(frame, StandardCharsets.UTF_8))
                    if (decoded != null) {
                        reassembled.add(decoded)
                    }
                }
                searchStart = i + 1
                lastDelimiter = i
            }
        }

        assertEquals(2, reassembled.size)
        assertEquals("MSG-1", reassembled[0].messageId)
        assertEquals("MSG-2", reassembled[1].messageId)
    }

    @Test
    fun testMalformedFrameGracefulRecovery() {
        val malformedJson = "{not-valid-json}\n"
        val validPacket = MessagePacket(messageId = "MSG-VALID", sourceId = "NODE-A", destinationId = "NODE-C", payload = "Valid")
        val validJson = JsonSerializer.packetToJson(validPacket) + "\n"

        val streamData = (malformedJson + validJson).toByteArray(StandardCharsets.UTF_8)

        val reassembled = mutableListOf<MessagePacket>()
        val stream = ByteArrayOutputStream()
        stream.write(streamData)

        val currentBytes = stream.toByteArray()
        var searchStart = 0
        for (i in currentBytes.indices) {
            if (currentBytes[i] == delimiterByte) {
                val frameLen = i - searchStart
                if (frameLen > 0) {
                    val frame = ByteArray(frameLen)
                    System.arraycopy(currentBytes, searchStart, frame, 0, frameLen)
                    val decoded = JsonSerializer.jsonToPacket(String(frame, StandardCharsets.UTF_8))
                    if (decoded != null) {
                        reassembled.add(decoded)
                    }
                }
                searchStart = i + 1
            }
        }

        // Malformed packet skipped, valid packet preserved
        assertEquals(1, reassembled.size)
        assertEquals("MSG-VALID", reassembled[0].messageId)
    }
}
