package com.gimso2x.herdr.access

import java.util.Base64

/** Unsigned JWT fixtures (alg none, empty signature). Every payload carries "iss":"qa-fixture". */
object TestJwt {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun b64(text: String): String = encoder.encodeToString(text.toByteArray(Charsets.UTF_8))

    fun unsigned(exp: Long?): String {
        val header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}")
        val claims = if (exp == null) "{\"iss\":\"qa-fixture\"}" else "{\"iss\":\"qa-fixture\",\"exp\":$exp}"
        return "$header.${b64(claims)}."
    }

    fun withRawPayload(rawPayload: String): String =
        "${b64("{\"alg\":\"none\"}")}.${b64(rawPayload)}."

    fun setCookie(jwt: String): String = "CF_Authorization=$jwt; Path=/; Secure; HttpOnly; SameSite=Lax"
}
