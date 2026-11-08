package com.meshlink.app.data.transport.framing

fun MeshLinkFrame.encode(frameType: Byte, payload: ByteArray, corruptForTesting: Boolean): ByteArray =
    encode(frameType, payload).also { frame ->
        if (corruptForTesting) {
            val index = if (payload.isEmpty()) frame.lastIndex else HEADER_SIZE
            frame[index] = (frame[index].toInt() xor 0xff).toByte()
        }
    }
