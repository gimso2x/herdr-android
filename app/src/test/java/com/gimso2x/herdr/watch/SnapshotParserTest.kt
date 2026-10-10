package com.gimso2x.herdr.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotParserTest {
    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("session.json")) { "session.json missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun parse(body: String): List<PaneSample> = checkNotNull(SnapshotParser.parse(body)) { "parse returned null" }

    private fun snapshot(workspaces: String, panes: String) =
        """{"snapshot":{"workspaces":$workspaces,"panes":$panes}}"""

    @Test
    fun parsesRecordedSession() {
        val samples = parse(fixture())
        assertEquals(
            listOf("w11:p1", "w12:p1", "w13:p1", "w13:p2"),
            samples.map { it.paneId },
        )
        assertEquals(
            listOf(AgentStatus.WORKING, AgentStatus.WORKING, AgentStatus.WORKING, AgentStatus.DONE),
            samples.map { it.status },
        )
        assertEquals("ws-a", samples[0].workspace)
        assertEquals("ws-b", samples[1].workspace)
        assertEquals("π Fixture task 1", samples[0].title)
        assertEquals("omp", samples[3].agent)
        assertEquals("main", samples[0].tab)
        assertEquals("logs", samples[3].tab)
    }

    @Test
    fun agentPrefersDisplayNameAndTitleFallsBack() {
        val samples = parse(
            snapshot(
                """[{"workspace_id":"w1","label":"ws"}]""",
                """[
                  {"pane_id":"p1","workspace_id":"w1","agent_status":"idle","agent":"omp","display_agent":"Review bot","terminal_title_stripped":"Title","title":"Other"},
                  {"pane_id":"p2","workspace_id":"w1","agent_status":"idle","agent":"omp","title":"Pane title"},
                  {"pane_id":"p3","workspace_id":"w1","agent_status":"idle"},
                  {"pane_id":"p4","workspace_id":"w1","agent_status":"idle","agent":"  ","terminal_title_stripped":"  "},
                  {"pane_id":"p5","workspace_id":"w1","agent_status":"idle","agent":null,"terminal_title_stripped":null}
                ]""",
            ),
        )
        assertEquals(listOf("Review bot", "omp", null, null, null), samples.map { it.agent })
        assertEquals(listOf("Title", "Pane title", null, null, null), samples.map { it.title })
        assertEquals(listOf("ws", "ws", "ws", "ws", "ws"), samples.map { it.workspace })
    }

    @Test
    fun missingWorkspaceOrTabLabelsAreNull() {
        val samples = parse(
            snapshot(
                """[{"workspace_id":"w1"},{"label":"orphan"},{"workspace_id":"w3","label":null}]""",
                """[
                  {"pane_id":"p1","workspace_id":"w1","tab_id":"t9","agent_status":"idle"},
                  {"pane_id":"p2","workspace_id":"zzz","agent_status":"idle"},
                  {"pane_id":"p3","agent_status":"idle"},
                  {"pane_id":"p4","workspace_id":"w3","agent_status":"idle"}
                ]""",
            ),
        )
        assertEquals(listOf(null, null, null, null), samples.map { it.workspace })
        assertEquals(listOf(null, null, null, null), samples.map { it.tab })
    }

    @Test
    fun statusStringsMapAndUnknownValuesAreUnknown() {
        val samples = parse(
            snapshot(
                "[]",
                """[
                  {"pane_id":"a","agent_status":"blocked"},
                  {"pane_id":"b","agent_status":"done"},
                  {"pane_id":"c","agent_status":"idle"},
                  {"pane_id":"d","agent_status":"working"},
                  {"pane_id":"e","agent_status":"unknown"},
                  {"pane_id":"f","agent_status":"sleeping"},
                  {"pane_id":"g","agent_status":""},
                  {"pane_id":"h","agent_status":null},
                  {"pane_id":"i"},
                  {"pane_id":"j","agent_status":7},
                  {"pane_id":"k","agent_status":"WORKING"}
                ]""",
            ),
        )
        assertEquals(
            listOf(
                AgentStatus.BLOCKED, AgentStatus.DONE, AgentStatus.IDLE, AgentStatus.WORKING,
                AgentStatus.UNKNOWN, AgentStatus.UNKNOWN, AgentStatus.UNKNOWN, AgentStatus.UNKNOWN,
                AgentStatus.UNKNOWN, AgentStatus.UNKNOWN, AgentStatus.WORKING,
            ),
            samples.map { it.status },
        )
    }

    @Test
    fun panesWithoutUsableIdAreSkippedAndGarbageEntriesDoNotCrash() {
        val samples = parse(
            snapshot(
                "[]",
                """[
                  {"agent_status":"working"},
                  {"pane_id":null,"agent_status":"working"},
                  {"pane_id":"  ","agent_status":"working"},
                  "text", 5, null, [],
                  {"pane_id":"ok","agent_status":"working"}
                ]""",
            ),
        )
        assertEquals(listOf("ok"), samples.map { it.paneId })
    }

    @Test
    fun emptyPanesIsValidAndEmpty() {
        val samples = SnapshotParser.parse(snapshot("[]", "[]"))
        assertNotNull(samples)
        assertTrue(samples!!.isEmpty())
    }

    @Test
    fun missingOrMalformedWorkspacesStillYieldPanes() {
        for (workspaces in listOf("null", "{}", "\"x\"", "[1,\"a\",null]")) {
            val samples = parse(snapshot(workspaces, """[{"pane_id":"p","agent_status":"idle","agent":"a"}]"""))
            assertEquals(workspaces, listOf("a"), samples.map { it.agent })
            assertEquals(workspaces, listOf<String?>(null), samples.map { it.workspace })
        }
        val noWorkspaces = parse("""{"snapshot":{"panes":[{"pane_id":"p","agent_status":"idle"}]}}""")
        assertEquals(listOf("p"), noWorkspaces.map { it.paneId })
    }

    @Test
    fun unusableBodiesReturnNullInsteadOfThrowing() {
        val bodies = listOf(
            "", "   ", "not json", "{", "[]", "null", "42", "{}", """{"snapshot":null}""",
            """{"snapshot":"x"}""", """{"snapshot":[]}""", """{"snapshot":{}}""",
            """{"snapshot":{"workspaces":[]}}""", """{"snapshot":{"panes":null}}""",
            """{"snapshot":{"panes":{}}}""", """{"snapshot":{"panes":"x"}}""",
            "<html>Cloudflare Access</html>",
        )
        for (body in bodies) assertNull(body, SnapshotParser.parse(body))
    }
}
