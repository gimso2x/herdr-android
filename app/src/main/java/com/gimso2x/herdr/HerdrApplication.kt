package com.gimso2x.herdr

import android.app.Application
import com.gimso2x.herdr.access.AccessCookieBootstrapper
import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.access.EncryptedTokenStore
import com.gimso2x.herdr.access.TokenStore
import com.gimso2x.herdr.shell.AccessTestHooks
import okhttp3.OkHttpClient

class HerdrApplication : Application() {
    // Lazy: nothing touches the keystore/network from Application.onCreate, and the base URL is
    // read only after any debug override has been installed.
    val tokenStore: TokenStore by lazy { EncryptedTokenStore(this) }

    val accessSession: AccessSession by lazy {
        val baseUrl = HerdrConfig.baseUrl
        AccessSession(
            fetcher = AccessCookieBootstrapper(OkHttpClient(), baseUrl),
            store = tokenStore,
            installer = CookieInstaller(baseUrl),
            clock = AccessTestHooks.clock ?: Clock.SYSTEM,
        )
    }
}
