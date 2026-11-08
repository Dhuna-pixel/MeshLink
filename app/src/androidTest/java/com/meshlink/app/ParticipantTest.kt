package com.meshlink.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.domain.models.BleHandshake
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.security.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.UUID

class ParticipantTest {
    @Test fun firstContactTlsRemoteProfilesVerificationAndBlocking() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = UUID.randomUUID().toString()
        val a = Identity(context, namespace + "a")
        val b = Identity(context, namespace + "b")
        val c = Identity(context, namespace + "c")
        val alice = a.profile(Node("a", "Alice", about = "Water at the school"))
        val bob = b.profile(Node("b", "Bob"))
        val cara = c.profile(Node("c", "Cara"))
        val toA = ArrayDeque<ByteArray>()
        val toB = ArrayDeque<ByteArray>()
        val ae = a.engine(true, discoverParticipants = true)
        val be = b.engine(false, discoverParticipants = true)
        val client = TlsPipe(ae, { toB.add(it) }, {}, {
            a.authenticateHello(requireNotNull(BleHandshake.fromJson(it.decodeToString())),
                ae.session.peerCertificates.first() as X509Certificate, alice.profile.card, null)
        })
        val server = TlsPipe(be, { toA.add(it) }, {}, {
            b.authenticateHello(requireNotNull(BleHandshake.fromJson(it.decodeToString())),
                be.session.peerCertificates.first() as X509Certificate, bob.profile.card, null)
        })
        suspend fun drain() {
            var steps = 0
            while (toA.isNotEmpty() || toB.isNotEmpty()) {
                check(++steps < 256)
                if (toA.isNotEmpty()) client.receive(toA.removeFirst())
                if (toB.isNotEmpty()) server.receive(toB.removeFirst())
            }
        }
        fun hello(profile: SignedProfile) = BleHandshake(protocolVersion = 2, nodeId = profile.profile.card.id,
            displayName = profile.profile.card.name, profile = profile.export())
        server.begin(); client.begin(); drain()
        client.send(BleHandshake.toJson(hello(alice)).toByteArray())
        server.send(BleHandshake.toJson(hello(bob)).toByteArray()); drain()
        assertEquals(alice.profile.card, b.participant("a"))
        assertNull(b.peer("a"))
        assertTrue(runCatching { b.authenticateHello(hello(cara),alice.profile.card.cert(),bob.profile.card,null) }.isFailure)

        // The remote endpoint receives the same signed profile through another participant.
        c.learn(SignedProfile.parse(alice.export()), cara.profile.card)
        a.learn(SignedProfile.parse(cara.export()), alice.profile.card)
        val message = MessagePacket("alert", "a", destinationId = "c", payload = "Water available")
        val sealed = EndToEnd.seal(message, a.signing(), requireNotNull(a.participant("c")).wrappingKey())
        assertEquals(message, EndToEnd.open(sealed, c.decrypting(), requireNotNull(c.participant("a")).cert().publicKey, message.timestamp))
        assertTrue(runCatching { c.learn(alice.copy(profile = alice.profile.copy(about = "Changed")), cara.profile.card) }.isFailure)
        val impostor = b.profile(Node("a","Alice"))
        assertTrue(runCatching { c.learn(impostor,cara.profile.card) }.isFailure)
        c.trust(alice.profile.card, cara.profile.card)
        val renamed = a.profile(Node("a","Alex",about = "Medic"))
        c.learn(renamed, cara.profile.card)
        assertTrue(c.peer("a")!!.sameKeys(renamed.profile.card))
        assertEquals("Alex", c.participant("a")?.name)
        c.setBlocked("a", true)
        val reopened = Identity(context,namespace + "c")
        assertNull(reopened.participant("a"))
        reopened.learn(renamed, cara.profile.card)
        assertNull(reopened.participant("a"))
        assertEquals(renamed.profile.card, reopened.linkCard("a"))
        reopened.setBlocked("a",false)
        assertEquals(renamed.profile.card,reopened.participant("a"))
    }
}
