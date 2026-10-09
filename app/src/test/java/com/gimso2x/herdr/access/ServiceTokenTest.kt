package com.gimso2x.herdr.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTokenTest {
    private val id = "abcd1234.access"
    private val secret = "s3cr3t-value-not-real"
    private val token = ServiceToken(id, secret)

    @Test
    fun toStringShowsOnlyFirstFourCharsOfId() {
        assertEquals("ServiceToken(abcd…)", token.toString())
    }

    @Test
    fun toStringContainsNeitherFullIdNorSecret() {
        val text = token.toString()
        assertFalse(text.contains(id))
        assertFalse(text.contains(secret))
    }

    @Test
    fun interpolationAndCollectionsDoNotLeak() {
        val text = "$token ${listOf(token)} ${token.copy(clientId = "wxyz9999")}"
        assertFalse(text.contains(id))
        assertFalse(text.contains(secret))
        assertTrue(text.contains("ServiceToken(abcd…)"))
    }
}
