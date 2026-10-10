package com.gimso2x.herdr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HerdrConfigTest {
    @After
    fun resetOverride() {
        HerdrConfig.applyOverride(null, true)
    }

    @Test
    fun baseUrlIsHerdrOrigin() {
        assertEquals("https://herdr.example.com", HerdrConfig.BASE_URL)
    }

    @Test
    fun baseUrlDefaultsToConstant() {
        assertEquals(HerdrConfig.BASE_URL, HerdrConfig.baseUrl)
    }

    @Test
    fun debugBuildAppliesAndClearsOverride() {
        HerdrConfig.applyOverride("http://127.0.0.1:4321/", true)
        assertEquals("http://127.0.0.1:4321", HerdrConfig.baseUrl)
        HerdrConfig.applyOverride(null, true)
        assertEquals(HerdrConfig.BASE_URL, HerdrConfig.baseUrl)
    }

    @Test
    fun releaseBuildIgnoresOverride() {
        HerdrConfig.applyOverride("http://127.0.0.1:4321", false)
        assertEquals(HerdrConfig.BASE_URL, HerdrConfig.baseUrl)
    }

    @Test
    fun nonHttpOverrideIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HerdrConfig.applyOverride("file:///etc/passwd", true)
        }
        assertEquals(HerdrConfig.BASE_URL, HerdrConfig.baseUrl)
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
            assertEquals(url, HerdrConfig.BASE_URL, HerdrConfig.baseUrl)
        }
    }
}
