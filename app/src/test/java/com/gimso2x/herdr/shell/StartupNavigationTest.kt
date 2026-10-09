package com.gimso2x.herdr.shell

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupNavigationTest {
    private val root = "https://herdr.example.com/"
    private val pane = "https://herdr.example.com/?machine=local&pane=p1&view=chat"
    private val savedPane = "https://herdr.example.com/?machine=local&pane=p0&view=chat"

    @Test
    fun freshStartWithoutPaneLoadsRoot() {
        val plan = StartupNavigation.resolve(false, null, null, root)
        assertEquals(StartupPlan(restoreHistory = false, explicitUrl = null, fallbackUrl = root), plan)
    }

    @Test
    fun restoreWithoutPaneKeepsHistoryOnly() {
        val plan = StartupNavigation.resolve(true, null, null, root)
        assertTrue(plan.restoreHistory)
        assertNull(plan.explicitUrl)
    }

    // Finding 1: a pane link arriving before the first load must not be dropped by a restore.
    @Test
    fun paneLinkWinsOverPendingHistoryButHistoryIsStillRestored() {
        val plan = StartupNavigation.resolve(true, null, pane, root)
        assertTrue("history restored so Back works", plan.restoreHistory)
        assertEquals(pane, plan.explicitUrl)
    }

    // Finding 2: a pane saved across recreation before any load is not lost.
    @Test
    fun savedStartUrlIsLoadedWhenNoHistoryExists() {
        val plan = StartupNavigation.resolve(false, savedPane, null, root)
        assertFalse(plan.restoreHistory)
        assertEquals(savedPane, plan.explicitUrl)
    }

    @Test
    fun newerPaneBeatsSavedStartUrl() {
        assertEquals(pane, StartupNavigation.resolve(false, savedPane, pane, root).explicitUrl)
    }

    @Test
    fun staleStateNeverSwallowsPane() {
        // Restore may fail at runtime (foreign page); the explicit URL is still planned either way.
        val plan = StartupNavigation.resolve(true, savedPane, pane, root)
        assertEquals(pane, plan.explicitUrl)
        assertEquals(root, plan.fallbackUrl)
    }
}

class NotificationGateTest {
    @Test
    fun requestsOnceOnApi33WhenNotGrantedAndNotAsked() {
        assertEquals(NotificationAction.REQUEST_THEN_START, NotificationGate.decide(33, granted = false, asked = false))
        assertEquals(NotificationAction.REQUEST_THEN_START, NotificationGate.decide(35, granted = false, asked = false))
    }

    @Test
    fun neverRequestsAgainOnceAsked() {
        assertEquals(NotificationAction.START_WATCHER, NotificationGate.decide(34, granted = false, asked = true))
    }

    @Test
    fun grantedStartsWatcherImmediately() {
        assertEquals(NotificationAction.START_WATCHER, NotificationGate.decide(34, granted = true, asked = false))
    }

    @Test
    fun preTiramisuStartsWatcherImmediately() {
        for (sdk in listOf(26, 30, 32)) {
            assertEquals(NotificationAction.START_WATCHER, NotificationGate.decide(sdk, granted = false, asked = false))
        }
    }
}

class DeepLinkMalformedPaneTest {
    private val base = "https://herdr.example.com"

    private fun assertStaysOnHerdr(paneId: String) {
        val url = DeepLink.paneUrl(base, paneId)
        val uri = URI(url)
        assertEquals(url, "herdr.example.com", uri.host)
        assertEquals(url, "https", uri.scheme)
        assertEquals(url, 3, uri.rawQuery.split("&").size)
        assertNull(url, uri.rawFragment)
        assertNull(url, uri.rawUserInfo)
        assertEquals(UrlDecision.InApp, UrlPolicy().decide(url, true))
    }

    @Test
    fun ampersandHashAndAuthorityCharsAreEncoded() {
        for (id in listOf("a&view=x", "a#frag", "@evil.example", "x/../../y", "p?q=1", "a b", "%00", "\u00e9\n")) {
            assertStaysOnHerdr(id)
        }
        assertEquals(
            "$base/?machine=local&pane=a%26b%23c&view=chat",
            DeepLink.paneUrl(base, "a&b#c"),
        )
    }

    @Test
    fun veryLongIdStaysEncodedOnHerdrHost() {
        assertStaysOnHerdr("&#".repeat(5_000))
    }
}
