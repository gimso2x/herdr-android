package com.gimso2x.herdr.qa

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.app.Instrumentation
import com.gimso2x.herdr.MainActivity
import java.security.MessageDigest
import java.util.Random
import okio.Buffer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C1: a real tap on <input type=file> reaches the stubbed system picker and the page reads the file. */
@RunWith(AndroidJUnit4::class)
class C1FileChooserTest {
    @After fun tearDown() {
        Qa.hooksReset()
        runCatching { Intents.release() }
    }

    @Test
    fun fileChooserDeliversTheFile() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "c1",
                "<input id=f type=file accept=\"image/*\" style=\"display:block;width:300px;height:80px\">",
                """
                document.getElementById('f').addEventListener('change', function (e) {
                  var file = e.target.files[0];
                  var reader = new FileReader();
                  reader.onload = function () { window.__c1 = file.size + ':' + reader.result.byteLength; };
                  reader.onerror = function () { window.__c1 = 'error'; };
                  reader.readAsArrayBuffer(file);
                });
                """.trimIndent(),
            )
        }
        fx.start()
        Intents.init()
        try {
            val uri = Uri.parse("content://${QaPng.AUTHORITY}/qa/${QaPng.NAME}")
            intending(hasAction(Intent.ACTION_GET_CONTENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('f') !== null && document.readyState === 'complete'")
                Qa.tap("#f")
                val size = QaPng.bytes().size
                Qa.until(20_000, "the file chooser reached ACTION_GET_CONTENT") {
                    Intents.getIntents().any { it.action == Intent.ACTION_GET_CONTENT }
                }
                // The stubbed result is only usable if the app can open the test APK's provider.
                val opened = Qa.targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().size }
                assertEquals("provider served a different file", size, opened)
                Qa.until(30_000, "page read the chosen file", { "c1=${Qa.js("window.__c1")}" }) {
                    Qa.js("window.__c1") == "$size:$size"
                }
            }
        } finally {
            fx.stop()
        }
    }
}

private const val MIC_SCRIPT = """
window.__mic = undefined;
navigator.mediaDevices.getUserMedia({audio: true}).then(function (stream) {
  var track = stream.getAudioTracks()[0];
  var state = track.readyState;
  var recorder = new MediaRecorder(stream);
  var chunks = [];
  recorder.ondataavailable = function (e) { chunks.push(e.data); };
  recorder.onstop = function () {
    var blob = new Blob(chunks);
    window.__mic = JSON.stringify({state: state, size: blob.size});
    stream.getTracks().forEach(function (t) { t.stop(); });
  };
  recorder.start();
  setTimeout(function () { recorder.stop(); }, 2000);
}).catch(function (e) { window.__mic = JSON.stringify({error: e.name}); });
"""

/** C2: microphone granted: a live track and a non-empty 2 s recording. */
@RunWith(AndroidJUnit4::class)
class C2MicGrantTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun microphoneRecords() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = { fx.html("c2", "<div id=m>c2</div>") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('m') !== null && document.readyState === 'complete'")
                Qa.js("(function(){ $MIC_SCRIPT ; return 1})()")
                Qa.awaitJs("microphone result", 45_000, "window.__mic !== undefined")
                val result = JSONObject(Qa.jsString("window.__mic")!!)
                assertEquals("getUserMedia failed: ${result.optString("error")}", "live", result.optString("state"))
                assertTrue("recording is empty", result.optLong("size") > 0)
            }
        } finally {
            fx.stop()
        }
    }
}

/** C3: microphone refused in the system dialog (the host revoked the permission before this phase). */
@RunWith(AndroidJUnit4::class)
class C3MicDenyTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun microphoneDenied() {
        check(Qa.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED) {
            "RECORD_AUDIO must be revoked by the host before this phase"
        }
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = { fx.html("c3", "<div id=m>c3</div>") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('m') !== null && document.readyState === 'complete'")
                Qa.js("(function(){ $MIC_SCRIPT ; return 1})()")
                val deny = Qa.device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), 30_000)
                assertNotNull("permission dialog did not appear", deny)
                deny.click()
                Qa.awaitJs("microphone result", 30_000, "window.__mic !== undefined")
                assertEquals("NotAllowedError", JSONObject(Qa.jsString("window.__mic")!!).optString("error"))
            }
        } finally {
            fx.stop()
        }
    }
}

/** C4: navigator.clipboard.writeText from a real tap lands in the system clipboard. */
@RunWith(AndroidJUnit4::class)
class C4ClipboardTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun clipboardWrite() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "c4",
                "<button id=b style=\"display:block;width:300px;height:80px\">copy</button>",
                """
                document.getElementById('b').addEventListener('click', function () {
                  navigator.clipboard.writeText('qa-123').then(
                    function () { window.__c4 = 'ok'; },
                    function (e) { window.__c4 = 'error:' + e.name; });
                });
                """.trimIndent(),
            )
        }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('b') !== null && document.readyState === 'complete'")
                Qa.tap("#b")
                Qa.awaitJs("clipboard write settled", 30_000, "window.__c4 !== undefined")
                assertEquals("clipboard write result (document.hasFocus=${Qa.js("document.hasFocus()")})", "ok", Qa.jsString("window.__c4"))
                var text: String? = null
                Qa.until(15_000, "primary clip text") {
                    text = Qa.onMain {
                        val manager = Qa.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        manager.primaryClip?.getItemAt(0)?.text?.toString()
                    }
                    text == "qa-123"
                }
            }
        } finally {
            fx.stop()
        }
    }
}

/** C5: attachments are downloaded by DownloadManager; an HTML login page under a .png name keeps its text/html type. */
@RunWith(AndroidJUnit4::class)
class C5FixtureDownloadTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun fixtureDownloads() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val bytes = ByteArray(64 * 1024).also { Random(42).nextBytes(it) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "c5",
                "<a id=bin href=\"/f.bin\" style=\"display:block;height:80px\">bin</a>" +
                    "<a id=login href=\"/login.html\" style=\"display:block;height:80px\">login</a>",
            )
        }
        fx.routes["/f.bin"] = {
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Disposition", "attachment; filename=\"f.bin\"")
                .setBody(Buffer().write(bytes))
        }
        fx.routes["/login.html"] = {
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setHeader("Content-Disposition", "attachment; filename=\"login.png\"")
                .setBody("<html><body>login</body></html>")
        }
        fx.start()
        val manager = Qa.targetContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        DownloadAssertions.removeAll(manager)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('bin') !== null && document.readyState === 'complete'")
                Qa.tap("#bin")
                val bin = DownloadAssertions.awaitSuccessful(manager, "/f.bin", 60_000)
                val digest = MessageDigest.getInstance("SHA-256").digest(DownloadAssertions.read(manager, bin.id))
                assertEquals(sha, digest.joinToString("") { "%02x".format(it) })

                Qa.tap("#login")
                val login = DownloadAssertions.awaitSuccessful(manager, "/login.html", 60_000)
                assertEquals("text/html", login.mime)
            }
        } finally {
            fx.stop()
        }
    }
}
