package com.gimso2x.herdr.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PaneLinkTest {
    @Test
    fun collidingHashCodePairGetsDistinctIntentData() {
        val first = "w10P:p1"
        val second = "w111:p1"
        // The old request code: both panes shared one PendingIntent and the later one rewrote the extra.
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(PaneLink.uri(first), PaneLink.uri(second))
        assertEquals("herdr://pane/w10P%3Ap1", PaneLink.uri(first))
        assertEquals("herdr://pane/w111%3Ap1", PaneLink.uri(second))
    }

    @Test
    fun uriCarriesTheWholePaneIdPercentEncoded() {
        assertEquals("herdr://pane/w1%3Ap1", PaneLink.uri("w1:p1"))
        assertEquals("herdr://pane/a%2Fb%3Fc%23d%20e%25", PaneLink.uri("a/b?c#d e%"))
        assertEquals("herdr://pane/%ED%95%9C", PaneLink.uri("한"))
        assertEquals("herdr://pane/_-!.~'()*", PaneLink.uri("_-!.~'()*"))
    }

    @Test
    fun localUriIsTheSameAsBeforeMachinesExisted() {
        assertEquals(PaneLink.uri("w1:p1"), PaneLink.uri("w1:p1", "local"))
        assertEquals("herdr://pane/w1%3Ap1", PaneLink.uri("w1:p1", "local"))
        assertEquals("local", PaneLink.machineOf(PaneLink.uri("w1:p1")))
    }

    @Test
    fun remoteUriRoundTripsMachineAndIsDistinctPerMachine() {
        val uri = PaneLink.uri("w1:p1", "fixture-m1")
        assertEquals("herdr://pane/w1%3Ap1?machine=fixture-m1", uri)
        assertEquals("fixture-m1", PaneLink.machineOf(uri))
        assertNotEquals(uri, PaneLink.uri("w1:p1", "fixture-m2"))
        assertNotEquals(uri, PaneLink.uri("w1:p1"))
    }

    @Test
    fun malformedOrForeignLinksFallBackToLocal() {
        assertEquals("local", PaneLink.machineOf(null))
        assertEquals("local", PaneLink.machineOf("https://pane/x?machine=m1"))
        assertEquals("local", PaneLink.machineOf("herdr://pane/p1?machine=a%2Fb"))
        assertEquals("local", PaneLink.machineOf("herdr://pane/p1?machine="))
        assertEquals("local", PaneLink.machineOf("herdr://pane/p1?machine=m1&x=1"))
        assertEquals("local", PaneLink.machineOf("herdr://pane/p1?other=m1"))
    }

    @Test
    fun alertKeyDiffersPerMachineAndLocalKeepsTheBareId() {
        assertEquals("w1:p1", PaneLink.alertKey("w1:p1"))
        assertEquals("w1:p1", PaneLink.alertKey("w1:p1", "local"))
        val keys = listOf(PaneLink.alertKey("w1:p1"), PaneLink.alertKey("w1:p1", "m1"),
            PaneLink.alertKey("w1:p1", "m2"), PaneLink.alertKey("p1", "m1"))
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(PaneLink.alertKey("m1\u001Fp1"), PaneLink.alertKey("p1", "m1"))
    }

    @Test
    fun distinctPaneIdsNeverShareAUri() {
        val ids = listOf("w1:p1", "w1%3Ap1", "w1:p1/", "W1:p1", "w1:p1 ", "")
        assertEquals(ids.size, ids.map { PaneLink.uri(it) }.toSet().size)
    }
}
