package com.meshlink.app.domain.voice

import com.meshlink.app.security.*
import com.meshlink.app.domain.models.MessagePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTransferTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun strictWireBounds() {
        val w = VoiceWire(kind="DATA",transfer="id",index=0,size=3,count=1,data=byteArrayOf(1,2,3).b64())
        assertEquals(w,VoiceWire.decode(w.encode()))
        listOf(w.copy(version=2),w.copy(index=1),w.copy(size=0),w.copy(count=2),w.copy(data="AA=="),w.copy(kind="BAD")).forEach {
            assertTrue(runCatching { it.encode() }.isFailure)
        }
    }
    @Test fun senderReceiverCommitBeforeFinalAck() = runTest {
        val data = temp.newFile().apply { writeBytes(ByteArray(1800) { it.toByte() }) }
        val m = VoicePayloadMetadata(transferId="transfer",sourceId="A",destinationId="B",conversationId="B",
            createdAt=System.currentTimeMillis(),durationMs=1000,audioBytes=512,chunkCount=1)
        var committed = false
        lateinit var a: VoiceTransfer
        lateinit var b: VoiceTransfer
        a = VoiceTransfer(temp.newFolder(),backgroundScope,{"A"},{it=="B"}, { _,w ->
            b.accept(MessagePacket("packet", "A",destinationId="B",payload=w.encode()))
            yield()
        },{ _, _ -> error("Not a receiver") },{_,_->false}, io = StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime })
        b = VoiceTransfer(temp.newFolder(),backgroundScope,{"B"},{it=="A"},{_,w ->
            assertTrue(committed)
            a.accept(MessagePacket("reply","B",destinationId="A",payload=w.encode()))
        },{ payload, _ -> assertArrayEquals(data.readBytes(),payload.encryptedFile.readBytes()); committed=true },{_,_->false}, io = StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime })
        a.send(VoicePayloadService.Payload(m,data))
        repeat(200) { advanceTimeBy(25); runCurrent() }
        assertTrue(committed)
        assertEquals("Delivered",a.states.value[m.transferId])
    }

    @Test fun outOfOrderDuplicatesAndMissingParts() = runTest {
        val m = VoicePayloadMetadata(transferId="t",sourceId="A",destinationId="B",conversationId="B",
            createdAt=1,durationMs=1000,audioBytes=1,chunkCount=1)
        val replies = mutableListOf<String>()
        var commits = 0
        val receiver = VoiceTransfer(temp.newFolder(),backgroundScope,{"B"},{it=="A"},
            {_,w -> replies.add(w.kind)}, {payload,_ ->
                assertEquals(769,payload.encryptedFile.length().toInt()); commits++
            },{_,_->false},io=StandardTestDispatcher(testScheduler),now={testScheduler.currentTime})
        suspend fun feed(w: VoiceWire) {
            receiver.accept(MessagePacket("id","A",destinationId="B",payload=w.encode())); runCurrent()
        }
        val start = VoiceWire(kind="START",transfer="t",size=769,count=2,metadata=m)
        feed(start)
        val last = start.copy(kind="DATA",metadata=null,index=1,data=byteArrayOf(7).b64())
        feed(last); feed(last)
        feed(start.copy(kind="DATA",metadata=null,index=0,data=ByteArray(768).b64()))
        feed(start.copy(kind="END",metadata=null))
        assertEquals(1,commits); assertEquals(listOf("ACK"),replies)
        feed(start.copy(transfer="missing",metadata=m.copy(transferId="missing")))
        feed(start.copy(kind="END",transfer="missing",metadata=null))
        assertEquals(1,commits); assertEquals("ERROR",replies.last())
    }
    @Test fun forgedEarlyControlsTimeoutAndOutgoingLimit() = runTest {
        val file=temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        val m=VoicePayloadMetadata(transferId="t",sourceId="A",destinationId="B",conversationId="B",createdAt=1,durationMs=1,audioBytes=1,chunkCount=1)
        val sender=VoiceTransfer(temp.newFolder(),backgroundScope,{"A"},{true},
            {_,_->delay(300_000)}, {_,_->error("unexpected")},{_,_->false},
            io=StandardTestDispatcher(testScheduler),now={testScheduler.currentTime})
        val payload=VoicePayloadService.Payload(m,file)
        sender.send(payload)
        assertTrue(runCatching { sender.send(payload) }.isFailure)
        sender.accept(MessagePacket("forged","C",destinationId="A",payload=VoiceWire(kind="ACK",transfer="t",size=1,count=1).encode()))
        sender.accept(MessagePacket("early","B",destinationId="A",payload=VoiceWire(kind="ACK",transfer="t",size=1,count=1).encode()))
        runCurrent(); advanceTimeBy(240_001); runCurrent()
        assertEquals("Failed: transfer deadline exceeded",sender.states.value["t"])
    }
    @Test fun receiveQueueIsBounded() = runTest {
        val receiver=VoiceTransfer(temp.newFolder(),backgroundScope,{"B"},{true},{_,_->},{_,_->},{_,_->false},io=StandardTestDispatcher(testScheduler))
        val packet=MessagePacket("id","A",destinationId="B",payload="invalid")
        repeat(8) { receiver.accept(packet) }
        assertTrue(runCatching { receiver.accept(packet) }.isFailure)
    }

    @Test fun secondSendIsActionablyRejectedAndFirstIsUnchanged() = runTest {
        val file=temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        val m=VoicePayloadMetadata(transferId="first",sourceId="A",destinationId="B",conversationId="B",createdAt=1,durationMs=1,audioBytes=1,chunkCount=1)
        val packets=mutableListOf<String>()
        val sender=VoiceTransfer(temp.newFolder(),backgroundScope,{"A"},{true},
            {_,w -> packets.add(w.transfer); delay(300_000)},{_,_->},{_,_->false},
            io=StandardTestDispatcher(testScheduler),now={testScheduler.currentTime})
        sender.send(VoicePayloadService.Payload(m,file)); runCurrent()
        val failure=runCatching { sender.send(VoicePayloadService.Payload(m.copy(transferId="second"),file)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("already sending"))
        assertEquals(listOf("first"),packets)
        assertFalse(sender.states.value.containsKey("second"))
        assertTrue(file.exists())
        advanceTimeBy(240_001); runCurrent()
        assertTrue(sender.states.value.getValue("first").startsWith("Failed"))
        sender.send(VoicePayloadService.Payload(m.copy(transferId="second"),file)); runCurrent()
        assertEquals(listOf("first","second"),packets)
    }
}
