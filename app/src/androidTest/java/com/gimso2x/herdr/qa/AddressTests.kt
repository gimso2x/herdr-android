package com.gimso2x.herdr.qa

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private fun pageFixture(): Fixture {
    val fx = Fixture()
    fx.routes["/"] = { fx.html("address", "<div id=m>address</div>") }
    return fx
}

private fun resetAddress() {
    Qa.app.tokenStore.clearServer()
    HerdrConfig.setServerAddress(null)
    Qa.hooksReset()
}

/**
 * U1: the upgrade path of an install that has a token but never stored a server address (what a
 * v0.1.3 -> v0.1.4 update looks like). MainActivity must land on the setup screen asking for the
 * address only, keep the token untouched and never assume a domain; entering a fixture address then
 * reuses the stored token and continues into the app.
 */
@RunWith(AndroidJUnit4::class)
class U1UpgradePathTest {
    @After fun tearDown() = resetAddress()

    @Test
    fun tokenWithoutAddressAsksOnlyForTheAddress() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken()) // token present, no address, no override
        assertNull("no address must be stored", Qa.app.tokenStore.getServer())
        assertFalse(HerdrConfig.isConfigured)
        val fx = pageFixture().start(override = false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.until(60_000, "setup screen", { "live=${Qa.liveActivityNames()}" }) {
                    Qa.liveActivityNames().contains("SetupActivity")
                }
                assertNotNull(
                    "address-only hint not shown",
                    Qa.device.wait(Until.findObject(By.textContains("저장된 서비스 토큰은 그대로")), 30_000),
                )
                val fields = Qa.device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 30_000)
                assertEquals("only the address field may be asked", 1, fields?.size)
                assertFalse("token fields must not be asked", Qa.device.hasObject(By.textContains("Client ID")))
                assertFalse("token fields must not be asked", Qa.device.hasObject(By.textContains("Client Secret")))
                Qa.until(30_000, "MainActivity left") { !Qa.liveActivityNames().contains("MainActivity") }
                assertEquals("the token must be kept", Qa.syntheticToken(), Qa.app.tokenStore.get())
                assertEquals("nothing may be contacted before the address is entered", 0, fx.log.size)

                // An invalid address is reported on the field and nothing is contacted.
                fields!![0].text = "http://insecure.example.com"
                assertNotNull(
                    "invalid-address message not shown",
                    Qa.device.wait(Until.findObject(By.textContains("https://로 시작하는")), 15_000),
                )
                assertEquals(0, fx.log.size)

                // The fixture address (debug builds accept loopback) completes it with the stored token.
                fields[0].text = fx.base
                val connect = Qa.device.wait(Until.findObject(By.text("연결").enabled(true)), 30_000)
                assertNotNull("connect button never enabled", connect)
                connect.click()
                Qa.until(60_000, "address stored", { "health=${fx.healthRequests()}" }) {
                    Qa.app.tokenStore.getServer() == fx.base
                }
                assertTrue("bootstrap used the stored token (fixture answers 403 otherwise)", fx.healthRequests() >= 1)
                assertEquals(Qa.syntheticToken(), Qa.app.tokenStore.get())
                Qa.until(60_000, "app opened", { "live=${Qa.liveActivityNames()}" }) {
                    Qa.liveActivityNames().contains("MainActivity")
                }
                assertEquals(fx.base, HerdrConfig.baseUrl)
            }
        } finally {
            fx.stop()
        }
    }
}

/** U2: Settings shows the stored address; changing it wipes the old cookie and bootstraps the new server. */
@RunWith(AndroidJUnit4::class)
class U2SettingsChangeTest {
    @After fun tearDown() = resetAddress()

    @Test
    fun changeAddressRebootstraps() {
        val a = pageFixture().start(override = false)
        val b = pageFixture().start(override = false)
        try {
            Qa.resetState(alerts = false, token = Qa.syntheticToken(), server = a.base)
            Qa.onMain { android.webkit.CookieManager.getInstance().setCookie(a.base, "CF_Authorization=old; Path=/") }
            ActivityScenario.launch(SettingsActivity::class.java).use {
                assertNotNull("stored address not shown", Qa.device.wait(Until.findObject(By.text(a.base)), 30_000))
                val field = Qa.device.wait(Until.findObject(By.clazz("android.widget.EditText")), 30_000)
                assertNotNull(field)
                field.text = b.base
                val change = Qa.device.wait(Until.findObject(By.text("주소 바꾸기").enabled(true)), 30_000)
                assertNotNull("change button never enabled", change)
                change.click()
                Qa.until(60_000, "new address stored", { "health=${b.healthRequests()}" }) {
                    Qa.app.tokenStore.getServer() == b.base && b.healthRequests() >= 1
                }
                // Cookies are per host, not per port: both fixtures share 127.0.0.1, so the old marker
                // value must be gone and the value the new server issued must be present.
                Qa.until(30_000, "old cookie wiped, new one installed") {
                    val cookie = Qa.cookieFor(b.base).orEmpty()
                    !cookie.contains("CF_Authorization=old") && b.issued.any { cookie.contains(it) }
                }
                assertEquals(0, a.healthRequests())
                assertEquals(b.base, HerdrConfig.baseUrl)
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun failedChangeGoesBackToSetup() {
        val a = pageFixture().start(override = false)
        val b = pageFixture().start(override = false)
        b.healthStatus = { 403 }
        try {
            Qa.resetState(alerts = false, token = Qa.syntheticToken(), server = a.base)
            ActivityScenario.launch(SettingsActivity::class.java).use {
                val field = Qa.device.wait(Until.findObject(By.clazz("android.widget.EditText")), 30_000)
                assertNotNull(field)
                field.text = b.base
                val change = Qa.device.wait(Until.findObject(By.text("주소 바꾸기").enabled(true)), 30_000)
                assertNotNull(change)
                change.click()
                Qa.until(60_000, "setup shown after the failed bootstrap", { "live=${Qa.liveActivityNames()}" }) {
                    Qa.liveActivityNames().contains("SetupActivity")
                }
                assertNotNull(
                    "rejection not shown",
                    Qa.device.wait(Until.findObject(By.textContains("토큰이 거부되었습니다")), 30_000),
                )
                assertTrue(b.healthRequests() >= 1)
                assertNull("no cookie of the failed server", Qa.cookieFor(b.base))
            }
        } finally {
            a.stop()
            b.stop()
        }
    }
}
