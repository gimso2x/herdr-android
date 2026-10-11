package com.gimso2x.herdr.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTextTest {
    private fun fmt(
        kind: AlertKind = AlertKind.NEEDS_INPUT,
        paneId: String = "w1:p1",
        workspace: String? = "ws-a",
        tab: String? = "main",
        agent: String? = "omp",
        title: String? = "Fixture task",
        detail: String? = null,
    ) = AlertText.format(kind, paneId, workspace, tab, agent, title, detail)

    @Test
    fun fullTitleAndBodyForBothKinds() {
        val blocked = fmt()
        assertEquals("ws-a · main", blocked.title)
        assertEquals("omp · Fixture task — 입력이 필요합니다", blocked.body)
        assertEquals(blocked.body, blocked.bigText)
        val done = fmt(kind = AlertKind.FINISHED)
        assertEquals("omp · Fixture task — 작업을 마쳤습니다", done.body)
    }

    @Test
    fun titleFallsBackToWorkspaceThenTabThenPaneId() {
        assertEquals("ws-a", fmt(tab = null).title)
        assertEquals("ws-a", fmt(tab = "  ").title)
        assertEquals("main", fmt(workspace = null).title)
        assertEquals("w1:p1", fmt(workspace = null, tab = null).title)
        assertEquals("w1:p1", fmt(workspace = "", tab = "\n").title)
    }

    @Test
    fun bodyDropsMissingParts() {
        assertEquals("omp — 입력이 필요합니다", fmt(title = null).body)
        assertEquals("Fixture task — 입력이 필요합니다", fmt(agent = null).body)
        assertEquals("입력이 필요합니다", fmt(agent = " ", title = null).body)
        assertEquals("작업을 마쳤습니다", fmt(kind = AlertKind.FINISHED, agent = null, title = null).body)
    }

    @Test
    fun detailGoesIntoTheBigTextOnly() {
        val text = fmt(detail = "Apply the migration?")
        assertEquals("omp · Fixture task — 입력이 필요합니다", text.body)
        assertEquals("omp · Fixture task — 입력이 필요합니다\nApply the migration?", text.bigText)
    }

    @Test
    fun detailUsesItsFirstNonBlankLineOnly() {
        assertEquals("first", AlertText.firstLine("\n  \n  first  \nsecond"))
        assertNull(AlertText.firstLine(null))
        assertNull(AlertText.firstLine(" \n\t "))
        assertEquals("a b", AlertText.firstLine("a \t  b"))
        assertTrue(fmt(detail = "   ").bigText == fmt().body)
    }

    @Test
    fun detailIsCutToOneHundredTwentyCharactersWithAnEllipsis() {
        val exact = "x".repeat(AlertText.DETAIL_MAX)
        assertEquals(exact, AlertText.firstLine(exact))
        val cut = AlertText.firstLine("y".repeat(500))!!
        assertEquals(AlertText.DETAIL_MAX, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("y".repeat(AlertText.DETAIL_MAX - 1) + "…", cut)
    }

    @Test
    fun longLabelsAreCutAndControlCharactersFlattened() {
        val text = fmt(
            workspace = "w".repeat(200),
            tab = "t\u0000a\u0007b",
            agent = "a".repeat(100),
            title = "line one\nline two",
        )
        val parts = text.title.split(" · ")
        assertEquals(AlertText.LABEL_MAX, parts[0].length)
        assertEquals("t a b", parts[1])
        assertTrue(text.body.startsWith("a".repeat(AlertText.AGENT_MAX - 1) + "… · line one line two — "))
        assertTrue(!text.body.contains('\n'))
    }

    @Test
    fun alertDerivesItsTextFromTheSampleAndDetail() {
        val sample = PaneSample("w1:p1", AgentStatus.BLOCKED, "ws-a", "main", "omp", "Fixture task")
        val alert = Alert("w1:p1", AlertKind.NEEDS_INPUT, sample).copy(detail = "Proceed?")
        assertEquals("ws-a · main", alert.title)
        assertEquals("omp · Fixture task — 입력이 필요합니다", alert.body)
        assertEquals("${alert.body}\nProceed?", alert.bigText)
    }
}

class AlertDetailTest {
    @Test
    fun promptQuestionIsTheFirstLine() {
        val body = """{"prompt":{"id":"x","agent":"claude","kind":"question","title":"Heads up","question":"Run the fixture migration?\nmore","options":[]},"suggestion":null}"""
        assertEquals("Run the fixture migration?", AlertDetail.fromPrompt(body))
    }

    @Test
    fun promptFallsBackToTitleAndToNothing() {
        assertEquals("Heads up", AlertDetail.fromPrompt("""{"prompt":{"title":"Heads up","question":" "}}"""))
        assertNull(AlertDetail.fromPrompt("""{"prompt":null,"suggestion":"x"}"""))
        assertNull(AlertDetail.fromPrompt("""{"prompt":{}}"""))
        assertNull(AlertDetail.fromPrompt("{}"))
        assertNull(AlertDetail.fromPrompt("not json"))
        assertNull(AlertDetail.fromPrompt(""))
    }

    @Test
    fun conversationUsesTheLastAssistantText() {
        val body = """{"source":"omp-transcript","turns":[
          {"role":"user","parts":[{"kind":"text","text":"do it"}]},
          {"role":"assistant","parts":[{"kind":"text","text":"old answer"}]},
          {"role":"user","parts":[{"kind":"text","text":"again"}]},
          {"role":"assistant","parts":[
            {"kind":"thinking","text":"hmm"},
            {"kind":"text","text":"Done: updated 3 files.\nDetails follow"},
            {"kind":"tool","name":"bash","summary":"ls","input":"","output":""}]}
        ],"cursor":null}"""
        assertEquals("Done: updated 3 files.", AlertDetail.fromConversation(body))
    }

    @Test
    fun conversationWithoutAnAnswerYieldsNothing() {
        assertNull(AlertDetail.fromConversation("""{"source":"scrollback","turns":[]}"""))
        assertNull(AlertDetail.fromConversation("""{"turns":[{"role":"user","parts":[{"kind":"text","text":"hi"}]}]}"""))
        // The newest assistant turn has no text: never reach back to an older answer.
        assertNull(
            AlertDetail.fromConversation(
                """{"turns":[{"role":"assistant","parts":[{"kind":"text","text":"old"}]},
                   {"role":"assistant","parts":[{"kind":"tool","name":"x","summary":"","input":"","output":""}]}]}""",
            ),
        )
        assertNull(AlertDetail.fromConversation("{}"))
        assertNull(AlertDetail.fromConversation("[]"))
    }
}

class AlertLockScreenTextTest {
    @Test
    fun lockScreenShowsOnlyTheKindOfAttention() {
        assertEquals("입력이 필요합니다", AlertText.lockScreenText(AlertKind.NEEDS_INPUT))
        assertEquals("작업을 마쳤습니다", AlertText.lockScreenText(AlertKind.FINISHED))
    }
}
