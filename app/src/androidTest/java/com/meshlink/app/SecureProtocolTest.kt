package com.meshlink.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.security.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SecureProtocolTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun mutualTlsKeystoreAndJournalSurviveReopen() = runBlocking {
        val namespace="test."+UUID.randomUUID()
        val a=Identity(context,namespace+"a"); val b=Identity(context,namespace+"b")
        val ac=a.own("A","First"); val bc=b.own("B","Second")
        assertEquals(ac,Identity(context,namespace+"a").own("A","First"))
        assertNull(a.peer("B"))
        a.trust(PeerCard.parse(bc.export()),ac); b.trust(PeerCard.parse(ac.export()),bc)
        assertEquals(bc,Identity(context,namespace+"a").peer("B"))
        val records=ArrayDeque<Pair<Boolean,ByteArray>>()
        val received=mutableListOf<String>(); var endpoints=0
        val client=TlsPipe(a.engine(true),{ records.add(false to it) },{endpoints++},{received.add(it.toString(Charsets.UTF_8))})
        val server=TlsPipe(b.engine(false),{ records.add(true to it) },{endpoints++},{received.add(it.toString(Charsets.UTF_8))})
        suspend fun drain() {
            var steps=0
            while(records.isNotEmpty()) {
                check(++steps<128)
                val (toClient,data)=records.removeFirst()
                val endpoint=if(toClient) client else server
                data.asList().chunked(37).forEach { endpoint.receive(it.toByteArray()) }
            }
        }
        server.begin();client.begin();drain(); assertEquals(2,endpoints)
        client.send("secure link".toByteArray()); val replay=records.first().second; drain()
        assertEquals(listOf("secure link"),received)
        assertTrue(runCatching { server.receive(replay) }.isFailure)
        val packet=MessagePacket(namespace,"A",destinationId="B",payload="private")
        val sealed=EndToEnd.seal(packet,a.signing(),bc.wrappingKey())
        assertEquals(packet,EndToEnd.open(sealed,b.decrypting(),ac.cert().publicKey,packet.timestamp))
        MessageJournal(context).use { store ->
            assertTrue(store.deliverOnce("B",packet)); assertFalse(store.deliverOnce("B",packet))
        }
        MessageJournal(context).use { store ->
            assertFalse(store.consume("A",packet.messageId,packet.timestamp))
            assertTrue(store.load("B").any { it.first==packet && it.second=="Received" })
            store.clear("B")
        }
        a.forget("B"); assertNull(a.peer("B"))
        val untrusted=TlsPipe(a.engine(true),{ records.add(false to it) },{ fail("Untrusted handshake") },{})
        val rejectServer=TlsPipe(b.engine(false),{ records.add(true to it) },{}, {})
        untrusted.begin();rejectServer.begin()
        assertTrue(runCatching {
            while(records.isNotEmpty()) { val (toClient,data)=records.removeFirst(); (if(toClient) untrusted else rejectServer).receive(data) }
        }.isFailure)
    }
}
