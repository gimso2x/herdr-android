package com.gimso2x.herdr.qa

import com.gimso2x.herdr.access.ServiceToken
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Live-site helpers: the CI token (pushed by ci/instrumented.sh) and read-only GETs. */
object LiveSupport {
    private const val TOKEN_FILE = "/data/local/tmp/qa-token.json"

    /**
     * Reads the token the harness pushed. Never echoes any part of the file: a missing or
     * malformed file is a hard failure (live scenarios must not silently pass).
     */
    fun token(): ServiceToken {
        val text = Qa.shell("cat $TOKEN_FILE")
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw AssertionError("qa-token.json is missing or not JSON")
        }
        if (!json.optBoolean("liveQa", false)) throw AssertionError("qa-token.json does not enable liveQa")
        val id = json.optString("id")
        val secret = json.optString("secret")
        if (id.isEmpty() || secret.isEmpty()) throw AssertionError("qa-token.json lacks id/secret")
        return ServiceToken(id, secret)
    }

    /**
     * The live server origin from the same file (`baseUrl`, https only). The address is a CI secret:
     * it is never echoed, a missing or invalid value is a hard failure.
     */
    fun baseUrl(): String {
        val json = try {
            JSONObject(Qa.shell("cat $TOKEN_FILE"))
        } catch (_: Exception) {
            throw AssertionError("qa-token.json is missing or not JSON")
        }
        return com.gimso2x.herdr.access.ServerAddress.parse(json.optString("baseUrl"))
            ?: throw AssertionError("qa-token.json lacks a valid https baseUrl")
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** GET [path] on the live site with the token headers; returns the body of a 200. */
    fun get(path: String, token: ServiceToken): String {
        val request = Request.Builder()
            .url(Qa.LIVE + path)
            .get()
            .header("CF-Access-Client-Id", token.clientId)
            .header("CF-Access-Client-Secret", token.clientSecret)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) throw AssertionError("GET $path answered ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    /** `snapshot.workspaces[0].label` of the live `/api/session`. */
    fun firstWorkspaceLabel(token: ServiceToken): String {
        val body = get("/api/session", token)
        val label = JSONObject(body).getJSONObject("snapshot").getJSONArray("workspaces").getJSONObject(0).getString("label")
        if (label.isBlank()) throw AssertionError("first workspace has a blank label")
        return label
    }
}
