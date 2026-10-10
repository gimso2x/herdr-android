package com.gimso2x.herdr.watch

import java.util.Locale

/** `AgentStatus` of herdr's API (`blocked|done|idle|unknown|working`); anything else maps to [UNKNOWN]. */
enum class AgentStatus {
    BLOCKED,
    DONE,
    IDLE,
    UNKNOWN,
    WORKING,
    ;

    val busy: Boolean get() = this == WORKING || this == BLOCKED

    companion object {
        fun parse(raw: String?): AgentStatus = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "blocked" -> BLOCKED
            "done" -> DONE
            "idle" -> IDLE
            "working" -> WORKING
            else -> UNKNOWN
        }
    }
}

/**
 * One pane as seen by one poll. Every label is optional: [workspace] and [tab] are the labels of the
 * pane's workspace and tab, [agent] the agent's display name, [title] the pane's terminal title.
 */
data class PaneSample(
    val paneId: String,
    val status: AgentStatus,
    val workspace: String? = null,
    val tab: String? = null,
    val agent: String? = null,
    val title: String? = null,
)

enum class AlertKind { NEEDS_INPUT, FINISHED }

/** An alert for [sample]; [detail] is the optional first line of the prompt / last answer (see [AlertDetail]). */
data class Alert(val paneId: String, val kind: AlertKind, val sample: PaneSample, val detail: String? = null) {
    private val text: AlertText.Text get() = AlertText.format(kind, sample, detail)
    val title: String get() = text.title
    val body: String get() = text.body
    val bigText: String get() = text.bigText
}

enum class PollOutcome { NONE, OK, AUTH, NETWORK, HTTP_ERROR, PARSE_ERROR, NOT_RUNNING }

/** [sequence] increases with every poll so equal outcomes still emit on a StateFlow. */
data class PollResult(
    val sequence: Long,
    val outcome: PollOutcome,
    val paneCount: Int = 0,
    val alertCount: Int = 0,
) {
    companion object {
        val NONE = PollResult(0, PollOutcome.NONE)
    }
}
