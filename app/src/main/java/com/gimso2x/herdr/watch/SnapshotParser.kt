package com.gimso2x.herdr.watch

import org.json.JSONException
import org.json.JSONObject

/** Reads `GET /api/session` bodies. Never throws: malformed input yields null or skips the entry. */
object SnapshotParser {
    /**
     * Returns the panes of `snapshot.panes[]`, or null when the body is not a usable snapshot
     * (so a broken response never makes the tracker forget every pane). An empty `panes` array is
     * valid and yields an empty list. Panes without a `pane_id` are skipped.
     */
    fun parse(body: String): List<PaneSample>? {
        val snapshot = try {
            JSONObject(body).optJSONObject("snapshot")
        } catch (_: JSONException) {
            null
        } ?: return null
        val panes = snapshot.optJSONArray("panes") ?: return null

        val workspaceLabels = HashMap<String, String>()
        val workspaces = snapshot.optJSONArray("workspaces")
        if (workspaces != null) {
            for (i in 0 until workspaces.length()) {
                val ws = workspaces.optJSONObject(i) ?: continue
                val id = ws.text("workspace_id") ?: continue
                val label = ws.text("label") ?: continue
                workspaceLabels[id] = label
            }
        }

        val tabLabels = HashMap<String, String>()
        val tabs = snapshot.optJSONArray("tabs")
        if (tabs != null) {
            for (i in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(i) ?: continue
                val id = tab.text("tab_id") ?: continue
                val label = tab.text("label") ?: continue
                tabLabels[id] = label
            }
        }

        val samples = ArrayList<PaneSample>(panes.length())
        for (i in 0 until panes.length()) {
            val pane = panes.optJSONObject(i) ?: continue
            val paneId = pane.text("pane_id") ?: continue
            samples += PaneSample(
                paneId = paneId,
                status = AgentStatus.parse(pane.text("agent_status")),
                workspace = pane.text("workspace_id")?.let { workspaceLabels[it] },
                tab = pane.text("tab_id")?.let { tabLabels[it] },
                agent = pane.text("display_agent") ?: pane.text("agent"),
                title = pane.text("terminal_title_stripped") ?: pane.text("title"),
            )
        }
        return samples
    }

    /** Trimmed string value, or null when absent, JSON null or blank (org.json would give "null"). */
    private fun JSONObject.text(key: String): String? {
        if (isNull(key)) return null
        return optString(key).trim().ifEmpty { null }
    }
}
