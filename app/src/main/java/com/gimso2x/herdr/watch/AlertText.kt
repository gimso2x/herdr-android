package com.gimso2x.herdr.watch

/**
 * Pure text of an alert notification.
 *
 * - title: `<workspace> · <tab>`; only the labels that exist; the pane id when there is none;
 * - body: `<agent> · <pane title> — <입력이 필요합니다|작업을 마쳤습니다>`; missing parts are left out;
 * - bigText: the body, plus the first line of [detail] (a prompt question or the last answer) on a new line.
 * Every field is flattened to one line and cut to a fixed length; the detail's first line is at most
 * [DETAIL_MAX] characters including the ellipsis.
 */
object AlertText {
    const val BODY_NEEDS_INPUT = "입력이 필요합니다"
    const val BODY_FINISHED = "작업을 마쳤습니다"

    const val LABEL_MAX = 60
    const val AGENT_MAX = 40
    const val PANE_TITLE_MAX = 80
    const val DETAIL_MAX = 120

    data class Text(val title: String, val body: String, val bigText: String)

    /** What a locked screen shows instead of the alert: the kind of attention needed, nothing from the pane. */
    fun lockScreenText(kind: AlertKind): String =
        if (kind == AlertKind.NEEDS_INPUT) BODY_NEEDS_INPUT else BODY_FINISHED

    fun format(kind: AlertKind, sample: PaneSample, detail: String?): Text =
        format(kind, sample.paneId, sample.workspace, sample.tab, sample.agent, sample.title, detail)

    fun format(
        kind: AlertKind,
        paneId: String,
        workspace: String?,
        tab: String?,
        agent: String?,
        paneTitle: String?,
        detail: String?,
    ): Text {
        val title = listOfNotNull(clean(workspace, LABEL_MAX), clean(tab, LABEL_MAX))
            .joinToString(" · ")
            .ifEmpty { clean(paneId, LABEL_MAX) ?: paneId }
        val status = if (kind == AlertKind.NEEDS_INPUT) BODY_NEEDS_INPUT else BODY_FINISHED
        val who = listOfNotNull(clean(agent, AGENT_MAX), clean(paneTitle, PANE_TITLE_MAX)).joinToString(" · ")
        val body = if (who.isEmpty()) status else "$who — $status"
        val line = firstLine(detail)
        return Text(title, body, if (line == null) body else "$body\n$line")
    }

    /** First non-blank line of [text], flattened and cut to [DETAIL_MAX]; null when there is none. */
    fun firstLine(text: String?): String? {
        val first = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        return clean(first, DETAIL_MAX)
    }

    /** One line: control characters and runs of whitespace become one space; cut to [max] with an ellipsis. */
    internal fun clean(value: String?, max: Int): String? {
        val flat = value?.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
            ?.joinToString("")
            ?.replace(Regex(" {2,}"), " ")
            ?.trim()
            ?.ifEmpty { null } ?: return null
        if (flat.length <= max) return flat
        return flat.take(max - 1).trimEnd() + "…"
    }
}
