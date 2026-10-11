package com.meshlink.app.domain.crc

/**
 * Standard CRC-16-CCITT (CRC-16/CCITT-FALSE) implementation for MeshLink frame integrity verification.
 *
 * Mathematical / Algorithmic Specification:
 * - Polynomial:     0x1021 (x^16 + x^12 + x^5 + 1)
 * - Initial Value:  0xFFFF (65535)
 * - Reflect Input:  false (MSB-first processing)
 * - Reflect Output: false
 * - XOR Output:     0x0000
 * - Check Vector:   "123456789" (ASCII) -> 0x29B1 (10673)
 *
 * Purpose in MeshLink Review III:
 * Detects accidental transmission corruption over BLE GATT characteristics.
 * Note: CRC is purely an error-detection code, NOT cryptographic encryption or authentication.
 */
object Crc16Ccitt {

    private const val POLYNOMIAL = 0x1021
    private const val INITIAL_VALUE = 0xFFFF

    // Precomputed 256-entry lookup table for high-performance processing on Android devices
    private val TABLE = IntArray(256) { i ->
        var temp = i shl 8
        for (bit in 0 until 8) {
            temp = if ((temp and 0x8000) != 0) {
                ((temp shl 1) xor POLYNOMIAL) and 0xFFFF
            } else {
                (temp shl 1) and 0xFFFF
            }
        }
        temp
    }

    /**
     * Computes the 16-bit CRC-CCITT over [data] from [offset] for [length] bytes.
     *
     * @param data Byte array containing the input data
     * @param offset Starting byte offset (default 0)
     * @param length Number of bytes to process (default data.size)
     * @return 16-bit integer CRC value (in range 0x0000..0xFFFF)
     */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        if (offset < 0 || length < 0 || offset + length > data.size) {
            throw IllegalArgumentException("Invalid offset ($offset) or length ($length) for data size ${data.size}")
        }

        var crc = INITIAL_VALUE
        for (i in offset until (offset + length)) {
            val byteVal = data[i].toInt() and 0xFF
            val tableIndex = ((crc ushr 8) xor byteVal) and 0xFF
            crc = ((crc shl 8) xor TABLE[tableIndex]) and 0xFFFF
        }
        return crc
    }

    /**
     * Computes CRC using direct bitwise shift-register logic (useful for academic reference & verification).
     */
    fun computeBitwise(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = INITIAL_VALUE
        for (i in offset until (offset + length)) {
            val b = data[i].toInt() and 0xFF
            crc = crc xor (b shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor POLYNOMIAL) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Verifies whether [data] matches the [expectedCrc].
     *
     * @return true if calculated CRC matches expected, false otherwise.
     */
    fun verify(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, expectedCrc: Int): Boolean {
        val calculated = compute(data, offset, length)
        return (calculated and 0xFFFF) == (expectedCrc and 0xFFFF)
    }

    /**
     * Formats a 16-bit CRC integer into standard hexadecimal string (e.g. "0x29B1").
     */
    fun formatHex(crc: Int): String {
        return String.format("0x%04X", crc and 0xFFFF)
    }
}
