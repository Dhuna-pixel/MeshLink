package com.meshlink.app.domain.notifications

import com.meshlink.app.data.transport.framing.encode

import com.meshlink.app.data.preferences.*
import com.meshlink.app.domain.models.MessagePacket
import com.meshlink.app.domain.routing.AodvEngine
import com.meshlink.app.domain.routing.RouteControl
import com.meshlink.app.data.transport.framing.MeshLinkFrame
import com.meshlink.app.utils.JsonSerializer
import org.junit.Assert.*
import org.junit.Test

class MessageNotificationsTest {
    class Memory : PreferenceStorage {
        val values = mutableMapOf<String,String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
    }
    private val storage = Memory()
    private val prefs = AppPreferencesStore(storage)
    private var allowed = true
    private val posted = mutableListOf<MessageNotification>()
    private val inbox = ReceivedMessageStore(storage)
    private fun service() = DeliveredMessageNotifications({"B"},{prefs.state.value},{allowed},inbox,{null},posted::add)
    private fun packet() = MessagePacket("id","A","Phone 2","B",payload="hello",hopCount=1,routeHistory=listOf("A","B"))
    @Test fun validDeliveryAndFriendlyName() { service().onDelivered(packet()); assertEquals("Phone 2",posted.single().sender); assertEquals("hello",posted.single().text) }
    @Test fun multiHopMatchesDirect() { service().onDelivered(packet().copy(hopCount=3,routeHistory=listOf("A","C","D","B"))); assertEquals(1,posted.size) }
    @Test fun relayAndAckAndControlsExcluded() {
        val s=service(); s.onDelivered(packet().copy(destinationId="C")); s.onDelivered(packet().copy(ackFor="ack"))
        listOf("RREQ","RREP","RERR","HELLO","ANNOUNCE").forEach { s.onDelivered(packet().copy(control=RouteControl(it,"A","B"))) }
        assertTrue(posted.isEmpty())
    }
    @Test fun duplicateAcrossRecreationExcluded() { service().onDelivered(packet()); service().onDelivered(packet()); assertEquals(1,posted.size); assertFalse(ReceivedMessageStore(storage).remember("B",packet())) }
    @Test fun deniedPermissionDoesNotThrowOrReplayLater() { allowed=false; service().onDelivered(packet()); allowed=true; service().onDelivered(packet()); assertTrue(posted.isEmpty()); assertNotNull(inbox.find("B",NotificationTarget("id","A"))) }
    @Test fun disabledPreferenceRespected() { prefs.setNotifications(false); service().onDelivered(packet()); assertTrue(posted.isEmpty()) }
    @Test fun privacyPreferenceRespected() { prefs.setPrivatePreview(false); service().onDelivered(packet()); assertFalse(posted.single().privatePreview) }
    @Test fun safeFallbackAndBoundedPreview() { service().onDelivered(packet().copy(sourceName="\n\u202e",payload="x".repeat(5000))); assertEquals("Node A",posted.single().sender); assertEquals(4000,posted.single().text.length) }
    @Test fun tapTargetRestoresCorrectMessageAndLocalIdentity() { service().onDelivered(packet()); val restored=ReceivedMessageStore(storage); assertEquals(packet().payload,restored.find("B",posted.single().target)?.payload); assertNull(restored.find("C",posted.single().target)); assertNull(restored.find("B",NotificationTarget("id","other"))) }
    @Test fun postingFailureDoesNotEscapeDelivery() { DeliveredMessageNotifications({"B"},{prefs.state.value},{true},inbox,{null},{error("OS unavailable")}).onDelivered(packet()); assertNotNull(inbox.find("B",NotificationTarget("id","A"))) }
    private fun receiveThroughFrame(corrupt: Boolean=false, ttl: Int=5) {
        val engine=AodvEngine("B","B",{_,_->true},service()::onDelivered,{_,_->},{_,_->},{},{1000L})
        engine.neighborUp("A")
        val incoming=packet().copy(hopCount=0,routeHistory=listOf("A"),ttl=ttl)
        val decoded=MeshLinkFrame.decode(MeshLinkFrame.encode(1,JsonSerializer.packetToBytes(incoming),corrupt))
        if(decoded is MeshLinkFrame.FrameResult.Success) engine.receive(JsonSerializer.bytesToPacket(decoded.payload)!!,"A")
    }
    @Test fun validatedFrameNotifies() { receiveThroughFrame(); assertEquals(1,posted.size) }
    @Test fun crcInvalidNeverNotifies() { receiveThroughFrame(corrupt=true); assertTrue(posted.isEmpty()) }
    @Test fun expiredIncomingTtlNeverNotifies() { receiveThroughFrame(ttl=0); assertTrue(posted.isEmpty()) }
    @Test fun lastValidHopTtlZeroNotifies() { receiveThroughFrame(ttl=1); assertEquals(1,posted.size) }
    @Test fun permissionDenialPreservesEngineDelivery() { allowed=false; receiveThroughFrame(); assertNotNull(inbox.find("B",NotificationTarget("id","A"))); assertTrue(posted.isEmpty()) }
    @Test fun themeDefaultsAndSystemChanges() { assertEquals(ThemeMode.SYSTEM,prefs.state.value.theme); assertFalse(prefs.state.value.theme.isDark(false)); assertTrue(prefs.state.value.theme.isDark(true)) }
    @Test fun allThemeSelectionsPersistImmediately() { ThemeMode.entries.forEach { prefs.setTheme(it); assertEquals(it,prefs.state.value.theme); assertEquals(it,AppPreferencesStore(storage).state.value.theme) } }
    @Test fun explicitThemesIgnoreSystemAppearance() { assertFalse(ThemeMode.LIGHT.isDark(true)); assertFalse(ThemeMode.LIGHT.isDark(false)); assertTrue(ThemeMode.DARK.isDark(false)); assertTrue(ThemeMode.DARK.isDark(true)) }
    @Test fun permissionDenialAndOtherPreferencesPersistWithTheme() { prefs.markPermissionAsked(); prefs.setNotifications(false); prefs.setPrivatePreview(false); prefs.setTheme(ThemeMode.DARK); val restored=AppPreferencesStore(storage).state.value; assertTrue(restored.notificationPermissionAsked); assertFalse(restored.notificationsEnabled); assertFalse(restored.hideLockScreenPreview) }
    @Test fun corruptPreferenceFallsBackToSystem() { storage.put("theme","unknown"); assertEquals(ThemeMode.SYSTEM,AppPreferencesStore(storage).state.value.theme) }
}
