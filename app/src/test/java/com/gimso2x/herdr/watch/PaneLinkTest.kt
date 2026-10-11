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
    fun distinctPaneIdsNeverShareAUri() {
        val ids = listOf("w1:p1", "w1%3Ap1", "w1:p1/", "W1:p1", "w1:p1 ", "")
        assertEquals(ids.size, ids.map { PaneLink.uri(it) }.toSet().size)
    }
}
