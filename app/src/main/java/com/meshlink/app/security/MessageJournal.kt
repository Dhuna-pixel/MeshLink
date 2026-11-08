package com.meshlink.app.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.utils.JsonSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MessageJournal(context: Context, databaseName: String = "mesh_history_v1.db") : SQLiteOpenHelper(context,databaseName,null,2) {
    private val vault = Vault(context, "mesh_history_v1")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(owner TEXT, sender TEXT, id TEXT, stamp INTEGER, body TEXT NOT NULL, status TEXT, unread INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,sender,id))")
        db.execSQL("CREATE TABLE replay(sender TEXT, id TEXT, expires INTEGER NOT NULL, PRIMARY KEY(sender,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("ALTER TABLE history ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
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
        writableDatabase.execSQL("DELETE FROM history WHERE rowid IN (SELECT rowid FROM history ORDER BY stamp DESC LIMIT -1 OFFSET 500)")
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
            removed.forEach { db.delete("history", "owner=? AND sender=? AND id=?", arrayOf(owner, it.sourceId, it.messageId)) }
            // Keep replay records: deleting history must not allow old transmissions to reappear.
            db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }
    fun clear(owner: String) { writableDatabase.delete("history","owner=?",arrayOf(owner)) }
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
