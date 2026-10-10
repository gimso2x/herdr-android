package com.gimso2x.herdr.watch

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Optional extra line for an alert, from two herdr-web-ui endpoints that are plain GETs:
 * - `GET /api/pane/prompt?pane_id=` (blocked): the interactive prompt on the pane's screen
 *   (server/prompt.ts handlePromptRequest: no key is sent, the pane is only read);
 * - `GET /api/pane/conversation?pane_id=` (done): the pane's transcript page (server/index.ts, read-only).
 * One request, [TIMEOUT_MS] timeout, response capped at [MAX_BODY]; any failure yields null so the
 * alert is simply sent without the extra line. Never a non-GET request.
 */
object AlertDetail {
    const val TIMEOUT_MS = 5_000L
    const val MAX_BODY = 1_048_576L

    suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
        cookie: String,
        kind: AlertKind,
        paneId: String,
    ): String? {
        val path = if (kind == AlertKind.NEEDS_INPUT) "/api/pane/prompt" else "/api/pane/conversation"
        val url = "$baseUrl$path".toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("pane_id", paneId)?.build()
            ?: return null
        val request = Request.Builder().url(url).get().header("Cookie", cookie).build()
        val bounded = client.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        return try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    bounded.newCall(request).execute().use { response ->
                        if (response.code != 200) return@use null
                        val source = response.body?.source() ?: return@use null
                        if (source.request(MAX_BODY + 1)) return@use null // too big to be worth it
                        val body = source.buffer.readUtf8()
                        if (kind == AlertKind.NEEDS_INPUT) fromPrompt(body) else fromConversation(body)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** `{"prompt":{"question":...,"title":...}|null}` -> the question (else the title) as one line. */
    fun fromPrompt(body: String): String? {
        val prompt = try {
            JSONObject(body).optJSONObject("prompt")
        } catch (_: Exception) {
            null
        } ?: return null
        return AlertText.firstLine(prompt.text("question") ?: prompt.text("title"))
    }

    /** `{"turns":[{"role","parts":[{"kind":"text","text"}]}]}` -> first line of the last assistant text. */
    fun fromConversation(body: String): String? {
        val turns = try {
            JSONObject(body).optJSONArray("turns")
        } catch (_: Exception) {
            null
        } ?: return null
        for (i in turns.length() - 1 downTo 0) {
            val turn = turns.optJSONObject(i) ?: continue
            if (turn.optString("role") != "assistant") continue
            val parts = turn.optJSONArray("parts") ?: continue
            for (j in parts.length() - 1 downTo 0) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("kind") != "text") continue
                AlertText.firstLine(part.text("text"))?.let { return it }
            }
            return null // the newest assistant turn has no text: do not reach back to an older answer
        }
        return null
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}
