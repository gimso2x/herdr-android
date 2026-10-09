package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val policy = UrlPolicy()

    private fun main(url: String) = policy.decide(url, isMainFrame = true)

    private fun sub(url: String) = policy.decide(url, isMainFrame = false)

    @Test
    fun herdrHostIsInApp() {
        assertEquals(UrlDecision.InApp, main("https://herdr.example.com"))
        assertEquals(UrlDecision.InApp, main("https://herdr.example.com/"))
        assertEquals(UrlDecision.InApp, main("https://herdr.example.com/?machine=local&pane=w1V%3Ap1&view=chat"))
        assertEquals(UrlDecision.InApp, main("https://herdr.example.com:443/x#frag"))
        assertEquals(UrlDecision.InApp, sub("https://herdr.example.com/ws"))
    }

    @Test
    fun hostAndSchemeAreCaseInsensitive() {
        assertEquals(UrlDecision.InApp, main("HTTPS://HERDR.EXAMPLE.COM/Path"))
        assertEquals(UrlDecision.InApp, main("https://Herdr.Example.Com/"))
        assertEquals(UrlDecision.RefreshSession, main("https://YOUR-TEAM.CloudflareAccess.com/cdn-cgi/access/login"))
    }

    @Test
    fun accessMainFrameRefreshesSession() {
        val url = "https://your-team.cloudflareaccess.com/cdn-cgi/access/login/herdr.example.com?kid=abc"
        assertEquals(UrlDecision.RefreshSession, main(url))
    }

    @Test
    fun accessSubframeIsBlockedNotRefreshed() {
        assertEquals(UrlDecision.Block, sub("https://your-team.cloudflareaccess.com/x"))
    }

    @Test
    fun otherWebHostsAreExternalInMainFrameAndBlockedInSubframes() {
        assertEquals(UrlDecision.External("https://example.com/a"), main("https://example.com/a"))
        assertEquals(UrlDecision.External("http://example.com"), main("http://example.com"))
        assertEquals(UrlDecision.Block, sub("https://example.com/a"))
    }

    @Test
    fun lookAlikeHostsAreNeverInApp() {
        val urls = listOf(
            "https://herdr.example.com.evil.com/",
            "https://evil.herdr.example.com.evil.com/",
            "https://evil.herdr.example.com/",
            "https://herdr.example.com.evil.com:443/",
            "https://xherdr.example.com/",
            "https://herdr.example.com./",
            "https://herdr-example.com/",
            "https://example.com/",
            "https://herdr.example.com:8443/",
            "http://herdr.example.com/",
            "https://your-team.cloudflareaccess.com.evil.com/",
            "https://evil.your-team.cloudflareaccess.com/",
        )
        for (url in urls) {
            val decision = main(url)
            assertTrue("$url -> $decision", decision is UrlDecision.External)
        }
    }

    @Test
    fun userinfoTricksAreNotInApp() {
        val urls = listOf(
            "https://herdr.example.com@evil.com",
            "https://herdr.example.com@evil.com/",
            "https://herdr.example.com:pw@evil.com/",
            "https://evil.com@herdr.example.com/",
            "https://your-team.cloudflareaccess.com@evil.com/",
        )
        for (url in urls) {
            assertTrue("$url", main(url) is UrlDecision.External)
            assertEquals("$url", UrlDecision.Block, sub(url))
        }
        assertEquals(UrlDecision.External("https://herdr.example.com@evil.com"), main("https://herdr.example.com@evil.com"))
    }

    @Test
    fun dangerousSchemesAreBlocked() {
        val urls = listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "file:///data/data/com.gimso2x.herdr/shared_prefs/x.xml",
            "content://com.android.contacts/contacts",
            "data:text/html,<script>1</script>",
            "ftp://herdr.example.com/",
            "view-source:https://herdr.example.com/",
            "chrome://crash",
        )
        for (url in urls) {
            assertEquals(url, UrlDecision.Block, main(url))
            assertEquals(url, UrlDecision.Block, sub(url))
        }
    }

    @Test
    fun intentTelMailtoAreExternalInMainFrameOnly() {
        for (url in listOf("intent://scan/#Intent;scheme=zxing;end", "tel:+8210000000", "mailto:me@example.com")) {
            assertEquals(url, UrlDecision.External(url), main(url))
            assertEquals(url, UrlDecision.Block, sub(url))
        }
    }

    @Test
    fun blobIsInAppOnlyForHerdrOrigin() {
        assertEquals(UrlDecision.InApp, main("blob:https://herdr.example.com/1f2e3d4c"))
        assertEquals(UrlDecision.Block, main("blob:https://evil.com/1f2e3d4c"))
        assertEquals(UrlDecision.Block, main("blob:https://herdr.example.com@evil.com/1f2e3d4c"))
        assertEquals(UrlDecision.Block, main("blob:null/1f2e3d4c"))
        assertEquals(UrlDecision.Block, main("blob:file:///x"))
    }

    @Test
    fun aboutBlankIsAllowedOtherAboutIsNot() {
        assertEquals(UrlDecision.InApp, main("about:blank"))
        assertEquals(UrlDecision.Block, main("about:config"))
    }

    @Test
    fun garbageNeverLoadsInApp() {
        val garbage = listOf(
            "",
            " ",
            "   https://herdr.example.com/",
            "https://herdr.example.com/ x",
            "https://herdr.example.com/\n",
            "https://",
            "http://",
            "https:",
            "https:herdr.example.com",
            "//herdr.example.com/",
            "herdr.example.com",
            "/relative/path",
            "https://[::1",
            "https://herdr%2Eexample.com/",
            "https://herdr.example.com\\@evil.com/",
            "https://evil.com\\.herdr.example.com/",
            "https://herdr_example.com/",
            "%%%",
            "\u0000",
            ":::",
            "https://herdr.example.com:99999999999/",
            "https://herdr.example.com:abc/",
        )
        for (url in garbage) {
            assertFalse("$url must not be in-app", main(url) == UrlDecision.InApp)
            assertFalse("$url must not be in-app (subframe)", sub(url) == UrlDecision.InApp)
        }
    }

    @Test
    fun debugOriginIsAdditiveAndExact() {
        val debug = UrlPolicy(debugOrigin = "http://127.0.0.1:41234")
        assertEquals(UrlDecision.InApp, debug.decide("http://127.0.0.1:41234/", true))
        assertEquals(UrlDecision.InApp, debug.decide("https://herdr.example.com/", true))
        assertTrue(debug.decide("http://127.0.0.1:9999/", true) is UrlDecision.External)
        assertTrue(debug.decide("http://127.0.0.1/", true) is UrlDecision.External)
        assertTrue(debug.decide("https://127.0.0.1:41234/", true) is UrlDecision.External)
        assertTrue(debug.decide("http://127.0.0.1.evil.com:41234/", true) is UrlDecision.External)
    }

    @Test
    fun releasePolicyDoesNotAllowLoopback() {
        assertTrue(main("http://127.0.0.1:41234/") is UrlDecision.External)
        assertTrue(UrlPolicy(debugOrigin = "not a url").decide("http://127.0.0.1/", true) is UrlDecision.External)
    }

    @Test
    fun deepLinkEncodesPaneId() {
        assertEquals(
            "https://herdr.example.com/?machine=local&pane=w1V%3Ap1&view=chat",
            DeepLink.paneUrl("https://herdr.example.com", "w1V:p1"),
        )
        assertEquals(
            "https://herdr.example.com/?machine=local&pane=a%20b%26c%3Dd%2Fe&view=chat",
            DeepLink.paneUrl("https://herdr.example.com/", "a b&c=d/e"),
        )
    }

    @Test
    fun deepLinkResultIsInApp() {
        val url = DeepLink.paneUrl("https://herdr.example.com", "w1V:p1&x=https://evil.com")
        assertEquals(UrlDecision.InApp, main(url))
        assertFalse(url.substringAfter("pane=").substringBefore("&view").contains("/"))
    }
}
