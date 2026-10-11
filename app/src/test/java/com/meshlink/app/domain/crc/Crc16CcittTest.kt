package com.meshlink.app.domain.crc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [Crc16Ccitt].
 *
 * Verifies standard test vectors, bitwise vs lookup consistency,
 * single/multi-bit error detection, and boundary conditions.
 */
class Crc16CcittTest {

    /**
     * TEST 1: Standard CCITT-FALSE Test Vector.
     * Input: "123456789" (ASCII bytes: 0x31..0x39)
     * Expected: 0x29B1 (10673)
     */
    @Test
    fun test1_standardTestVector_matchesExpected() {
        val input = "123456789".toByteArray(StandardCharsets.US_ASCII)
        val crc = Crc16Ccitt.compute(input)
        assertEquals(0x29B1, crc)
        assertEquals("0x29B1", Crc16Ccitt.formatHex(crc))
        assertTrue(Crc16Ccitt.verify(input, 0, input.size, 0x29B1))
    }

    /**
     * TEST 2: Determinism.
     * Same input repeatedly evaluated always yields the exact same CRC.
     */
    @Test
    fun test2_determinism_sameInputProducesSameCrc() {
        val input = "MeshLink Decentralized BLE GATT Routing".toByteArray(StandardCharsets.UTF_8)
        val crc1 = Crc16Ccitt.compute(input)
        val crc2 = Crc16Ccitt.compute(input)
        val crc3 = Crc16Ccitt.compute(input)

        assertEquals(crc1, crc2)
        assertEquals(crc2, crc3)
    }

    /**
     * TEST 3: Table lookup implementation matches shift-register bitwise implementation 100%.
     */
    @Test
    fun test3_tableMatchesBitwiseShiftRegister() {
        val testStrings = listOf(
            "",
            "A",
            "123456789",
            "Node A -> Node B -> Node C",
            "Special characters: !@#$%^&*()_+{}|:\"<>?~`-=[]\\;',./",
            "Unicode test: 📡 🛰️ 📱 🔗 🛡️",
            "A".repeat(1024)
        )

        for (str in testStrings) {
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            val tableCrc = Crc16Ccitt.compute(bytes)
            val bitwiseCrc = Crc16Ccitt.computeBitwise(bytes)
            assertEquals("Mismatch for string: '$str'", bitwiseCrc, tableCrc)
        }
    }

    /**
     * TEST 4: Single-byte corruption detection.
     * Flipping any single byte in the payload MUST change the CRC.
     */
    @Test
    fun test4_singleByteModification_changesCrc() {
        val original = "Sensitive MeshLink Payload Data".toByteArray(StandardCharsets.UTF_8)
        val originalCrc = Crc16Ccitt.compute(original)

        for (i in original.indices) {
            val corrupted = original.clone()
            corrupted[i] = (corrupted[i].toInt() xor 0x01).toByte() // Flip 1 bit

            val corruptedCrc = Crc16Ccitt.compute(corrupted)
            assertTrue("CRC failed to detect 1-bit corruption at index $i", originalCrc != corruptedCrc)
            assertFalse(Crc16Ccitt.verify(corrupted, 0, corrupted.size, originalCrc))
        }
    }

    /**
     * TEST 5: Multi-byte corruption detection.
     */
    @Test
    fun test5_multiByteModification_changesCrc() {
        val original = "MeshLink Message ID: MSG-99887766".toByteArray(StandardCharsets.UTF_8)
        val originalCrc = Crc16Ccitt.compute(original)

        val corrupted = original.clone()
        corrupted[0] = 'X'.code.toByte()
        corrupted[1] = 'Y'.code.toByte()
        corrupted[2] = 'Z'.code.toByte()

        val corruptedCrc = Crc16Ccitt.compute(corrupted)
        assertTrue(originalCrc != corruptedCrc)
        assertFalse(Crc16Ccitt.verify(corrupted, 0, corrupted.size, originalCrc))
    }

    /**
     * TEST 6: Empty payload.
     */
    @Test
    fun test6_emptyPayload_computesWithoutCrashing() {
        val empty = byteArrayOf()
        val crc = Crc16Ccitt.compute(empty)
        assertEquals(0xFFFF, crc)
        assertTrue(Crc16Ccitt.verify(empty, 0, 0, 0xFFFF))
    }

    /**
     * TEST 7: Large payload (4 KB).
     */
    @Test
    fun test7_largePayload_computesCorrectly() {
        val large = ByteArray(4096) { it.toByte() }
        val crc = Crc16Ccitt.compute(large)
        assertTrue(crc in 0x0000..0xFFFF)
        assertTrue(Crc16Ccitt.verify(large, 0, large.size, crc))
    }
}
