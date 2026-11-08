package com.meshlink.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.security.*
import com.meshlink.app.utils.JsonSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class JournalMigrationTest {
    @Test fun upgradePreservesCiphertextAndTracksNewReceiptsAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "migration-${UUID.randomUUID()}.db"
        val old = MessagePacket("old","peer",destinationId = "me",payload = "private history")
        val fresh = old.copy(messageId = "new",timestamp = old.timestamp + 1)
        val cipher = Vault(context,"mesh_history_v1").encrypt(Json.encodeToString(listOf("me","peer","old")), JsonSerializer.packetToJson(old))
        try {
            context.openOrCreateDatabase(file,Context.MODE_PRIVATE,null).use { db ->
                db.execSQL("CREATE TABLE history(owner TEXT,sender TEXT,id TEXT,stamp INTEGER,body TEXT NOT NULL,status TEXT,PRIMARY KEY(owner,sender,id))")
                db.execSQL("CREATE TABLE replay(sender TEXT,id TEXT,expires INTEGER NOT NULL,PRIMARY KEY(sender,id))")
                db.execSQL("INSERT INTO history VALUES(?,?,?,?,?,?)", arrayOf("me","peer","old",old.timestamp,cipher,"Received"))
                db.version = 1
            }
            MessageJournal(context,file).use {
                assertEquals(listOf(old to "Received"),it.load("me"))
                assertTrue(it.unread("me").isEmpty())
                assertFalse(it.deliverOnce("me",old.copy(timestamp = System.currentTimeMillis())))
                assertTrue(it.deliverOnce("me",fresh))
                assertFalse(it.deliverOnce("me",fresh))
                assertEquals(listOf(fresh,old),it.load("me").map { row -> row.first })
            }
            MessageJournal(context,file).use {
                assertEquals(setOf("peer" to "new"), it.unread("me"))
                it.markRead("me","peer",setOf("new"))
                val outgoing = old.copy(sourceId="me", destinationId="peer")
                it.save("me",outgoing,"Queued")
                it.status("me","old","Delivered (destination ACK)")
                assertEquals("Delivered (destination ACK)", it.load("me").first { row -> row.first.sourceId == "me" }.second)
                it.readableDatabase.rawQuery("SELECT body FROM history WHERE id='old' AND sender='peer'",null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(cipher,cursor.getString(0))
                }
            }
            MessageJournal(context,file).use { assertTrue(it.unread("me").isEmpty()) }
        } finally { context.deleteDatabase(file) }
    }
}
