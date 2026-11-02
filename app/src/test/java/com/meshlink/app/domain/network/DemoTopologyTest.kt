package com.meshlink.app.domain.network
import org.junit.Assert.*
import org.junit.Test

class DemoTopologyTest {
    @Test fun normalModeIsDefaultAndRemovesRestrictions() {
        assertTrue(DemoTopology().permits("unknown"))
        assertTrue(DemoTopology().permitsAdvertisement(byteArrayOf(1)))
        val restricted=DemoTopology(true,setOf("peer"))
        assertFalse(restricted.permits("other"))
        assertTrue(restricted.copy(enabled=false).permits("other"))
    }
    @Test fun allowlistChecksIdentityAndAdvertisement() {
        val policy=DemoTopology(true,setOf("two"))
        assertTrue(policy.permits("two"));assertFalse(policy.permits("TWO"))
        assertTrue(policy.permitsAdvertisement(DemoTopology.identityToken("two")))
        assertFalse(policy.permitsAdvertisement(DemoTopology.identityToken("four")))
    }
    @Test fun chainConsistentAcrossEveryPhoneForbidsShortcuts() {
        val ids=listOf("one","two","three","four")
        val configs=ids.associateWith { DemoTopology.chain(it,ids) }
        ids.forEachIndexed { i,a -> ids.forEachIndexed { j,b ->
            assertEquals(kotlin.math.abs(i-j)==1,configs.getValue(a).permits(b))
        } }
        assertFalse(configs.getValue("one").permits("four"))
        assertFalse(configs.getValue("one").permitsAdvertisement(DemoTopology.identityToken("four")))
    }
    @Test fun emptyEnabledListDeniesAll() { assertFalse(DemoTopology(true).permits("peer"));assertFalse(DemoTopology(true).permitsAdvertisement(DemoTopology.identityToken("peer"))) }
    @Test(expected=IllegalArgumentException::class) fun missingLocalIdentityRejectsChain() { DemoTopology.chain("local",listOf("x","y")) }
    @Test(expected=IllegalArgumentException::class) fun duplicateIdentitiesRejectChain() { DemoTopology.chain("x",listOf("x","x")) }
}
