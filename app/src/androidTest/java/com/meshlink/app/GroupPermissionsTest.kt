package com.meshlink.app

import androidx.test.platform.app.InstrumentationRegistry
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.models.Node
import com.meshlink.app.security.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupPermissionsTest {
    @Test fun unverifiedAndPersonallyBlockedMembersExchangeGroupDataAndAcksButCannotInviteOrDm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = (context.applicationContext as MeshLinkApp).meshRepository
        val local = repo.identity.profile(repo.currentNode.value)
        val suffix = UUID.randomUUID().toString()
        val friendIdentity = Identity(context, "$suffix-friend")
        val blockedIdentity = Identity(context, "$suffix-blocked")
        val friend = friendIdentity.profile(Node(UUID.randomUUID().toString(), "Mutual friend"))
        val blocked = blockedIdentity.profile(Node(UUID.randomUUID().toString(), "Blocked personally"))
        val friendId = friend.profile.card.id
        val blockedId = blocked.profile.card.id
        repo.identity.learn(friend, local.profile.card)
        repo.identity.learn(blocked, local.profile.card)
        instrumentation.runOnMainSync { repo.setBlocked(blockedId, true) }
        assertNull(repo.identity.peer(friendId))
        assertNull(repo.identity.peer(blockedId))
        val chat = GroupChat(UUID.randomUUID().toString(), "Mutual group", friendId, listOf(friend, local, blocked)).sign(friendIdentity.signing())
        fun receive(sender: SignedProfile, signer: Identity, group: GroupContext?, text: String = "hello"): MessagePacket {
            val packet = MessagePacket(UUID.randomUUID().toString(), sender.profile.card.id, sender.profile.card.name,
                local.profile.card.id, local.profile.card.name, text, routeHistory = listOf(sender.profile.card.id), group = group)
            return EndToEnd.open(EndToEnd.seal(packet, signer.signing(), local.profile.card.wrappingKey()), repo.identity.decrypting(),
                sender.profile.card.cert().publicKey, packet.timestamp).createHopPacket(local.profile.card.id)
        }
        fun deliver(packet: MessagePacket) { instrumentation.runOnMainSync { repo.deliverIncoming(packet) } }
        val invitation = receive(friend, friendIdentity, GroupContext(chat, UUID.randomUUID().toString(), "created"))
        assertTrue(repo.incomingAllowed(invitation))
        deliver(invitation)
        val message = receive(blocked, blockedIdentity, GroupContext(chat, UUID.randomUUID().toString()))
        assertTrue(repo.incomingAllowed(message))
        deliver(message)
        val ack = MessagePacket(UUID.randomUUID().toString(), local.profile.card.id, destinationId = blockedId, payload = "", ackFor = message.messageId)
        assertNotNull(repo.outgoingPeer(ack))
        assertFalse(repo.incomingAllowed(receive(blocked, blockedIdentity, null)))
        val personal = MessagePacket("personal", local.profile.card.id, destinationId = blockedId, payload = "private")
        assertNull(repo.outgoingPeer(personal))
        instrumentation.runOnMainSync { assertTrue(repo.sendGroup(chat.id, "Reply to everyone")) }
        val copy = repo.messages.value.first { it.sourceId == local.profile.card.id && it.destinationId == blockedId && it.payload == "Reply to everyone" }
        assertNotNull(repo.outgoingPeer(copy))
        val wire = EndToEnd.seal(copy, repo.identity.signing(), blocked.profile.card.wrappingKey())
        assertNull(wire.group)
        assertNotNull(repo.outgoingPeer(wire)) // encryption must not erase group permission
        assertTrue(repo.incomingAllowed(ack.copy(sourceId = blockedId, destinationId = local.profile.card.id, ackFor = copy.messageId)))
        assertFalse(repo.incomingAllowed(ack.copy(sourceId = blockedId, destinationId = local.profile.card.id, ackFor = "unknown-personal")))
        val blockedChat = GroupChat(UUID.randomUUID().toString(), "Blocked invite", blockedId, listOf(local, blocked)).sign(blockedIdentity.signing())
        assertFalse(repo.incomingAllowed(receive(blocked, blockedIdentity, GroupContext(blockedChat, UUID.randomUUID().toString(), "created"))))
        val friendExit = GroupExit(1, UUID.randomUUID().toString(), true)
        deliver(receive(friend, friendIdentity, GroupContext(chat, UUID.randomUUID().toString(), "exit", friendExit.count, friendExit.token)))
        assertTrue(repo.groupExits.value.getValue(chat.id).getValue(friendId).left)
        instrumentation.runOnMainSync { assertTrue(repo.inviteBack(chat.id, friendId)) }
        val freshInvite = repo.messages.value.first { it.sourceId == local.profile.card.id && it.destinationId == friendId && it.group?.kind == "invite" }
        assertEquals(friendExit.token, freshInvite.group?.exitToken)
        assertNotNull(repo.outgoingPeer(EndToEnd.seal(freshInvite, repo.identity.signing(), friend.profile.card.wrappingKey())))
        deliver(receive(friend, friendIdentity, GroupContext(chat, UUID.randomUUID().toString(), "joined", friendExit.count, friendExit.token)))
        assertFalse(repo.groupExits.value.getValue(chat.id).getValue(friendId).left)
        repeat(3) { index ->
            instrumentation.runOnMainSync { repo.leaveGroup(chat.id) }
            val exit = repo.groupExits.value.getValue(chat.id).getValue(local.profile.card.id)
            assertEquals(index + 1, exit.count)
            assertTrue(chat.id in repo.leftGroups.value)
            if (index < 2) {
                val addBack = GroupContext(chat, UUID.randomUUID().toString(), "invite", exit.count, exit.token)
                assertFalse(repo.incomingAllowed(receive(blocked, blockedIdentity, addBack)))
                val allowed = receive(friend, friendIdentity, addBack)
                assertTrue(repo.incomingAllowed(allowed))
                deliver(allowed)
                assertFalse(chat.id in repo.leftGroups.value)
                instrumentation.runOnMainSync { assertTrue(repo.sendGroup(chat.id, "After rejoin $index")) }
                val after = repo.messages.value.first { it.sourceId == local.profile.card.id && it.payload == "After rejoin $index" }
                assertEquals(exit.count, after.group?.exitCount)
                assertEquals(exit.token, after.group?.exitToken)
                assertNotNull(repo.outgoingPeer(EndToEnd.seal(after, repo.identity.signing(), chat.member(after.destinationId)!!.wrappingKey())))
            } else {
                assertFalse(repo.incomingAllowed(invitation))
                assertFalse(repo.incomingAllowed(message))
                assertEquals(3, GroupStore(Vault(context, "mesh_groups_v1"), local.profile.card.id).exit(chat.id, local.profile.card.id).count)
            }
        }
    }
}
