package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupPolicyTest {
    private val policy = UrlPolicy("https://herdr.example.com")

    @Test
    fun herdrTargetLoadsInMainWebView() {
        val url = "https://herdr.example.com/?machine=local&pane=p1&view=chat"
        assertEquals(PopupAction.LoadInApp(url), PopupPolicy.route(url, policy))
    }

    @Test
    fun evilHostNeverLoadsInApp() {
        for (url in listOf(
            "https://evil.example/",
            "http://herdr.example.com/",
            "https://herdr.example.com.evil.example/",
            "https://herdr.example.com@evil.example/",
            "https://herdr.example.com:8443/",
        )) {
            assertEquals(url, PopupAction.OpenExternal(url), PopupPolicy.route(url, policy))
        }
    }

    @Test
    fun dangerousSchemesAreIgnored() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/html,x", "content://x/y", "ftp://x/y")) {
            assertEquals(url, PopupAction.Ignore, PopupPolicy.route(url, policy))
        }
    }

    @Test
    fun accessRedirectFromPopupIsIgnored() {
        assertEquals(
            PopupAction.Ignore,
            PopupPolicy.route("https://team.cloudflareaccess.com/cdn-cgi/access/login", policy),
        )
    }

    @Test
    fun blankTargetWaitsForTheRealUrl() {
        for (url in listOf(null, "", "  ", "about:blank")) {
            assertEquals(url, PopupAction.Wait, PopupPolicy.route(url, policy))
        }
    }

    @Test
    fun fixtureBaseFollowsUrlPolicy() {
        val debug = UrlPolicy("http://127.0.0.1:41234")
        assertEquals(
            PopupAction.LoadInApp("http://127.0.0.1:41234/x"),
            PopupPolicy.route("http://127.0.0.1:41234/x", debug),
        )
        assertEquals(PopupAction.OpenExternal("http://127.0.0.1:9/x"), PopupPolicy.route("http://127.0.0.1:9/x", debug))
    }
}
