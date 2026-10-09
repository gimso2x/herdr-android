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
}
