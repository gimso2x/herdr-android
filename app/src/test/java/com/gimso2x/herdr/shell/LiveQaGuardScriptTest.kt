package com.gimso2x.herdr.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the debug-only guard script's frame lists to the plan and keeps it out of release source sets. */
class LiveQaGuardScriptTest {
    // Module dir under Gradle; repo root when run from an IDE.
    private val srcDir: File = listOf(File("src"), File("app/src")).first { File(it, "debug").isDirectory }

    private val script: String = File(srcDir, "debug/assets/live_qa_guard.js").readText()

    private fun list(name: String): List<String> {
        val body = Regex("""var $name\s*=\s*\[([^\]]*)]""").find(script)?.groupValues?.get(1)
            ?: error("$name not found in guard script")
        return Regex("""'([^']*)'""").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun dropListMatchesPlan() {
        assertEquals(
            listOf("input", "keys", "pty-data", "resize", "submit", "pending-action", "secret", "take-over"),
            list("DROP_TYPES"),
        )
    }

    @Test
    fun allowListMatchesPlan() {
        assertEquals(listOf("attach", "detach", "pty-ack"), list("ALLOW_TYPES"))
    }

    @Test
    fun listsDoNotOverlap() {
        assertTrue(list("DROP_TYPES").intersect(list("ALLOW_TYPES").toSet()).isEmpty())
    }

    @Test
    fun scriptForcesObserveAndCountsDropsAndBlocks() {
        assertTrue(script.contains("type === 'role'"))
        assertTrue(script.contains("frame.mode = 'observe'"))
        assertTrue(script.contains("window.__qaDropped"))
        assertTrue(script.contains("window.__qaBlocked"))
        assertTrue(script.contains("'GET'") && script.contains("'HEAD'"))
    }

    @Test
    fun scriptCountsIncomingFrameTypesWithoutKeepingPayloads() {
        assertTrue(script.contains("window.__qaIncomingTypes"))
        assertTrue(script.contains("addEventListener('message', countIncoming)"))
        // The counter records only a validated type name; the event data is never stored.
        val counter = script.substringAfter("function countIncoming").substringBefore("var NativeWebSocket")
        assertFalse(counter.contains("window.__qaLast"))
        assertTrue(Regex("""window\.__qaIncomingTypes\[key]""").containsMatchIn(counter))
        assertFalse(Regex("""__qaIncoming\w*\s*(\[[^\]]*])?\s*=\s*[^;]*event""").containsMatchIn(counter))
    }

    @Test
    fun scriptIsOnlyInTheDebugSourceSet() {
        assertTrue(File(srcDir, "debug/assets/live_qa_guard.js").isFile)
        for (set in listOf("main", "release")) {
            val leaked = File(srcDir, set).walkTopDown().filter { it.isFile }.any { it.name.contains("live_qa_guard") }
            assertFalse("$set must not contain the guard script", leaked)
        }
    }

    @Test
    fun releaseStubsCarryNoHookOrScriptLogic() {
        val release = File(srcDir, "release").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(release.isNotEmpty())
        for (file in release) {
            val text = file.readText()
            assertFalse("${file.name} must not reference the script asset", text.contains("live_qa_guard"))
            assertFalse("${file.name} must not carry DOCUMENT_START_SCRIPT logic", text.contains("addDocumentStartJavaScript"))
            assertFalse("${file.name} must not be settable", Regex("""\bvar\s+(sleeper|clock)\b""").containsMatchIn(text))
        }
    }
}
