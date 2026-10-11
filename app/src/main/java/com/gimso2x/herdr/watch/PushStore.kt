package com.gimso2x.herdr.watch

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Cookies and FCM tokens never enter WorkManager input, unencrypted preferences, or logs. */
class PushStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext, "herdr_push", MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }

    var token: String?
        get() = prefs.getString("token", null)
        set(value) { check(prefs.edit().putString("token", value).commit()) }

    val installationId: String
        get() = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("installation", it).commit())
        }

    /** Saved before POST, so process death or disable during registration still gets a DELETE. */
    class Registration(val origin: String, val cookie: String, val expires: Long)

    fun registrations(): List<Registration> {
        val json = JSONArray(prefs.getString("registrations", "[]"))
        return (0 until json.length()).map { index ->
            val row = json.getJSONObject(index)
            Registration(row.getString("origin"), row.getString("cookie"), row.getLong("expires"))
        }
    }

    fun saveRegistrations(rows: List<Registration>) {
        val json = JSONArray()
        for (row in rows) json.put(JSONObject().put("origin", row.origin).put("cookie", row.cookie).put("expires", row.expires))
        check(prefs.edit().putString("registrations", json.toString()).commit())
    }

    fun ledger(): PushEventLedger {
        val json = JSONArray(prefs.getString("events", "[]"))
        val events = linkedMapOf<String, PushEvent>()
        for (index in 0 until json.length()) {
            val row = json.getJSONObject(index)
            val event = PushEvent(row.getString("id"), AlertKind.valueOf(row.getString("kind")),
                row.getString("pane"), row.getString("origin"), row.getLong("sent"))
            events[event.eventId] = event
        }
        return PushEventLedger(events)
    }

    fun saveLedger(ledger: PushEventLedger) {
        val json = JSONArray()
        for (event in ledger.all()) json.put(JSONObject().put("id", event.eventId).put("kind", event.kind.name)
            .put("pane", event.paneId).put("origin", event.origin).put("sent", event.sentAt))
        check(prefs.edit().putString("events", json.toString()).commit())
    }

    fun clearEvents() { check(prefs.edit().remove("events").commit()) }

    /** Desired-state changes, ledger writes and notification updates share this process lock. */
    companion object { val lock = Any() }
}
