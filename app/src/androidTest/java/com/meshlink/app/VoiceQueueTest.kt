package com.meshlink.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.security.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VoiceQueueTest {
    private fun packet(id: String)=MessagePacket(id,"owner",destinationId="peer",payload="Voice message",
        voice=VoicePayloadMetadata(transferId=id,sourceId="owner",destinationId="peer",conversationId="peer",
            createdAt=System.currentTimeMillis(),durationMs=1000,audioBytes=3,chunkCount=1),voiceAudio="AQID")
    @Test fun fifoSurvivesReopenAndRetryMovesToTailWithoutDuplicatingHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db="queue-${UUID.randomUUID()}.db"
        try {
            MessageJournal(context,db).use { j ->
                listOf("A","B","C").forEach { j.enqueueVoice("owner",packet(it)) }
                j.enqueueVoice("owner",packet("B"))
                assertEquals(listOf("A","B","C"),j.queuedVoiceIds("owner"))
                j.status("owner","A","Sending 1/3")
            }
            MessageJournal(context,db).use { j ->
                assertEquals("A",j.queuedVoiceHead("owner")!!.messageId)
                j.finishQueuedVoice("owner","A","Failed: route unavailable")
                assertEquals("B",j.queuedVoiceHead("owner")!!.messageId)
                j.enqueueVoice("owner",packet("A"))
                assertEquals(listOf("B","C","A"),j.queuedVoiceIds("owner"))
                assertEquals(3,j.load("owner").size)
                j.finishQueuedVoice("owner","B","Canceled")
                assertEquals(listOf("C","A"),j.queuedVoiceIds("owner"))
            }
        } finally { context.deleteDatabase(db) }
    }
    @Test fun capacityRejectionIsAtomicAndAudioIsEncrypted() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db="queue-${UUID.randomUUID()}.db"
        try {
            MessageJournal(context,db).use { j ->
                repeat(10) { j.enqueueVoice("owner",packet("$it")) }
                assertTrue(runCatching { j.enqueueVoice("owner",packet("overflow")) }.isFailure)
                assertEquals(10,j.load("owner").size)
                assertEquals(10,j.queuedVoiceIds("owner").size)
                j.readableDatabase.rawQuery("SELECT body FROM history",null).use {
                    while(it.moveToNext()) { assertFalse(it.getString(0).contains("voiceAudio")); assertFalse(it.getString(0).contains("AQID")) }
                }
            }
        } finally { context.deleteDatabase(db) }
    }
    @Test fun byteLimitRejectsWithoutJournalMutation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db="queue-${UUID.randomUUID()}.db"
        try {
            MessageJournal(context,db).use { j ->
                j.enqueueVoice("owner",packet("A"),maxBytes=3)
                assertTrue(runCatching { j.enqueueVoice("owner",packet("B"),maxBytes=3) }.isFailure)
                assertEquals(listOf("A"),j.queuedVoiceIds("owner"))
            }
        } finally { context.deleteDatabase(db) }
    }
}
