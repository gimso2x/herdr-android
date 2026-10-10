package com.gimso2x.herdr.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun httpsHostIsNormalised() {
        assertEquals("https://herdr.example.com", ServerAddress.parse("https://herdr.example.com"))
        assertEquals("https://herdr.example.com", ServerAddress.parse("https://herdr.example.com/"))
        assertEquals("https://herdr.example.com", ServerAddress.parse("  HTTPS://Herdr.Example.COM/  "))
        assertEquals("https://herdr.example.com", ServerAddress.parse("https://herdr.example.com:443"))
        assertEquals("https://herdr.example.com:8443", ServerAddress.parse("https://herdr.example.com:8443/"))
        assertEquals("https://box.tail1234.ts.net", ServerAddress.parse("https://box.tail1234.ts.net"))
    }

    @Test
    fun otherSchemesAreRefused() {
        for (input in listOf(
            "http://herdr.example.com",
            "ftp://herdr.example.com",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "herdr.example.com",
            "//herdr.example.com",
            "https:herdr.example.com",
            "",
            "   ",
        )) assertNull(input, ServerAddress.parse(input))
    }

    @Test
    fun hostIsRequiredAndNothingElseIsAllowed() {
        for (input in listOf(
            "https://",
            "https:///",
            "https:///path",
            "https://:443",
            "https://user@herdr.example.com",
            "https://user:pw@herdr.example.com",
            "https://herdr.example.com@evil.example",
            "https://herdr.example.com/path",
            "https://herdr.example.com/path/",
            "https://herdr.example.com//",
            "https://herdr.example.com?x=1",
            "https://herdr.example.com/?x=1",
            "https://herdr.example.com?",
            "https://herdr.example.com#frag",
            "https://herdr.example.com/#",
            "https://herdr.example.com:",
            "https://herdr.example.com:0",
            "https://herdr.example.com:99999",
            "https://herdr.example.com:abc",
            "https://herdr .example.com",
            "https://herdr.example\n.com",
            "https://herdr.example.com\\evil",
            "https://" + "a".repeat(300) + ".example.com",
        )) assertNull(input, ServerAddress.parse(input))
    }

    @Test
    fun loopbackHttpOnlyWhenAllowed() {
        assertNull(ServerAddress.parse("http://127.0.0.1:4321"))
        assertNull(ServerAddress.parse("http://localhost"))
        assertEquals("http://127.0.0.1:4321", ServerAddress.parse("http://127.0.0.1:4321/", allowLoopback = true))
        assertEquals("http://localhost", ServerAddress.parse("http://localhost", allowLoopback = true))
        assertEquals("https://localhost:3000", ServerAddress.parse("https://localhost:3000", allowLoopback = true))
        assertNull(ServerAddress.parse("http://10.0.2.2:8080", allowLoopback = true))
        assertNull(ServerAddress.parse("http://herdr.example.com", allowLoopback = true))
        assertNull(ServerAddress.parse("http://127.0.0.1.evil.com", allowLoopback = true))
    }

    @Test
    fun accessHostsAreTeamSubdomainsOnly() {
        assertTrue(ServerAddress.isAccessHost("team.cloudflareaccess.com"))
        assertTrue(ServerAddress.isAccessHost("Team.CloudflareAccess.com"))
        assertTrue(ServerAddress.isAccessHost("a.b.cloudflareaccess.com"))
        assertFalse(ServerAddress.isAccessHost("cloudflareaccess.com"))
        assertFalse(ServerAddress.isAccessHost(".cloudflareaccess.com"))
        assertFalse(ServerAddress.isAccessHost("evilcloudflareaccess.com"))
        assertFalse(ServerAddress.isAccessHost("team.cloudflareaccess.com.evil.example"))
        assertFalse(ServerAddress.isAccessHost(null))
    }
}
