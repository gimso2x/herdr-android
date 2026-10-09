package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock

/**
 * Turns poll samples into alerts, following herdr-web-ui's defaults (`server/push.ts`):
 * - the first sample of a pane only seeds it (a turn already running has an unknown start);
 * - a measured turn starts when a pane is sampled busy (`working`/`blocked`) after a non-busy sample;
 * - sampled entry into `blocked` -> [AlertKind.NEEDS_INPUT], once per blocked episode;
 * - busy -> `done`/`idle` after a measured turn of at least [MIN_TURN_MILLIS] -> [AlertKind.FINISHED];
 *   a turn with unknown start never produces one;
 * - `unknown` samples are ignored; panes missing from a poll are forgotten.
 * Only sampled states count: a transition that happens between two polls is invisible.
 * Not thread-safe; callers serialize [update].
 */
class StatusTracker(private val clock: Clock = Clock.SYSTEM) {
    private class PaneState(var last: AgentStatus, var turnStart: Long?)

    private val panes = HashMap<String, PaneState>()

    fun update(samples: List<PaneSample>): List<Alert> {
        panes.keys.retainAll(samples.mapTo(HashSet()) { it.paneId })
        val alerts = ArrayList<Alert>()
        for (sample in samples) {
            if (sample.status == AgentStatus.UNKNOWN) continue
            val state = panes[sample.paneId]
            if (state == null) {
                panes[sample.paneId] = PaneState(sample.status, null)
                continue
            }
            val now = clock.nowMillis()
            val previous = state.last
            if (sample.status.busy) {
                if (!previous.busy) state.turnStart = now
                if (sample.status == AgentStatus.BLOCKED && previous != AgentStatus.BLOCKED) {
                    alerts += Alert(sample.paneId, AlertKind.NEEDS_INPUT, sample.label, BODY_NEEDS_INPUT)
                }
            } else if (previous.busy) {
                val start = state.turnStart
                state.turnStart = null
                if (start != null && now - start >= MIN_TURN_MILLIS) {
                    alerts += Alert(sample.paneId, AlertKind.FINISHED, sample.label, BODY_FINISHED)
                }
            }
            state.last = sample.status
        }
        return alerts
    }

    /** Forget everything; the next sample of each pane seeds again. */
    fun reset() = panes.clear()

    companion object {
        const val MIN_TURN_MILLIS = 60_000L
        const val BODY_NEEDS_INPUT = "입력이 필요합니다"
        const val BODY_FINISHED = "작업을 마쳤습니다"
    }
}
