package com.meshlink.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.routing.AodvEngine
import com.meshlink.app.security.*
import com.meshlink.app.utils.JsonSerializer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupProtocolTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedFanoutDifferentPathsMemberRepliesPersistenceAndTamperRejection() {
        val namespace = "group-test-" + UUID.randomUUID()
        val ids = listOf("A", "B", "C", "D")
        val identities = ids.associateWith { Identity(context, "$namespace-$it") }
        val profiles = ids.associateWith { identities.getValue(it).profile(Node(it, "Person $it")) }
        val chat = GroupChat(UUID.randomUUID().toString(), "Rescue team", "A", listOf("A", "B", "C").map { profiles.getValue(it) })
            .sign(identities.getValue("A").signing())
        chat.verify()
        val logical = GroupContext(chat, UUID.randomUUID().toString())
        val queue = ArrayDeque<Triple<String, String, MessagePacket>>()
        val received = mutableMapOf<String, MutableList<MessagePacket>>()
        val statuses = mutableMapOf<String, String>()
        val paths = mutableMapOf<String, List<String>>()
        val engines = ids.associateWith { id -> AodvEngine(id, id, { to, p ->
            val wire = if (p.ackFor != null && p.sourceId == id)
                EndToEnd.seal(p, identities.getValue(id).signing(), profiles.getValue(p.destinationId).profile.card.wrappingKey()) else p
            queue.add(Triple(id, to, requireNotNull(JsonSerializer.bytesToPacket(JsonSerializer.packetToBytes(wire)))))
            true
        }, { p -> received.getOrPut(id) { mutableListOf() }.add(p) }, { key, status -> statuses[key] = status }, { _, _ -> }, {},
            acknowledged = { key, path -> paths[key] = path }) }
        listOf("A" to "B", "A" to "D", "D" to "C").forEach { (a, b) -> engines.getValue(a).neighborUp(b); engines.getValue(b).neighborUp(a) }
        fun send(from: String, to: String, group: GroupContext, text: String) {
            val p = MessagePacket("$from-$to-${group.messageId}", from, "Person $from", to, "Person $to", text, ttl = 16, routeHistory = listOf(from), group = group)
            val sealed = EndToEnd.seal(p, identities.getValue(from).signing(), profiles.getValue(to).profile.card.wrappingKey())
            assertNull(sealed.group)
            assertFalse(JsonSerializer.packetToJson(sealed).contains(chat.name))
            engines.getValue(from).send(sealed)
        }
        fun drain() {
            var steps = 0
            while (queue.isNotEmpty()) {
                check(++steps < 1000)
                val (from, to, wire) = queue.removeFirst()
                val p = if (wire.control == null && wire.destinationId == to)
                    EndToEnd.open(wire, identities.getValue(to).decrypting(), profiles.getValue(wire.sourceId).profile.card.cert().publicKey, System.currentTimeMillis())
                else wire
                if (to == "D" && wire.control == null) assertNull(p.group)
                engines.getValue(to).receive(p, from)
            }
        }
        send("A", "B", logical, "Water available")
        send("A", "C", logical, "Water available")
        drain()
        assertEquals(listOf("A", "B"), paths["A-B-${logical.messageId}"])
        assertEquals(listOf("A", "D", "C"), paths["A-C-${logical.messageId}"])
        assertEquals(logical, received.getValue("B").single().group)
        assertEquals(logical, received.getValue("C").single().group)
        assertTrue(statuses.values.all { it.startsWith("Delivered") })
        val learned = received.getValue("B").single().group!!.chat
        learned.members.filter { it.profile.card.id != "B" }.forEach { identities.getValue("B").learn(it, profiles.getValue("B").profile.card) }
        assertNotNull(identities.getValue("B").participant("C"))
        val reply = GroupContext(learned, UUID.randomUUID().toString())
        send("B", "A", reply, "On our way")
        send("B", "C", reply, "On our way")
        drain()
        assertEquals("On our way", received.getValue("A").single().payload)
        assertEquals("On our way", received.getValue("C").last().payload)
        assertEquals(listOf("B", "A", "D", "C"), paths["B-C-${reply.messageId}"])
        val store = GroupStore(Vault(context, namespace), "B")
        store.remember(learned)
        val reopened = GroupStore(Vault(context, namespace), "B")
        assertEquals(listOf(chat), reopened.chats())
        reopened.leave(chat.id)
        reopened.remember(chat)
        assertTrue(chat.id in GroupStore(Vault(context, namespace), "B").left())
        assertTrue(runCatching { chat.copy(name = "Hijacked").verify() }.isFailure)
        assertTrue(runCatching { chat.copy(members = chat.members.dropLast(1)).verify() }.isFailure)
        assertTrue(runCatching { logical.verify("D", "B") }.isFailure)
        val invalid = MessagePacket("bad", "D", destinationId = "B", payload = "intruder", group = logical)
        assertTrue(runCatching { EndToEnd.seal(invalid, identities.getValue("D").signing(), profiles.getValue("B").profile.card.wrappingKey()) }.isFailure)
        val valid = invalid.copy(sourceId = "A")
        val sealed = EndToEnd.seal(valid, identities.getValue("A").signing(), profiles.getValue("B").profile.card.wrappingKey())
        assertTrue(runCatching { EndToEnd.open(sealed.copy(group = logical), identities.getValue("B").decrypting(), profiles.getValue("A").profile.card.cert().publicKey, System.currentTimeMillis()) }.isFailure)
        MessageJournal(context, "$namespace.db").use { journal ->
            journal.saveBatch("A", listOf(valid.copy(messageId = "one"), valid.copy(messageId = "two", destinationId = "C")))
            assertEquals(2, journal.load("A").size)
            assertEquals(logical, journal.load("A").first().first.group)
        }
    }

    @Test fun eightMemberGroupAndFullLengthMessageFitTheWireBudget() {
        val namespace = "group-limit-" + UUID.randomUUID()
        val identities = (0 until GroupChat.MAX_MEMBERS).map { Identity(context, "$namespace-$it") }
        val profiles = identities.mapIndexed { index, identity -> identity.profile(Node(UUID.randomUUID().toString(), "Member $index")) }
        val from = profiles.first().profile.card
        val to = profiles.last().profile.card
        val chat = GroupChat(UUID.randomUUID().toString(), "Emergency coordination", from.id, profiles).sign(identities.first().signing())
        val packet = MessagePacket("full-group", from.id, from.name, to.id, to.name, "x".repeat(2000),
            group = GroupContext(chat, UUID.randomUUID().toString()))
        val sealed = EndToEnd.seal(packet, identities.first().signing(), to.wrappingKey())
        assertTrue(JsonSerializer.packetToBytes(sealed).size < 32_000)
        assertEquals(packet, EndToEnd.open(sealed, identities.last().decrypting(), from.cert().publicKey, packet.timestamp))
        assertTrue(runCatching { chat.copy(members = profiles + profiles.first()).verify() }.isFailure)
    }

    @Test fun tlsCarriesMaximumSizedGroupPayloadAcrossRecordBoundaries() = runBlocking {
        val namespace = "group-tls-" + UUID.randomUUID()
        val a = Identity(context, namespace + "a")
        val b = Identity(context, namespace + "b")
        val ac = a.own("a", "A"); val bc = b.own("b", "B")
        a.trust(bc, ac); b.trust(ac, bc)
        val wire = ArrayDeque<Pair<Boolean, ByteArray>>()
        val received = mutableListOf<ByteArray>()
        val client = TlsPipe(a.engine(true), { wire.add(false to it) }, {}, {})
        val server = TlsPipe(b.engine(false), { wire.add(true to it) }, {}, { received.add(it) })
        suspend fun drain() {
            var steps = 0
            while (wire.isNotEmpty()) {
                check(++steps < 1000)
                val (toClient, bytes) = wire.removeFirst()
                bytes.asList().chunked(210).forEach { (if (toClient) client else server).receive(it.toByteArray()) }
            }
        }
        server.begin(); client.begin(); drain()
        val payload = ByteArray(32_000) { (it % 127).toByte() }
        client.send(payload); drain()
        assertArrayEquals(payload, received.single())
    }
}
