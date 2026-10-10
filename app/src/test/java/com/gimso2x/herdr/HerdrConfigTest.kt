package com.gimso2x.herdr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HerdrConfigTest {
    @Before
    fun clean() {
        HerdrConfig.applyOverride(null, true)
        HerdrConfig.install { null }
    }

    @After
    fun resetOverride() {
        HerdrConfig.applyOverride(null, true)
        HerdrConfig.install { null }
    }

    @Test
    fun nothingIsConfiguredByDefault() {
        assertNull(HerdrConfig.baseUrlOrNull)
        assertEquals("", HerdrConfig.baseUrl)
        assertFalse(HerdrConfig.isConfigured)
    }

    @Test
    fun storedAddressIsReadOnceFromTheLoader() {
        var reads = 0
        HerdrConfig.install {
            reads++
            "https://herdr.example.com"
        }
        assertEquals("https://herdr.example.com", HerdrConfig.baseUrl)
        assertEquals("https://herdr.example.com", HerdrConfig.baseUrlOrNull)
        assertTrue(HerdrConfig.isConfigured)
        assertEquals(1, reads)
    }

    @Test
    fun setServerAddressReplacesAndForgets() {
        HerdrConfig.install { "https://old.example.com" }
        HerdrConfig.setServerAddress("https://new.example.com")
        assertEquals("https://new.example.com", HerdrConfig.baseUrl)
        HerdrConfig.setServerAddress(null)
        assertNull(HerdrConfig.baseUrlOrNull)
        assertFalse(HerdrConfig.isConfigured)
    }

    @Test
    fun debugBuildOverrideWinsOverTheStoredAddress() {
        HerdrConfig.install { "https://herdr.example.com" }
        HerdrConfig.applyOverride("http://127.0.0.1:4321/", true)
        assertEquals("http://127.0.0.1:4321", HerdrConfig.baseUrl)
        assertTrue(HerdrConfig.hasDebugOverride)
        HerdrConfig.applyOverride(null, true)
        assertEquals("https://herdr.example.com", HerdrConfig.baseUrl)
        assertFalse(HerdrConfig.hasDebugOverride)
    }

    @Test
    fun releaseBuildIgnoresOverride() {
        HerdrConfig.applyOverride("http://127.0.0.1:4321", false)
        assertNull(HerdrConfig.baseUrlOrNull)
        assertFalse(HerdrConfig.hasDebugOverride)
    }

    @Test
    fun nonHttpOverrideIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HerdrConfig.applyOverride("file:///etc/passwd", true)
        }
        assertNull(HerdrConfig.baseUrlOrNull)
    }

    @Test
    fun loopbackAndLocalhostOverridesAreAccepted() {
        for (url in listOf(
            "http://127.0.0.1",
            "https://127.0.0.1",
            "http://127.0.0.1:8080",
            "https://127.0.0.1:8443/",
            "http://localhost",
            "http://localhost:3000",
            "https://localhost:3000/",
        )) {
            HerdrConfig.applyOverride(url, true)
            assertEquals(url, url.trimEnd('/'), HerdrConfig.baseUrl)
        }
    }

    @Test
    fun foreignHostOverridesAreRejected() {
        for (url in listOf(
            "https://example.com",
            "http://10.0.2.2:8080",
            "http://127.0.0.1.evil.com",
            "http://127.0.0.1@evil.com",
            "http://evil.com/127.0.0.1",
            "http://localhost.evil.com",
            "http://127.0.0.1:8080/path",
            "http://127.0.0.1:",
            "http://[::1]:8080",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) {
                HerdrConfig.applyOverride(url, true)
            }
            assertNull(url, HerdrConfig.baseUrlOrNull)
        }
    }
}
