package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val policy = UrlPolicy("https://herdr.example.com")

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
        assertEquals(UrlDecision.InApp, main("HTTPS://HERDR.example.com/Path"))
        assertEquals(UrlDecision.InApp, main("https://Herdr.example.com/"))
        assertEquals(UrlDecision.RefreshSession, main("https://TEAM.CloudflareAccess.com/cdn-cgi/access/login"))
    }

    @Test
    fun accessMainFrameRefreshesSession() {
        val url = "https://team.cloudflareaccess.com/cdn-cgi/access/login/herdr.example.com?kid=abc"
        assertEquals(UrlDecision.RefreshSession, main(url))
    }

    @Test
    fun accessSubframeIsBlockedNotRefreshed() {
        assertEquals(UrlDecision.Block, sub("https://team.cloudflareaccess.com/x"))
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
            "https://team.cloudflareaccess.com.evil.com/",
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
            "https://team.cloudflareaccess.com@evil.com/",
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
    fun fixtureBaseIsExact() {
        val debug = UrlPolicy("http://127.0.0.1:41234")
        assertEquals(UrlDecision.InApp, debug.decide("http://127.0.0.1:41234/", true))
        assertTrue(debug.decide("https://herdr.example.com/", true) is UrlDecision.External)
        assertTrue(debug.decide("http://127.0.0.1:9999/", true) is UrlDecision.External)
        assertTrue(debug.decide("http://127.0.0.1/", true) is UrlDecision.External)
        assertTrue(debug.decide("https://127.0.0.1:41234/", true) is UrlDecision.External)
        assertTrue(debug.decide("http://127.0.0.1.evil.com:41234/", true) is UrlDecision.External)
    }
    @Test
    fun nothingIsInAppWithoutAConfiguredServer() {
        val none = UrlPolicy(null)
        assertTrue(none.decide("https://herdr.example.com/", true) is UrlDecision.External)
        assertEquals(UrlDecision.Block, none.decide("https://herdr.example.com/", false))
        assertTrue(none.decide("http://127.0.0.1/", true) is UrlDecision.External)
    }

    @Test
    fun changedAddressTakesEffectAtOnce() {
        var base: String? = "https://one.example.net"
        val live = UrlPolicy { base }
        assertEquals(UrlDecision.InApp, live.decide("https://one.example.net/x", true))
        base = "https://two.example.net"
        assertTrue(live.decide("https://one.example.net/x", true) is UrlDecision.External)
        assertEquals(UrlDecision.InApp, live.decide("https://two.example.net/x", true))
    }

    @Test
    fun anyAccessTeamHostIsALoginRedirect() {
        for (host in listOf("team.cloudflareaccess.com", "other-team.cloudflareaccess.com", "a.b.cloudflareaccess.com")) {
            assertEquals(host, UrlDecision.RefreshSession, main("https://$host/cdn-cgi/access/login/herdr.example.com?kid=1"))
            assertEquals(host, UrlDecision.Block, sub("https://$host/x"))
        }
        for (host in listOf("cloudflareaccess.com", "evilcloudflareaccess.com", "team.cloudflareaccess.com.evil.com")) {
            assertTrue(host, main("https://$host/").let { it is UrlDecision.External })
        }
        assertTrue(main("http://team.cloudflareaccess.com/") is UrlDecision.External)
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

    @Test
    fun charactersChromiumLeavesUnescapedStayInApp() {
        val urls = listOf(
            "https://herdr.example.com/?q=a[1]|b^c",
            "https://herdr.example.com/p[1]/x|y^z?k={v}&t=`b`",
            "https://herdr.example.com:443/?q=a[1]|b^c#f[1]",
        )
        for (url in urls) {
            assertEquals(url, UrlDecision.InApp, main(url))
            assertEquals(url, UrlDecision.InApp, sub(url))
            assertTrue(url, policy.isInApp(url))
        }
        assertEquals(UrlDecision.InApp, main("blob:https://herdr.example.com/1f2e?q=a[1]|b^c"))
    }

    @Test
    fun unescapedCharactersDoNotWeakenOriginAndUserinfoChecks() {
        assertEquals(
            UrlDecision.External("https://example.com/?q=a[1]|b^c"),
            main("https://example.com/?q=a[1]|b^c"),
        )
        assertEquals(UrlDecision.Block, sub("https://example.com/?q=a[1]|b^c"))
        assertEquals(
            UrlDecision.External("https://herdr.example.com@evil.com/?q=a[1]|b^c"),
            main("https://herdr.example.com@evil.com/?q=a[1]|b^c"),
        )
        assertEquals(UrlDecision.Block, sub("https://evil.com@herdr.example.com/?q=a[1]|b^c"))
        assertEquals(UrlDecision.RefreshSession, main("https://team.cloudflareaccess.com/login?x=a[1]|b^c"))
        assertEquals(UrlDecision.Block, main("https://herdr.example.com/ ?q=a[1]|b^c"))
        assertTrue(main("https://herdr.example.com:8443/?q=a[1]|b^c") is UrlDecision.External)
    }
}
