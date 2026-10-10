package com.gimso2x.herdr.qa

import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.SettingsActivity
import com.gimso2x.herdr.access.ServiceToken
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private val STORE_TOKEN = ServiceToken("qa-id-XXXX", "qa-secret-YYYY")

/** S-store phase 1: the token is stored; the preference file on disk contains neither value. */
@RunWith(AndroidJUnit4::class)
class SStoreWriteTest {
    @Test
    fun tokenIsEncryptedAtRest() {
        Qa.resetState(alerts = false, token = null)
        assertTrue(Qa.app.tokenStore.put(STORE_TOKEN))
        val file = File(Qa.targetContext.dataDir, "shared_prefs/herdr_access.xml")
        assertTrue("herdr_access.xml was not written", file.isFile)
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("client id stored in clear text", bytes.contains(STORE_TOKEN.clientId))
        assertFalse("client secret stored in clear text", bytes.contains(STORE_TOKEN.clientSecret))
    }
}

/** S-store phase 2 (after the host force-stopped the app): the token reads back, then is cleared. */
@RunWith(AndroidJUnit4::class)
class SStoreReadTest {
    @Test
    fun tokenSurvivesProcessDeath() {
        try {
            assertEquals(STORE_TOKEN, Qa.app.tokenStore.get())
        } finally {
            Qa.app.tokenStore.clear()
        }
        assertNull(Qa.app.tokenStore.get())
    }
}

/** S-cookie: "토큰 지우기" removes the stored token and the Access cookie from the WebView. */
@RunWith(AndroidJUnit4::class)
class SCookieWipeTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun clearTokenWipesTheCookie() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        Qa.onMain {
            CookieManager.getInstance().setCookie(Qa.SAMPLE, "CF_Authorization=qa-fixture-cookie; Path=/; Secure; HttpOnly")
            CookieManager.getInstance().flush()
        }
        assertNotNull("fixture cookie was not installed", Qa.cookieFor(Qa.SAMPLE))
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val clear = Qa.device.wait(Until.findObject(By.text("토큰 지우기").enabled(true)), 30_000)
            assertNotNull("clear-token button not available", clear)
            clear.click()
            Qa.until(20_000, "cookie wiped") { Qa.cookieFor(Qa.SAMPLE) == null }
            Qa.until(20_000, "token wiped") { Qa.app.tokenStore.get() == null }
        }
    }
}
