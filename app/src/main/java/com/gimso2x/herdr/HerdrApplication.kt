package com.gimso2x.herdr

import android.app.Application
import android.webkit.WebStorage
import com.gimso2x.herdr.access.AccessCookieBootstrapper
import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.access.CookieFetcher
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.access.EncryptedTokenStore
import com.gimso2x.herdr.access.TokenStore
import com.gimso2x.herdr.shell.AccessTestHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/** Why the running Access session was dropped. */
enum class SessionEnd {
    /** The service token was removed: the page must go and setup is needed. */
    SIGN_OUT,

    /** The server address changed: the old origin's page and storage must go, the new one loads on resume. */
    SERVER_CHANGE,
}

/** [serial] makes every signal distinct for a StateFlow collector. */
data class SessionEndEvent(val serial: Int, val kind: SessionEnd?)

class HerdrApplication : Application() {
    // Lazy: nothing touches the keystore/network from Application.onCreate; the stored address is
    // read the first time a base URL is asked for.
    val tokenStore: TokenStore by lazy { EncryptedTokenStore(this) }

    private val _sessionEnd = MutableStateFlow(SessionEndEvent(0, null))

    /** Emits on every [endSession]; MainActivity wipes its WebView state when it changes. */
    val sessionEnd: StateFlow<SessionEndEvent> = _sessionEnd

    private val sessionLock = Any()
    private var session: Pair<String, AccessSession>? = null

    override fun onCreate() {
        super.onCreate()
        HerdrConfig.install { tokenStore.getServer() }
    }

    /**
     * Drops the cached Access result and the web storage of the signed-out/old server, then tells the
     * running MainActivity. Call on the main thread (WebStorage), after the token/address was changed.
     */
    fun endSession(kind: SessionEnd) {
        accessSession.invalidate()
        WebStorage.getInstance().deleteAllData()
        _sessionEnd.update { SessionEndEvent(it.serial + 1, kind) }
    }

    /**
     * The cookie session for the current base URL; a changed address (or a debug override) gets a
     * fresh session, so no cookie or expiry of the old server is ever reused.
     */
    val accessSession: AccessSession
        get() = synchronized(sessionLock) {
            val baseUrl = HerdrConfig.baseUrl
            session?.takeIf { it.first == baseUrl }?.second ?: AccessSession(
                fetcher = if (baseUrl.isEmpty()) {
                    CookieFetcher { BootstrapResult.InvalidToken } // no address yet: nothing to ask
                } else {
                    AccessCookieBootstrapper(OkHttpClient(), baseUrl)
                },
                store = tokenStore,
                installer = CookieInstaller(baseUrl),
                clock = AccessTestHooks.clock ?: Clock.SYSTEM,
            ).also { session = baseUrl to it }
        }
}
