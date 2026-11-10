package com.meshlink.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.security.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceReceiptTest {
    @Test fun receiptSurvivesReopenAndDuplicateCannotReplaceAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "voice-${UUID.randomUUID()}.db"
        val audio = File.createTempFile("voice-test", ".m4a", context.cacheDir)
        val m = VoicePayloadMetadata(transferId = "transfer", sourceId = "sender", destinationId = "owner",
            conversationId = "chat", createdAt = System.currentTimeMillis(), durationMs = 1000, audioBytes = 3, chunkCount = 1)
        try {
            audio.writeBytes(byteArrayOf(1,2,3))
            MessageJournal(context,database).use { assertTrue(it.storeVoice("owner",m,audio)) }
            audio.writeBytes(byteArrayOf(4,5,6))
            MessageJournal(context,database).use {
                assertFalse(it.storeVoice("owner",m,audio))
                assertEquals("transfer", it.load("owner").single().first.voice!!.transferId)
                assertEquals("AQID", it.load("owner").single().first.voiceAudio)
                assertArrayEquals(byteArrayOf(1,2,3),it.voiceReceipt("owner","sender","transfer")!!.second)
                assertNull(it.voiceReceipt("another-owner","sender","transfer"))
            }
        } finally { audio.delete(); context.deleteDatabase(database) }
    }
    @Test fun insertFailureRollsBackReplayMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "voice-${UUID.randomUUID()}.db"
        val audio = File.createTempFile("voice-test", ".m4a", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        val m = VoicePayloadMetadata(transferId = "transfer", sourceId = "sender", destinationId = "owner",
            conversationId = "chat", createdAt = System.currentTimeMillis(), durationMs = 1000, audioBytes = 1, chunkCount = 1)
        try {
            MessageJournal(context,database).use {
                it.writableDatabase.execSQL("CREATE TRIGGER fail_voice BEFORE INSERT ON voice_receipts BEGIN SELECT RAISE(ABORT,'test failure'); END")
                assertTrue(runCatching { it.storeVoice("owner",m,audio) }.isFailure)
                it.writableDatabase.execSQL("DROP TRIGGER fail_voice")
                assertTrue(it.storeVoice("owner",m,audio))
            }
        } finally { audio.delete(); context.deleteDatabase(database) }
    }

    @Test fun moreThan64ReceiptsPreserveHistoryAndDuplicateProtection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "voice-capacity-${UUID.randomUUID()}.db"
        val audio = File.createTempFile("voice-test", ".m4a", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            MessageJournal(context,database).use { journal ->
                repeat(70) { index ->
                    val m=VoicePayloadMetadata(transferId="t$index",sourceId="sender",destinationId="owner",conversationId="chat",
                        createdAt=System.currentTimeMillis(),durationMs=1,audioBytes=1,chunkCount=1)
                    assertTrue(journal.storeVoice("owner",m,audio))
                }
                assertEquals(70,journal.load("owner").size)
                assertNotNull(journal.voiceReceipt("owner","sender","t0"))
                assertEquals(1L,journal.readableDatabase.compileStatement("SELECT COUNT(*) FROM voice_receipts").use { it.simpleQueryForLong() })
            }
        } finally { audio.delete(); context.deleteDatabase(database) }
    }
}
