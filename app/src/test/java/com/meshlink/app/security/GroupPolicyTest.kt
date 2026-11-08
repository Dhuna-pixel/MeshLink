package com.meshlink.app.security

import com.meshlink.app.data.preferences.PreferenceStorage
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupPolicyTest {
    private class Memory : PreferenceStorage {
        val data = mutableMapOf<String,String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String) { data[key] = value }
    }
    private val chat = GroupChat(UUID.randomUUID().toString(), "Shared group", "friend", emptyList())
    private fun context(kind: String = "message", exit: GroupExit = GroupExit()) =
        GroupContext(chat, UUID.randomUUID().toString(), kind, exit.count, exit.token)

    @Test fun sharedGroupAllowsBlockedMemberButNotBlockedInviterOrCreator() {
        val store = GroupStore(Memory(), "me")
        val blocked = setOf("blocked")
        assertFalse(store.canReceive(context("created"), "blocked", blocked))
        assertFalse(store.canReceive(context().copy(chat = chat.copy(creator = "blocked")), "friend", blocked))
        assertTrue(store.canReceive(context("created"), "friend", blocked))
        store.remember(chat)
        assertTrue(store.canReceive(context(), "blocked", blocked))
        assertTrue(store.canReceive(context(), "unverified", blocked))
        store.leave(chat.id)
        val exit = store.exit(chat.id, "me")
        assertFalse(store.canReceive(context("invite", exit), "blocked", blocked))
        assertTrue(store.canReceive(context("invite", exit), "friend", blocked))
    }

    @Test fun threeExitsPersistAndOldInvitationsCannotRejoin() {
        val storage = Memory()
        var store = GroupStore(storage, "me")
        store.remember(chat)
        var previous: GroupContext? = null
        repeat(3) { index ->
            val exit = store.leave(chat.id)
            assertEquals(index + 1, exit.count)
            assertEquals(exit, store.leave(chat.id)) // double tap is not a second exit
            store = GroupStore(storage, "me")
            assertFalse(store.canReceive(context(), "friend", emptySet()))
            previous?.let { assertFalse(store.canReceive(it, "friend", emptySet())) }
            val invite = context("invite", exit)
            if (index < 2) {
                assertTrue(store.canReceive(invite, "friend", emptySet()))
                store.applyEvent(invite, "friend")
                assertFalse(chat.id in store.left())
                assertTrue(store.canReceive(invite, "friend", emptySet())) // duplicate may be ACKed
                previous = invite
            } else {
                assertFalse(store.canReceive(invite, "friend", emptySet()))
                assertTrue(runCatching { store.applyEvent(invite, "friend") }.isFailure)
            }
        }
        store.hide(chat.id)
        assertTrue(chat.id in GroupStore(storage,"me").hidden())
        assertEquals(3, GroupStore(storage,"me").exit(chat.id,"me").count)
        assertTrue(chat.id in GroupStore(storage,"me").left())
    }

    @Test fun exitAndJoinEventsAreIdempotentAndCannotUndoThirdExit() {
        val store = GroupStore(Memory(), "me")
        store.remember(chat)
        val exit = GroupExit(1, UUID.randomUUID().toString(), true)
        val notice = context("exit", exit)
        store.applyEvent(notice, "friend")
        store.applyEvent(notice, "friend")
        assertEquals(1, store.exit(chat.id,"friend").count)
        assertFalse(store.canReceive(context(), "friend", emptySet()))
        store.applyEvent(context("joined", exit), "friend")
        store.applyEvent(notice, "friend") // delayed duplicate exit must not remove a rejoined member
        assertFalse(store.exit(chat.id,"friend").left)
        store.applyEvent(context("exit", GroupExit(3, UUID.randomUUID().toString(), true)), "friend")
        store.applyEvent(context("joined", exit), "friend")
        assertTrue(store.exit(chat.id,"friend").left)
        assertFalse(store.canReceive(context("joined", exit), "friend", emptySet()))
    }

    @Test fun messageFromRejoinedMemberRepairsMissedNoticeButCannotUndoLaterExit() {
        val store = GroupStore(Memory(), "me")
        store.remember(chat)
        val exit = GroupExit(1, UUID.randomUUID().toString(), true)
        store.applyEvent(context("exit", exit), "friend")
        assertFalse(store.canReceive(context(), "friend", emptySet()))
        val afterRejoin = context("message", exit)
        assertTrue(store.canReceive(afterRejoin, "friend", emptySet()))
        store.applyEvent(afterRejoin, "friend")
        assertFalse(store.exit(chat.id, "friend").left)
        store.applyEvent(context("exit", GroupExit(2, UUID.randomUUID().toString(), true)), "friend")
        assertFalse(store.canReceive(afterRejoin, "friend", emptySet()))
    }

    @Test fun readdDoesNotDependOnRejoinNoticesMissedWhileOffline() {
        val store = GroupStore(Memory(), "me")
        store.remember(chat)
        store.applyEvent(context("exit", GroupExit(1, UUID.randomUUID().toString(), true)), "friend")
        val exit = store.leave(chat.id)
        // The friend rejoined while this phone was out of the group; its signed fresh invite is valid.
        assertTrue(store.canReceive(context("invite", exit), "friend", emptySet()))
    }

    @Test fun legacyLeftStateBecomesOnePersistedExit() {
        val storage = Memory()
        storage.put("me", "{\"left\":[\"${chat.id}\"]}")
        val first = GroupStore(storage,"me").exit(chat.id,"me")
        assertEquals(1, first.count)
        assertTrue(first.token.isNotBlank())
        assertEquals(first, GroupStore(storage,"me").exit(chat.id,"me"))
    }
}
