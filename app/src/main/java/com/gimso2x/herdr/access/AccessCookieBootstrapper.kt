package com.gimso2x.herdr.access

import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

fun interface CookieFetcher {
    suspend fun fetch(token: ServiceToken): BootstrapResult
}

/**
 * Exchanges a service token for a `CF_Authorization` cookie via `GET {base}/api/health`.
 *
 * Result mapping (malformed input never throws):
 * - 200 + `Set-Cookie` starting `CF_Authorization=` with a JWT value carrying numeric `exp` -> Success
 * - 200 without such a header (including a `Set-Cookie` lacking `=`, other cookies only, empty value)
 *   -> StrictModeOrNoCookie
 * - 200 with the cookie but value not a 3-part JWT, payload not base64url JSON, or no positive numeric
 *   `exp` -> Unexpected(200)
 * - 3xx with Location host `cloudflareaccess.com` or `*.cloudflareaccess.com`, 401, 403, or a token
 *   value that cannot be sent as an HTTP header (control characters) -> InvalidToken
 * - other 3xx / any other status -> Unexpected(code)
 * - IOException -> Network
 * Headers and cookie values are never logged.
 */
class AccessCookieBootstrapper(client: OkHttpClient, baseUrl: String) : CookieFetcher {
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val healthUrl = (baseUrl.trimEnd('/') + "/api/health").toHttpUrl()

    // Callers run on the main thread; the awaited response is classified and closed (closing may
    // read the remaining body or write a stream reset) on the IO dispatcher, or StrictMode throws
    // NetworkOnMainThreadException.
    override suspend fun fetch(token: ServiceToken): BootstrapResult = withContext(Dispatchers.IO) { fetchOnIo(token) }

    private suspend fun fetchOnIo(token: ServiceToken): BootstrapResult {
        val request = try {
            Request.Builder()
                .url(healthUrl)
                .get()
                .header("CF-Access-Client-Id", token.clientId)
                .header("CF-Access-Client-Secret", token.clientSecret)
                .build()
        } catch (_: IllegalArgumentException) {
            return BootstrapResult.InvalidToken
        }
        val response = try {
            client.newCall(request).await()
        } catch (_: IOException) {
            return BootstrapResult.Network
        }
        return response.use { classify(it) }
    }

    private fun classify(response: Response): BootstrapResult = when (val code = response.code) {
        200 -> parseCookie(response)
        401, 403 -> BootstrapResult.InvalidToken
        in 300..399 ->
            if (redirectsToAccess(response)) BootstrapResult.InvalidToken else BootstrapResult.Unexpected(code)
        else -> BootstrapResult.Unexpected(code)
    }

    private fun parseCookie(response: Response): BootstrapResult {
        val header = response.headers("Set-Cookie").firstOrNull { it.startsWith(COOKIE_PREFIX) }
            ?: return BootstrapResult.StrictModeOrNoCookie
        val value = header.substring(COOKIE_PREFIX.length).substringBefore(';').trim()
        if (value.isEmpty()) return BootstrapResult.StrictModeOrNoCookie
        val exp = jwtExpEpochSeconds(value) ?: return BootstrapResult.Unexpected(200)
        return BootstrapResult.Success(header, exp, response.headers.getDate("Date")?.time)
    }

    private fun redirectsToAccess(response: Response): Boolean {
        val location = response.header("Location") ?: return false
        val host = response.request.url.resolve(location)?.host ?: return false
        return host == ACCESS_DOMAIN || host.endsWith(".$ACCESS_DOMAIN")
    }

    private companion object {
        const val COOKIE_PREFIX = "CF_Authorization="
        const val ACCESS_DOMAIN = "cloudflareaccess.com"
    }
}

/** `exp` claim of an (unverified) JWT, or null when [jwt] is not a 3-part JWT with a positive numeric `exp`. */
internal fun jwtExpEpochSeconds(jwt: String): Long? {
    val parts = jwt.split('.')
    if (parts.size != 3) return null
    return try {
        val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        (payload.opt("exp") as? Number)?.toLong()?.takeIf { it > 0 }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JSONException) {
        null
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) response.close() else cont.resume(response)
        }
    })
}
