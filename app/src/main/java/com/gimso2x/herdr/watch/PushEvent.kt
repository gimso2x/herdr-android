package com.gimso2x.herdr.watch

import java.util.UUID
import org.json.JSONObject

/** The public transport contains identifiers only; private text comes from the authenticated API. */
data class PushEvent(val eventId: String, val kind: AlertKind, val paneId: String, val origin: String, val sentAt: Long) {
    fun genericAlert() = Alert(paneId, kind, PaneSample(paneId, AgentStatus.UNKNOWN))

    companion object {
        const val TTL_MILLIS = 12 * 60 * 60 * 1000L
        private val fields = setOf("type", "event_id", "kind", "pane_id", "server_origin")

        fun decode(data: Map<String, String>, sentAt: Long, now: Long, origin: String): PushEvent? {
            if (data.keys != fields || data["type"] != "herdr_event" || data["server_origin"] != origin) return null
            val id = data["event_id"] ?: return null
            if (!isUuid(id)) return null
            val pane = data["pane_id"] ?: return null
            if (pane.isBlank() || pane.length > 256 || pane.any { it.isISOControl() }) return null
            val kind = when (data["kind"]) {
                "needs_input" -> AlertKind.NEEDS_INPUT
                "finished" -> AlertKind.FINISHED
                else -> return null
            }
            if (sentAt <= 0 || sentAt > now + 60_000 || now - sentAt >= TTL_MILLIS) return null
            return PushEvent(id, kind, pane, origin, sentAt)
        }

        private fun isUuid(id: String): Boolean = try {
            UUID.fromString(id).toString() == id.lowercase() && id.length == 36
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

/** Bounded, durable ledger: one newest event per pane and up to 1,000 delivery identities. */
class PushEventLedger(private val events: MutableMap<String, PushEvent> = linkedMapOf()) {
    fun accept(event: PushEvent, now: Long): Boolean {
        events.entries.removeAll { now - it.value.sentAt >= PushEvent.TTL_MILLIS }
        if (events.containsKey(event.eventId)) return false
        if (events.values.any { it.origin == event.origin && it.paneId == event.paneId && it.sentAt > event.sentAt }) return false
        events[event.eventId] = event
        while (events.size > 1000) events.remove(events.keys.first())
        return true
    }

    fun isCurrent(event: PushEvent): Boolean = events.values.lastOrNull {
        it.origin == event.origin && it.paneId == event.paneId
    }?.eventId == event.eventId

    fun all(): List<PushEvent> = events.values.toList()
}

object PushDetail {
    fun parse(body: String, event: PushEvent, now: Long): Alert? = try {
        val json = JSONObject(body)
        val kind = if (event.kind == AlertKind.NEEDS_INPUT) "needs_input" else "finished"
        val created = json.getLong("created_at")
        if (json.getString("event_id") != event.eventId || json.getString("pane_id") != event.paneId ||
            json.getString("kind") != kind || json.getString("server_origin") != event.origin ||
            created <= 0 || created > now + 60_000 || now - created >= PushEvent.TTL_MILLIS
        ) null else {
            fun text(key: String): String? = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
            Alert(event.paneId, event.kind, PaneSample(event.paneId, AgentStatus.UNKNOWN,
                text("workspace"), text("tab"), text("agent"), text("pane_title")), text("detail"),
                PaneLink.validMachineId(json.opt("machine_id") as? String) ?: PaneLink.LOCAL)
        }
    } catch (_: Exception) {
        null
    }
}
