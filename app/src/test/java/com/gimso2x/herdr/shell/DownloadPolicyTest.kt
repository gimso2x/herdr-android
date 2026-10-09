package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    private val base = "https://herdr.example.com"

    /** Stand-in for URLUtil.guessFileName: honours filename= verbatim (the worst case), else last path segment. */
    private val guess: (String, String?, String?) -> String = { url, cd, _ ->
        val fromCd = cd?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]*)"?""").find(it)?.groupValues?.get(1) }
        fromCd?.takeIf { it.isNotEmpty() }
            ?: url.substringBefore('?').substringAfterLast('/').ifEmpty { "downloadfile.bin" }
    }

    private fun decide(url: String?, cd: String? = null, mime: String? = null, baseUrl: String = base) =
        DownloadPolicy.decide(url, cd, mime, baseUrl, guess)

    @Test
    fun baseHostDownloadIsEnqueued() {
        val url = "https://herdr.example.com/api/fs/file?path=%2Fa%2Freport.pdf&download=1"
        assertEquals(
            DownloadDecision.Enqueue(url, "file", "application/pdf"),
            decide(url, null, "application/pdf"),
        )
        assertEquals(
            DownloadDecision.Enqueue(
                "https://herdr.example.com/x/y",
                "report.pdf",
                "application/pdf",
            ),
            decide("https://herdr.example.com/x/y", "attachment; filename=\"report.pdf\"", "Application/PDF; charset=binary"),
        )
    }

    @Test
    fun foreignHostGoesExternalNeverInApp() {
        val url = "https://evil.example/herdr.example.com/file.zip"
        assertEquals(DownloadDecision.External(url), decide(url, null, "application/zip"))
        val lookalike = "https://herdr.example.com.evil.example/f"
        assertEquals(DownloadDecision.External(lookalike), decide(lookalike))
        val access = "https://your-team.cloudflareaccess.com/f"
        assertEquals(DownloadDecision.External(access), decide(access))
        val userinfo = "https://herdr.example.com@evil.example/f"
        assertEquals(DownloadDecision.External(userinfo), decide(userinfo))
        val withUser = "https://user:pw@herdr.example.com/f"
        assertEquals(DownloadDecision.External(withUser), decide(withUser))
    }

    @Test
    fun httpForHerdrHostIsNotDownloadedInApp() {
        val url = "http://herdr.example.com/api/fs/file?download=1"
        val decision = decide(url)
        assertFalse(decision is DownloadDecision.Enqueue)
        assertEquals(DownloadDecision.External(url), decision)
        assertFalse(decide("https://herdr.example.com:8443/f") is DownloadDecision.Enqueue)
    }

    @Test
    fun nonWebSchemesAreBlocked() {
        for (url in listOf(
            "blob:https://herdr.example.com/1234",
            "data:text/plain;base64,AAAA",
            "file:///sdcard/x",
            "javascript:alert(1)",
            "content://x/y",
            "intent://x#Intent;end",
            "ftp://herdr.example.com/f",
        )) {
            assertEquals(url, DownloadDecision.Block, decide(url))
        }
    }

    @Test
    fun malformedUrlIsBlockedOrExternalNeverEnqueued() {
        for (url in listOf(null, "", "   ", "not a url", "https://", "http://[::1")) {
            assertFalse(url, decide(url) is DownloadDecision.Enqueue)
        }
        assertEquals(DownloadDecision.Block, decide(null))
        assertEquals(DownloadDecision.Block, decide(""))
        assertEquals(DownloadDecision.Block, decide("   "))
    }

    @Test
    fun nullOrBlankMimeFallsBackToOctetStream() {
        val url = "https://herdr.example.com/f.bin"
        for (mime in listOf(null, "", "  ", "garbage", "/", "a/", "text/ plain", ";charset=utf-8")) {
            val d = decide(url, null, mime) as DownloadDecision.Enqueue
            assertEquals(mime, DownloadPolicy.FALLBACK_MIME, d.mimeType)
        }
    }

    @Test
    fun mimeIsNormalized() {
        assertEquals("text/plain", DownloadPolicy.normalizeMime(" Text/Plain ; charset=UTF-8"))
        assertEquals("image/svg+xml", DownloadPolicy.normalizeMime("image/svg+xml"))
    }

    @Test
    fun urlWithoutFilenameGetsAName() {
        val d = decide("https://herdr.example.com/", null, "text/plain") as DownloadDecision.Enqueue
        assertTrue(d.fileName.isNotBlank())
        assertEquals("downloadfile.bin", d.fileName)
        // a guesser that returns nothing useful still yields a safe fallback
        val blank = DownloadPolicy.decide("https://herdr.example.com/", null, null, base) { _, _, _ -> "" }
        assertEquals(DownloadPolicy.FALLBACK_NAME, (blank as DownloadDecision.Enqueue).fileName)
        val boom = DownloadPolicy.decide("https://herdr.example.com/", null, null, base) { _, _, _ -> error("x") }
        assertEquals(DownloadPolicy.FALLBACK_NAME, (boom as DownloadDecision.Enqueue).fileName)
    }

    @Test
    fun pathTraversalInContentDispositionIsSanitized() {
        val url = "https://herdr.example.com/api/fs/file"
        val cases = mapOf(
            "attachment; filename=\"../../x\"" to "x",
            "attachment; filename=\"..\\..\\x.txt\"" to "x.txt",
            "attachment; filename=\"/etc/passwd\"" to "passwd",
            "attachment; filename=\"a/../../b.sh\"" to "b.sh",
            "attachment; filename=\"..\"" to DownloadPolicy.FALLBACK_NAME,
            "attachment; filename=\"../\"" to DownloadPolicy.FALLBACK_NAME,
            "attachment; filename=\".hidden\"" to "hidden",
        )
        for ((cd, expected) in cases) {
            val d = decide(url, cd, "text/plain") as DownloadDecision.Enqueue
            assertEquals(cd, expected, d.fileName)
            assertFalse(cd, d.fileName.contains('/') || d.fileName.contains('\\') || d.fileName.startsWith("."))
        }
    }

    @Test
    fun sanitizeFileNameHandlesHostileNames() {
        assertEquals("x", DownloadPolicy.sanitizeFileName("../../x"))
        assertEquals("a_b_c.txt", DownloadPolicy.sanitizeFileName("a:b*c.txt"))
        assertEquals("a_b.txt", DownloadPolicy.sanitizeFileName("a\u0001b.txt"))
        assertEquals("a_b", DownloadPolicy.sanitizeFileName("a\nb"))
        assertEquals(DownloadPolicy.FALLBACK_NAME, DownloadPolicy.sanitizeFileName(null))
        assertEquals(DownloadPolicy.FALLBACK_NAME, DownloadPolicy.sanitizeFileName("   "))
        assertEquals(DownloadPolicy.FALLBACK_NAME, DownloadPolicy.sanitizeFileName("..."))
        assertEquals("x.bin", DownloadPolicy.sanitizeFileName("x.bin."))
        val long = DownloadPolicy.sanitizeFileName("a".repeat(500) + ".txt")
        assertTrue(long.length <= 120)
        assertTrue(long.endsWith(".txt"))
    }

    @Test
    fun debugFixtureBaseIsHonoured() {
        val fixture = "http://127.0.0.1:41234"
        val url = "http://127.0.0.1:41234/api/fs/file?download=1"
        assertTrue(decide(url, null, "text/plain", fixture) is DownloadDecision.Enqueue)
        assertEquals(DownloadDecision.External("http://127.0.0.1:9/f"), decide("http://127.0.0.1:9/f", null, null, fixture))
        // real host is foreign while the override is active
        assertTrue(decide("https://herdr.example.com/f", null, null, fixture) is DownloadDecision.External)
    }
}
