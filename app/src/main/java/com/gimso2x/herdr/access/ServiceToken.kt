package com.gimso2x.herdr.access

/**
 * Cloudflare Access service-token pair. [toString] shows at most the first 4 characters of the
 * client id and never the secret, so accidental logging/interpolation cannot leak it.
 */
data class ServiceToken(val clientId: String, val clientSecret: String) {
    override fun toString(): String = "ServiceToken(${clientId.take(4)}…)"
}
