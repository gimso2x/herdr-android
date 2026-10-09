package com.gimso2x.herdr.access

sealed interface BootstrapResult {
    /** [setCookieHeader] is the full `Set-Cookie` header value; it is never printed by [toString]. */
    data class Success(val setCookieHeader: String, val expEpochSeconds: Long) : BootstrapResult {
        override fun toString(): String = "Success(exp=$expEpochSeconds)"
    }

    /** Cloudflare rejected the service token (3xx to *.cloudflareaccess.com, 401, 403). */
    data object InvalidToken : BootstrapResult

    /** 200 but no usable `CF_Authorization` Set-Cookie (e.g. strict service-token mode is on). */
    data object StrictModeOrNoCookie : BootstrapResult

    /** Transport failure (IOException). */
    data object Network : BootstrapResult

    /** Anything else, including a 200 whose cookie is not a JWT with a numeric `exp`. */
    data class Unexpected(val code: Int) : BootstrapResult
}
