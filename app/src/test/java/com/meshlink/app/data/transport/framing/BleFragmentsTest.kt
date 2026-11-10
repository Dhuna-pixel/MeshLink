package com.meshlink.app.data.transport.framing
import org.junit.Assert.*
import org.junit.Test

class BleFragmentsTest {
    private val frame = MeshLinkFrame.encode(1, ByteArray(1000) { it.toByte() })
    @Test fun mtu23OutOfOrderAndDuplicates() {
        val fragments=BleFragments.split(frame,20,42); val receiver=FragmentAssembler()
        assertNull(receiver.accept(fragments.last())); assertNull(receiver.accept(fragments.last()))
        var result: ByteArray?=null
        fragments.dropLast(1).reversed().forEach { result=receiver.accept(it) ?: result }
        assertArrayEquals(frame,result)
    }
    @Test fun missingAndTimeout() {
        var clock=0L; val receiver=FragmentAssembler { clock }; val fragments=BleFragments.split(frame,100,1)
        fragments.dropLast(1).forEach { assertNull(receiver.accept(it)) }
        clock=10001
        assertThrows(IllegalStateException::class.java) { receiver.accept(fragments.last()) }
        // Reconnect creates a new assembler and a fresh TLS stream.
        val fresh=FragmentAssembler { clock }; var restored: ByteArray?=null
        fragments.forEach { restored=fresh.accept(it) ?: restored }
        assertArrayEquals(frame,restored)
    }
    @Test(expected=IllegalArgumentException::class) fun badIndex() {
        val part=BleFragments.split(frame,100,1).first(); part[5]=127; FragmentAssembler().accept(part)
    }
    @Test(expected=IllegalArgumentException::class) fun oversized() { BleFragments.split(ByteArray(5000),20,1) }
    @Test(expected=IllegalArgumentException::class) fun conflictingDuplicate() {
        val part=BleFragments.split(frame,100,1).first(); val r=FragmentAssembler();r.accept(part);part[11]=(part[11].toInt() xor 1).toByte();r.accept(part)
    }
    @Test fun strictFrameValidation() {
        assertTrue(MeshLinkFrame.decode(frame + byteArrayOf(0)) is MeshLinkFrame.FrameResult.CorruptedHeader)
        val unknown=MeshLinkFrame.encode(99,byteArrayOf(1));assertTrue(MeshLinkFrame.decode(unknown) is MeshLinkFrame.FrameResult.CorruptedHeader)
        val bad=frame.copyOf();bad[3]=0;assertFalse(MeshLinkFrame.decode(bad) is MeshLinkFrame.FrameResult.Success)
    }

    @Test fun eightWritesCanProgressBeyondTenSecondsWithoutExpiry() {
        var clock=0L
        val bytes=MeshLinkFrame.encode(1,ByteArray(650))
        val parts=BleFragments.split(bytes,100,7)
        assertEquals(8,parts.size)
        val receiver=FragmentAssembler { clock }
        var result: ByteArray?=null
        parts.forEach { part -> result=receiver.accept(part) ?: result; clock+=2000 }
        assertArrayEquals(bytes,result)
        assertEquals(0,receiver.expire())
    }
    @Test fun duplicatesCannotKeepBrokenFrameAlive() {
        var clock=0L; val receiver=FragmentAssembler { clock }
        val first=BleFragments.split(frame,100,1).first()
        receiver.accept(first)
        clock=9000; receiver.accept(first)
        clock=10001
        assertEquals(1,receiver.expire())
    }
}
