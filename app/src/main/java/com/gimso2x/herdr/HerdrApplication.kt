package com.gimso2x.herdr

import android.app.Application
import com.gimso2x.herdr.access.AccessCookieBootstrapper
import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.access.CookieFetcher
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.access.EncryptedTokenStore
import com.gimso2x.herdr.access.TokenStore
import com.gimso2x.herdr.shell.AccessTestHooks
import okhttp3.OkHttpClient

class HerdrApplication : Application() {
    // Lazy: nothing touches the keystore/network from Application.onCreate; the stored address is
    // read the first time a base URL is asked for.
    val tokenStore: TokenStore by lazy { EncryptedTokenStore(this) }

    private val sessionLock = Any()
    private var session: Pair<String, AccessSession>? = null

    override fun onCreate() {
        super.onCreate()
        HerdrConfig.install { tokenStore.getServer() }
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
