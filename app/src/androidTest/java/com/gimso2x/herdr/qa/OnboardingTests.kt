package com.gimso2x.herdr.qa

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.AppPreferences
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.OnboardingActivity
import com.gimso2x.herdr.SetupActivity
import com.gimso2x.herdr.watch.StatusWatcherService
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.CoreMatchers.anyOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PKG = "com.gimso2x.herdr"

// What ActivityResultContracts.RequestPermission launches today (androidx.activity 1.11): the platform action.
private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"

private object Ob {
    /** Intents of the app process stubbed to a cancelled result: the system screens never open. */
    private val ACTIONS = listOf(
        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        ACTION_REQUEST_PERMISSIONS,
    )

    fun stubSystemScreens() {
        Intents.init()
        Intents.intending(anyOf(*ACTIONS.map { hasAction(it) }.toTypedArray()))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    fun release() {
        runCatching { Intents.release() }
    }

    fun firedIntent(action: String): Intent? = Intents.getIntents().firstOrNull { it.action == action }

    fun awaitFired(action: String): Intent {
        Qa.until(15_000, "intent $action fired", { "seen=${Intents.getIntents().map { it.action }}" }) {
            firedIntent(action) != null
        }
        return firedIntent(action)!!
    }

    /** The row's button, scrolling the checklist down until it shows (a click hits its visible part). */
    fun button(text: String): UiObject2 {
        val device = Qa.device
        assertNotNull("onboarding screen did not show", device.wait(Until.hasObject(By.text("처음 설정 안내")), 30_000))
        repeat(12) {
            device.findObject(By.text(text))?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f)
        }
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        val nodes = Regex("<node [^>]*>").findAll(out.toString()).map { node ->
            fun attr(name: String) = Regex("$name=\"([^\"]*)\"").find(node.value)?.groupValues?.get(1).orEmpty()
            "${attr("class").substringAfterLast('.')}|${attr("text")}|${attr("bounds")}|c=${attr("clickable")} e=${attr("enabled")}"
        }.joinToString(" ;; ")
        throw AssertionError("button '$text' not found on the onboarding screen; nodes=$nodes")
    }

    /** A control outside the scrolling list (the bottom bar). */
    fun fixed(text: String): UiObject2 {
        val node = Qa.device.wait(Until.findObject(By.text(text)), 30_000)
        assertNotNull("'$text' not shown", node)
        return node
    }

    fun toTop() {
        val area = Qa.device.findObject(By.scrollable(true)) ?: return
        repeat(6) { area.scroll(Direction.UP, 1.0f) }
    }

    fun awaitActivity(simpleName: String) {
        Qa.until(30_000, "$simpleName live", { "live=${Qa.liveActivityNames()}" }) {
            Qa.liveActivityNames().contains(simpleName)
        }
    }

    fun doneCount(): Int = Qa.device.findObjects(By.text("완료")).size

    fun finishMain() {
        Qa.onMain {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED && it != Stage.PRE_ON_CREATE }
                .flatMap { registry.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>()
                .forEach { it.finish() }
        }
        Qa.until(20_000, "MainActivity gone") { !Qa.mainActivityExists() }
    }

    fun pageFixture(): Fixture {
        val fx = Fixture()
        fx.routes["/"] = { fx.html("onboarding", "<div id=m>onboarding</div>") }
        return fx.start()
    }
}

/** O1a: a fixture setup success continues into the onboarding checklist, not into MainActivity. */
@RunWith(AndroidJUnit4::class)
class O1SetupToOnboardingTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun setupSuccessShowsOnboarding() {
        Qa.resetState(alerts = false, token = null)
        Qa.setOnboarding(done = false, asked = false)
        val fx = Fixture().start()
        try {
            ActivityScenario.launch<SetupActivity>(SetupActivity.intent(Qa.targetContext)).use {
                val fields = Qa.device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 60_000)
                assertTrue("setup fields not shown", fields != null && fields.size >= 2)
                val token = Qa.syntheticToken()
                fields[0].text = token.clientId
                fields[1].text = token.clientSecret
                val connect = Qa.device.wait(Until.findObject(By.text("연결").enabled(true)), 30_000)
                assertNotNull("connect button never enabled", connect)
                connect.click()
                Ob.awaitActivity("OnboardingActivity")
                assertTrue(Qa.device.wait(Until.hasObject(By.text("처음 설정 안내")), 30_000) == true)
                assertFalse("MainActivity must not open before the checklist", Qa.liveActivityNames().contains("MainActivity"))
                assertFalse(AppPreferences(Qa.targetContext).onboardingDone)
            }
        } finally {
            fx.stop()
        }
    }
}

/** O1b: MainActivity itself shows the checklist while it is not done and a token exists. */
@RunWith(AndroidJUnit4::class)
class O1MainStartShowsOnboardingTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun mainActivityStartOpensOnboarding() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        Qa.setOnboarding(done = false, asked = false)
        val fx = Ob.pageFixture()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Ob.awaitActivity("OnboardingActivity")
                // MainActivity owns no permission prompt while the checklist is pending.
                assertFalse(AppPreferences(Qa.targetContext).notificationPermissionAsked)
            }
        } finally {
            fx.stop()
        }
    }
}

/** O1c: every button fires its system Intent, and the statuses are re-read on resume. */
@RunWith(AndroidJUnit4::class)
class O1ButtonsTest {
    @After fun tearDown() {
        Ob.release()
        Qa.shell("cmd deviceidle whitelist -$PKG")
        Qa.hooksReset()
    }

    @Test
    fun buttonsFireIntentsAndStatusRefreshes() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        Qa.setOnboarding(done = false, asked = true)
        Ob.stubSystemScreens()
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            // a: POST_NOTIFICATIONS is granted by the host, so the button opens the app's notification settings.
            Ob.button("허용하기").click()
            val notification = Ob.awaitFired(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            assertEquals(PKG, notification.getStringExtra(Settings.EXTRA_APP_PACKAGE))

            // b
            Ob.button("제한 해제하기").click()
            val battery = Ob.awaitFired(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            assertEquals("package:$PKG", battery.data.toString())

            // c
            Ob.button("일시중지 끄기").click()
            val revoke = Ob.awaitFired(Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
            assertEquals("package:$PKG", revoke.data.toString())

            // d: only on Samsung devices.
            val samsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            if (samsung) {
                Ob.button("앱 설정 열기").click()
                Ob.awaitFired(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            } else {
                assertFalse(Qa.device.hasObject(By.text("앱 설정 열기")))
            }

            // Status is re-evaluated on resume: whitelist the app, leave and come back.
            Ob.toTop()
            val before = Ob.doneCount()
            scenario.moveToState(Lifecycle.State.STARTED)
            Qa.shell("cmd deviceidle whitelist +$PKG")
            scenario.moveToState(Lifecycle.State.RESUMED)
            Qa.until(20_000, "battery row turned 완료 (was $before)", { "now=${Ob.doneCount()}" }) {
                Ob.doneCount() > before
            }
        }
    }
}

/** O1d: "시작하기" opens MainActivity, stores the flag, and the next launch shows no checklist. */
@RunWith(AndroidJUnit4::class)
class O1StartTest {
    @After fun tearDown() {
        Ob.release()
        Qa.hooksReset()
    }

    @Test
    fun startOpensMainAndOnboardingDoesNotReturn() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        Qa.setOnboarding(done = false, asked = false)
        val fx = Ob.pageFixture()
        try {
            Intents.init()
            ActivityScenario.launch(OnboardingActivity::class.java).use {
                Ob.fixed("시작하기").click()
                Qa.until(30_000, "MainActivity started by the checklist") {
                    Intents.getIntents().any { it.component?.className == MainActivity::class.java.name }
                }
                Qa.until(30_000, "checklist flags stored") {
                    val prefs = AppPreferences(Qa.targetContext)
                    prefs.onboardingDone && prefs.notificationPermissionAsked
                }
                Qa.until(30_000, "MainActivity alive and checklist closed") {
                    Qa.mainActivityExists() && !Qa.liveActivityNames().contains("OnboardingActivity")
                }
            }
            Ob.release()

            // Next launch: MainActivity decides synchronously from the flag, so once the page is up
            // a checklist that was going to open would already be live.
            Ob.finishMain()
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('m') !== null")
                assertFalse(Qa.liveActivityNames().contains("OnboardingActivity"))
            }
        } finally {
            fx.stop()
        }
    }
}

/** O1e: the ongoing watcher notification opens Settings (which offers the checklist again). */
@RunWith(AndroidJUnit4::class)
class O1WatchNotificationTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun watchNotificationTapOpensSettings() {
        Qa.resetState(alerts = true, token = Qa.syntheticToken())
        val wf = WatchFixture().start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { withTimeout(30_000) { StatusWatcherService.isRunning.first { it } } }
                Qa.device.pressHome()
                Qa.device.openNotification()
                val entry = Qa.device.wait(
                    Until.findObject(By.text(Pattern.compile("에이전트 알림 대기 중|연결 끊김.*"))),
                    30_000,
                )
                assertNotNull("watcher notification not in the shade", entry)
                entry.click()
                Ob.awaitActivity("SettingsActivity")
                assertTrue(Qa.device.wait(Until.hasObject(By.text("서비스 토큰")), 30_000) == true)
            }
        } finally {
            wf.fx.stop()
        }
    }
}

/** O2: with POST_NOTIFICATIONS revoked by the host, the button asks once, then falls back to Settings. */
@RunWith(AndroidJUnit4::class)
class O2PermissionRequestTest {
    @After fun tearDown() {
        Ob.release()
        Qa.hooksReset()
    }

    @Test
    fun notificationButtonRequestsThenOpensSettings() {
        check(Qa.targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
            "POST_NOTIFICATIONS must be revoked by the host before this phase"
        }
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        Qa.setOnboarding(done = false, asked = false)
        Ob.stubSystemScreens()
        ActivityScenario.launch(OnboardingActivity::class.java).use {
            Ob.button("허용하기").click()
            Ob.awaitFired(ACTION_REQUEST_PERMISSIONS)
            Qa.until(10_000, "asked flag stored") { AppPreferences(Qa.targetContext).notificationPermissionAsked }

            // The stubbed dialog never ran, so the system still allows no rationale: treated as permanently denied.
            Ob.button("허용하기").click()
            Ob.awaitFired(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
    }
}
