package com.gimso2x.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class HerdrConfigTest {
    @Test
    fun baseUrlIsHerdrOrigin() {
        assertEquals("https://herdr.example.com", HerdrConfig.BASE_URL)
    }
}
