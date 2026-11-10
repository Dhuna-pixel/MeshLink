package com.meshlink.app.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.utils.JsonSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MessageJournal(context: Context, databaseName: String = "mesh_history_v1.db") : SQLiteOpenHelper(context,databaseName,null,4) {
    private val vault = Vault(context, "mesh_history_v1")
    private fun createVoice(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE voice_receipts(owner TEXT NOT NULL,sender TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(owner,sender,id))")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(owner TEXT, sender TEXT, id TEXT, stamp INTEGER, body TEXT NOT NULL, status TEXT, unread INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,sender,id))")
        createVoice(db)
        createVoiceQueue(db)
        db.execSQL("CREATE TABLE replay(sender TEXT, id TEXT, expires INTEGER NOT NULL, PRIMARY KEY(sender,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 4) createVoiceQueue(db)
        if (old < 3) createVoice(db)
        if (old < 2) db.execSQL("ALTER TABLE history ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
    }
    private fun createVoiceQueue(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE voice_queue(seq INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,id TEXT NOT NULL,bytes INTEGER NOT NULL,UNIQUE(owner,id))")
    }
    /** Audio and queue reference commit together; caller must retain draft until this returns. */
    fun enqueueVoice(owner: String, packet: MessagePacket, maxItems: Int = 10, maxBytes: Int = 950 * 1024) {
        require(packet.sourceId == owner && packet.voice != null && packet.voiceAudio != null)
        packet.voice.validate()
        require(packet.voiceAudio.bytes64().size == packet.voice.audioBytes)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (queuedVoiceIds(owner).contains(packet.messageId)) { db.setTransactionSuccessful(); return }
            db.rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM voice_queue",null).use {
                check(it.moveToFirst() && it.getInt(0) < maxItems && it.getLong(1) + packet.voice.audioBytes <= maxBytes) {
                    "Voice queue full. Wait for a queued message to finish, then try Send again."
                }
            }
            val values = ContentValues().apply { put("owner",owner); put("id",packet.messageId); put("bytes",packet.voice.audioBytes) }
            db.insertOrThrow("voice_queue",null,values)
            save(owner,packet,"Queued")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun queuedVoiceIds(owner: String): List<String> = buildList {
        readableDatabase.rawQuery("SELECT id FROM voice_queue WHERE owner=? ORDER BY seq",arrayOf(owner)).use {
            while(it.moveToNext()) add(it.getString(0))
        }
    }
    fun queuedVoiceHead(owner: String): MessagePacket? {
        val id = queuedVoiceIds(owner).firstOrNull() ?: return null
        return load(owner).firstOrNull { it.first.sourceId == owner && it.first.messageId == id }?.first
            ?: error("Queued voice audio missing")
    }
    fun finishQueuedVoice(owner: String, id: String, status: String) {
        val db=writableDatabase; db.beginTransaction()
        try {
            status(owner,id,status)
            db.delete("voice_queue","owner=? AND id=?",arrayOf(owner,id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** Commit verified audio and its replay marker in the same SQLite transaction.
     * Bounded receipt inbox; future conversation integration must explicitly consume/delete receipts.
     */
    internal fun storeVoice(owner: String, metadata: VoicePayloadMetadata, audio: java.io.File): Boolean {
        metadata.validate()
        require(owner == metadata.destinationId && audio.length() == metadata.audioBytes.toLong())
        val id = "voice/1/" + metadata.transferId
        val record = Json.encodeToString(listOf(Json.encodeToString(metadata), audio.readBytes().b64()))
        val encrypted = vault.encrypt(label(owner,metadata.sourceId,id), record)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (voiceReceipt(owner,metadata.sourceId,metadata.transferId) != null) {
                db.setTransactionSuccessful()
                return false
            }
            // Receipt audio duplicates encrypted history. Remove only redundant receipt rows;
            // keep history and replay markers, including the durable duplicate lookup below.
            db.execSQL("DELETE FROM voice_receipts WHERE EXISTS (SELECT 1 FROM history h WHERE h.owner=voice_receipts.owner AND h.sender=voice_receipts.sender AND voice_receipts.id='voice/1/' || h.id)")
            check(db.compileStatement("SELECT COUNT(*) FROM voice_receipts").use { it.simpleQueryForLong() } < 128) { "Voice receipt storage full" }
            if (!consume(metadata.sourceId,id,metadata.createdAt)) {
                db.setTransactionSuccessful()
                return false
            }
            val values = ContentValues().apply {
                put("owner",owner); put("sender",metadata.sourceId); put("id",id); put("body",encrypted)
            }
            check(db.insertOrThrow("voice_receipts",null,values) != -1L)
            save(owner, MessagePacket(metadata.transferId, metadata.sourceId, destinationId = owner,
                payload = "Voice message · ${metadata.durationMs / 1000}s", timestamp = metadata.createdAt,
                voice = metadata, voiceAudio = audio.readBytes().b64()), "Received", unread = true)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    /** Returns only locally authenticated, durable receipts, including after process restart. */
    fun voiceReceipt(owner: String, sender: String, transferId: String): Pair<VoicePayloadMetadata, ByteArray>? {
        val id = "voice/1/" + transferId
        return readableDatabase.rawQuery("SELECT body FROM voice_receipts WHERE owner=? AND sender=? AND id=?", arrayOf(owner,sender,id)).use {
            if (!it.moveToFirst()) {
                readableDatabase.rawQuery("SELECT body FROM history WHERE owner=? AND sender=? AND id=?", arrayOf(owner,sender,transferId)).use { history ->
                    if (!history.moveToFirst()) null else {
                        val packet = JsonSerializer.jsonToPacket(vault.decrypt(label(owner,sender,transferId),history.getString(0)))
                        packet?.voice?.let { metadata ->
                            metadata.validate()
                            require(metadata.sourceId == sender && metadata.destinationId == owner && metadata.transferId == transferId)
                            val bytes = requireNotNull(packet.voiceAudio).bytes64()
                            require(bytes.size == metadata.audioBytes)
                            metadata to bytes
                        }
                    }
                }
            } else {
                val parts = Json.decodeFromString<List<String>>(vault.decrypt(label(owner,sender,id),it.getString(0)))
                require(parts.size == 2)
                val metadata = Json.decodeFromString<VoicePayloadMetadata>(parts[0]).also { m ->
                    m.validate(); require(m.sourceId == sender && m.destinationId == owner && m.transferId == transferId)
                }
                val bytes = parts[1].bytes64()
                require(bytes.size == metadata.audioBytes)
                metadata to bytes
            }
        }
    }
    fun unread(owner: String): Set<Pair<String,String>> = buildSet {
        readableDatabase.rawQuery("SELECT sender,id FROM history WHERE owner=? AND unread=1", arrayOf(owner)).use {
            while (it.moveToNext()) add(it.getString(0) to it.getString(1))
        }
    }
    fun markRead(owner: String, sender: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString { "?" }
        writableDatabase.execSQL("UPDATE history SET unread=0 WHERE owner=? AND sender=? AND id IN ($placeholders)",
            (listOf(owner,sender) + ids).toTypedArray())
    }
    private fun label(owner: String, sender: String, id: String) = Json.encodeToString(listOf(owner,sender,id))
    fun save(owner: String, packet: MessagePacket, status: String?, unread: Boolean = false) {
        val values = ContentValues().apply {
            put("owner",owner); put("sender",packet.sourceId); put("id",packet.messageId); put("stamp",packet.timestamp)
            put("body",vault.encrypt(label(owner,packet.sourceId,packet.messageId), JsonSerializer.packetToJson(packet)))
            put("status",status); put("unread", unread)
        }
        writableDatabase.insertWithOnConflict("history",null,values,SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        writableDatabase.execSQL("DELETE FROM history WHERE rowid IN (SELECT rowid FROM history WHERE NOT EXISTS (SELECT 1 FROM voice_queue q WHERE q.owner=history.owner AND q.id=history.id AND history.sender=history.owner) ORDER BY stamp DESC LIMIT -1 OFFSET 500)")
    }
    fun saveBatch(owner: String, packets: List<MessagePacket>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            packets.forEach { save(owner, it, "Queued") }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun status(owner: String,id: String,status: String) {
        writableDatabase.update("history", ContentValues().apply { put("status",status) },"owner=? AND sender=? AND id=?", arrayOf(owner,owner,id))
    }
    fun load(owner: String): List<Pair<MessagePacket,String?>> {
        val result = mutableListOf<Pair<MessagePacket,String?>>()
        readableDatabase.rawQuery("SELECT sender,id,body,status FROM history WHERE owner=? ORDER BY stamp DESC",arrayOf(owner)).use { c ->
            while (c.moveToNext()) {
                val packet = JsonSerializer.jsonToPacket(vault.decrypt(label(owner,c.getString(0),c.getString(1)),c.getString(2)))
                if (packet != null) result += packet to c.getString(3)
            }
        }
        return result
    }
    fun contains(owner: String, sender: String, id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM history WHERE owner=? AND sender=? AND id=?", arrayOf(owner,sender,id)).use { it.moveToFirst() }

    fun deleteMatching(owner: String, matches: (MessagePacket) -> Boolean): List<MessagePacket> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = load(owner).map { it.first }.filter(matches)
            removed.forEach { db.delete("voice_queue", "owner=? AND id=?", arrayOf(owner,it.messageId)); db.delete("history", "owner=? AND sender=? AND id=?", arrayOf(owner, it.sourceId, it.messageId)) }
            // Keep replay records: deleting history must not allow old transmissions to reappear.
            db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }
    fun clear(owner: String) {
        val db=writableDatabase; db.beginTransaction()
        try {
            db.delete("voice_queue","owner=?",arrayOf(owner))
            db.delete("history","owner=?",arrayOf(owner))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun deliverOnce(owner: String, packet: MessagePacket): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val fresh = consume(packet.sourceId, packet.messageId, packet.timestamp) && !contains(owner,packet.sourceId,packet.messageId)
            if (fresh) save(owner, packet, "Received", unread = true)
            db.setTransactionSuccessful()
            return fresh
        } finally { db.endTransaction() }
    }
    fun consume(source: String, id: String, stamp: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("replay","expires < ?",arrayOf(System.currentTimeMillis().toString()))
            val count = db.compileStatement("SELECT COUNT(*) FROM replay").use { it.simpleQueryForLong() }
            if (count >= 4096) {
                val seen = db.rawQuery("SELECT 1 FROM replay WHERE sender=? AND id=?",arrayOf(source,id)).use { it.moveToFirst() }
                check(seen) { "Replay window full" }
                db.setTransactionSuccessful()
                return false
            }
            val row = ContentValues().apply { put("sender",source); put("id",id); put("expires",stamp + EndToEnd.LIFETIME) }
            val inserted = db.insertWithOnConflict("replay",null,row,SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally { db.endTransaction() }
    }
}
