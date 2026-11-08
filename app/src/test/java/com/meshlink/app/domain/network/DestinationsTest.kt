package com.meshlink.app.domain.network
import com.meshlink.app.domain.models.Node
import com.meshlink.app.domain.routing.AodvRoute
import org.junit.Assert.*
import org.junit.Test

class DestinationsTest {
    @Test fun everyoneIncludesUnverifiedRemoteParticipantsAndExcludesBlockedOrAbsentPeople() {
        val options = listOf(DestinationOption("a","A","Direct",verified = true),
            DestinationOption("b","B","Remote"), DestinationOption("c","C","Blocked",canMessage = false, blocked = true),
            DestinationOption("d","D","Previous participant"))
        assertEquals(setOf("a","b"),Destinations.everyone(options,setOf("a","b","c")))
    }
    @Test fun excludesLocalDeduplicatesAndKeepsDuplicateNamesAsSeparateIds() {
        val list=Destinations.list("local",listOf(Node("local"),Node("p","Same")),listOf(Node("p","Same"),Node("q","Same")),emptyList(),1000)
        assertEquals(listOf("p","q"),list.map { it.id });assertTrue(list.first().status.contains("Directly"))
    }
    @Test fun updatesReachabilityAndSelectionAcrossJoinLeaveExpiry() {
        val node=Node("remote","Phone",lastSeenTimestamp=0)
        val route=AodvRoute("remote","hop",3,1,10_000)
        val available=Destinations.list("self",emptyList(),listOf(node),listOf(route),1000)
        assertEquals("Route available (3 hops)",available.single().status)
        assertEquals("remote",Destinations.retainedSelection("remote",available))
        val stale=Destinations.list("self",emptyList(),listOf(node),listOf(route),100_000)
        assertTrue(stale.single().status.contains("Stale"))
        assertEquals("",Destinations.retainedSelection("remote",emptyList()))
    }
    @Test fun directConnectionOverridesOldAnnouncement() {
        val list=Destinations.list("local",listOf(Node("p",lastSeenTimestamp=0)),emptyList(),emptyList(),999_999)
        assertEquals("Directly connected",list.single().status)
    }
    @Test fun multipleRecipientsToggleIndependentlyEvenWithSameName() {
        val options = listOf(DestinationOption("a", "Phone", "Direct"), DestinationOption("b", "Phone", "Direct"))
        val first = Destinations.toggleSelection(emptySet(), "a", options)
        val both = Destinations.toggleSelection(first, "b", options)
        assertEquals(setOf("a", "b"), both)
        assertEquals(setOf("b"), Destinations.toggleSelection(both, "a", options))
        assertEquals(both, Destinations.toggleSelection(both, "unknown", options))
    }
    @Test fun discoveryRefreshKeepsExistingChoicesWithoutSelectingNewPeers() {
        val options = listOf(DestinationOption("b", "B", "Direct"), DestinationOption("c", "C", "Direct"))
        assertEquals(setOf("b"), Destinations.retainedSelections(setOf("a", "b"), options))
        assertTrue(Destinations.retainedSelections(emptySet(), options).isEmpty())
        assertTrue(Destinations.retainedSelections(setOf("a"), emptyList()).isEmpty())
    }
}
