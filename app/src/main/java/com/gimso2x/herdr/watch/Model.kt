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

/** One pane as seen by one poll. [label] is already workspace-prefixed. */
data class PaneSample(val paneId: String, val status: AgentStatus, val label: String)

enum class AlertKind { NEEDS_INPUT, FINISHED }

data class Alert(val paneId: String, val kind: AlertKind, val title: String, val body: String)

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
