package com.gimso2x.herdr.ui

import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.ServiceToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLogicTest {
    @Test
    fun bootstrapResultsMapToSetupErrors() {
        assertNull(SetupLogic.errorFor(BootstrapResult.Success("CF_Authorization=x", 1L)))
        assertEquals(SetupError.REJECTED, SetupLogic.errorFor(BootstrapResult.InvalidToken))
        assertEquals(SetupError.NO_COOKIE, SetupLogic.errorFor(BootstrapResult.StrictModeOrNoCookie))
        assertEquals(SetupError.NETWORK, SetupLogic.errorFor(BootstrapResult.Network))
        assertEquals(SetupError.UNEXPECTED, SetupLogic.errorFor(BootstrapResult.Unexpected(500)))
    }

    @Test
    fun errorExtraRoundTripsAndIgnoresUnknown() {
        SetupError.entries.forEach { assertEquals(it, SetupError.fromExtra(it.name)) }
        assertNull(SetupError.fromExtra(null))
        assertNull(SetupError.fromExtra(""))
        assertNull(SetupError.fromExtra("bogus"))
    }

    @Test
    fun credentialsAreTrimmed() {
        assertEquals(ServiceToken("abcd.access", "s3cret"), SetupLogic.credentials("  abcd.access\n", "\ts3cret  "))
    }

    @Test
    fun blankOrWhitespaceFieldsGiveNoCredentials() {
        assertNull(SetupLogic.credentials("", "secret"))
        assertNull(SetupLogic.credentials("id", ""))
        assertNull(SetupLogic.credentials("   ", "secret"))
        assertNull(SetupLogic.credentials("id", " \n\t "))
        assertNull(SetupLogic.credentials("", ""))
    }

    @Test
    fun veryLongInputIsRefused() {
        val max = "a".repeat(SetupLogic.MAX_FIELD_LENGTH)
        val over = max + "a"
        assertTrue(SetupLogic.accepts(max))
        assertFalse(SetupLogic.accepts(over))
        assertEquals(ServiceToken(max, max), SetupLogic.credentials(max, max))
        assertNull(SetupLogic.credentials(over, "secret"))
        assertNull(SetupLogic.credentials("id", over))
        assertNull(SetupLogic.credentials(" ".repeat(10) + over, "secret"))
    }

    @Test
    fun clientIdIsMaskedToFirstFourCharsPlusEllipsis() {
        assertEquals("abcd…", SetupLogic.maskClientId("abcd1234.access"))
        assertEquals("ab…", SetupLogic.maskClientId("ab"))
        assertEquals("…", SetupLogic.maskClientId(""))
        assertEquals("abcd…", SetupLogic.maskClientId("  abcdef"))
    }

    @Test
    fun serverChangeRecoveryAsksForCredentialsWhenTheTokenCannotBeReused() {
        // Token present and not rejected: reuse it, ask for the address only.
        for (error in listOf(SetupError.NETWORK, SetupError.NO_COOKIE, SetupError.UNEXPECTED, SetupError.STORE_FAILED)) {
            assertTrue(error.name, SetupLogic.addressOnlyRecovery(hasToken = true, error = error))
        }
        // Rejected by the new Access application, or no token at all: full credential entry.
        assertFalse(SetupLogic.addressOnlyRecovery(hasToken = true, error = SetupError.REJECTED))
        for (error in SetupError.entries) {
            assertFalse(error.name, SetupLogic.addressOnlyRecovery(hasToken = false, error = error))
        }
    }
}
