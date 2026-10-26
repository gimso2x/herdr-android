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
 * One pane's optional labels from an authenticated push event detail.
 * [workspace] and [tab] are display labels; [agent] and [title] describe the pane.
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

/** Existing alert presentation model; private [detail] is supplied by the authenticated event API. */
data class Alert(
    val paneId: String,
    val kind: AlertKind,
    val sample: PaneSample,
    val detail: String? = null,
    /** herdr machine that owns the pane; [PaneLink.LOCAL] unless the bridge detail names a remote one. */
    val machineId: String = PaneLink.LOCAL,
) {
    private val text: AlertText.Text get() = AlertText.format(kind, sample, detail)
    val title: String get() = text.title
    val body: String get() = text.body
    val bigText: String get() = text.bigText
}
