package com.meshlink.app.security

import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.routing.AodvEngine
import kotlinx.serialization.json.*
import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test

class EndToEndTest {
    private val sign = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val wrap = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val message = MessagePacket("message","A",destinationId="C",payload="private payload",routeHistory=listOf("A"))
    private fun open(p: MessagePacket) = EndToEnd.open(p,wrap.private,sign.public,message.timestamp)
    @Test fun sealsContentAndAuthenticatesImmutableHeaders() {
        val sealed = EndToEnd.seal(message,sign.private,wrap.public)
        assertFalse(sealed.payload.contains(message.payload))
        assertEquals(message,open(sealed))
        listOf(sealed.copy(sourceId="B"),sealed.copy(destinationId="B"),sealed.copy(messageId="other"),
            sealed.copy(ttl=sealed.ttl+1),sealed.copy(ackFor="other"),sealed.copy(returnPath=listOf("B"))).forEach {
            assertTrue(runCatching { open(it) }.isFailure)
        }
    }
    @Test fun rejectsTamperingForeignSignersWrongPrivateKeysExpirationAndDowngrade() {
        val sealed=EndToEnd.seal(message,sign.private,wrap.public)
        val obj=Json.parseToJsonElement(sealed.payload).jsonObject
        for (field in listOf("body","iv","wrapped","signature","version")) {
            val changed=JsonObject(obj + (field to if(field=="version") JsonPrimitive(1) else JsonPrimitive("AAAA")))
            assertTrue(runCatching { open(sealed.copy(payload=changed.toString())) }.isFailure)
        }
        assertTrue(runCatching { open(message) }.isFailure)
        assertTrue(runCatching { EndToEnd.open(sealed,wrap.private,KeyPairGenerator.getInstance("EC").generateKeyPair().public,message.timestamp) }.isFailure)
        assertTrue(runCatching { EndToEnd.open(sealed,KeyPairGenerator.getInstance("RSA").generateKeyPair().private,sign.public,message.timestamp) }.isFailure)
        assertTrue(runCatching { EndToEnd.open(sealed,wrap.private,sign.public,message.timestamp+EndToEnd.LIFETIME+1) }.isFailure)
    }
    @Test fun invalidGcmTagRejectedEvenWithValidSenderSignature() {
        val sealed = EndToEnd.seal(message, sign.private, wrap.public)
        val fields = Json.parseToJsonElement(sealed.payload).jsonObject.toMutableMap()
        val corrupted = fields.getValue("body").jsonPrimitive.content.bytes64()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        fields["body"] = JsonPrimitive(corrupted.b64())
        val header = JsonArray(listOf("MeshLink/E2E/2", message.messageId, message.sourceId, message.sourceName,
            message.destinationId, message.destinationName, message.timestamp.toString(), message.ttl.toString(), "", "[]").map(::JsonPrimitive))
        val signed = JsonArray(listOf(JsonPrimitive(header.toString().toByteArray().b64()), fields.getValue("wrapped"),
            fields.getValue("iv"), fields.getValue("body"))).toString().toByteArray()
        fields["signature"] = JsonPrimitive(java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(sign.private); update(signed); sign().b64()
        })
        val failure = runCatching { open(sealed.copy(payload = JsonObject(fields).toString())) }.exceptionOrNull()
        assertTrue(failure is javax.crypto.AEADBadTagException)
    }
    @Test fun generatesFreshContentKeysAndNonces() {
        val envelopes=(1..32).map { Json.parseToJsonElement(EndToEnd.seal(message,sign.private,wrap.public).payload).jsonObject }
        assertEquals(32,envelopes.map { it["iv"] }.distinct().size)
        assertEquals(32,envelopes.map { it["wrapped"] }.distinct().size)
    }
    @Test fun threeHopForwardingAndDestinationSignedAck() {
        val ids=listOf("A","B","C","D")
        val signatures=ids.associateWith { KeyPairGenerator.getInstance("EC").generateKeyPair() }
        val keys=ids.associateWith { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        val queue=ArrayDeque<Triple<String,String,MessagePacket>>()
        val delivered=mutableListOf<MessagePacket>(); val statuses=mutableListOf<String>()
        val engines=ids.associateWith { id -> AodvEngine(id,id,{ to,p ->
            val wire=if (p.ackFor!=null && p.sourceId==id) EndToEnd.seal(p,signatures.getValue(id).private,keys.getValue(p.destinationId).public) else p
            queue.add(Triple(id,to,wire));true
        },delivered::add,{_,s->statuses.add(s)},{_,_->},{}) }
        ids.zipWithNext().forEach { (a,b)->engines.getValue(a).neighborUp(b);engines.getValue(b).neighborUp(a) }
        engines.getValue("A").send(EndToEnd.seal(message.copy(destinationId="D"),signatures.getValue("A").private,keys.getValue("D").public))
        var steps=0
        while(queue.isNotEmpty()) {
            check(++steps<500)
            val (from,to,p)=queue.removeFirst()
            val plain=if(p.control==null && p.destinationId==to) EndToEnd.open(p,keys.getValue(to).private,signatures.getValue(p.sourceId).public,System.currentTimeMillis())
                else { if(p.control==null) assertTrue(runCatching { EndToEnd.open(p,keys.getValue(to).private,signatures.getValue(p.sourceId).public,System.currentTimeMillis()) }.isFailure);p }
            engines.getValue(to).receive(plain,from)
        }
        assertEquals(message.payload,delivered.single().payload); assertEquals(ids,delivered.single().routeHistory)
        assertEquals("Delivered (destination ACK)",statuses.last())
    }
}
