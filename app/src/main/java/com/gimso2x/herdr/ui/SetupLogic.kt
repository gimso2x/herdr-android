package com.gimso2x.herdr.ui

import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.ServiceToken

/** Why setup could not finish. The UI maps each value to a Korean string resource. */
enum class SetupError {
    REJECTED,
    NO_COOKIE,
    NETWORK,
    UNEXPECTED,
    STORE_FAILED,
    ;

    companion object {
        /** Parses an error extra; unknown or missing values yield null instead of throwing. */
        fun fromExtra(value: String?): SetupError? = entries.firstOrNull { it.name == value }
    }
}

/** Pure form/mapping rules for [SetupActivity] and [SettingsActivity]; no Android types. */
object SetupLogic {
    /** Fields longer than this are refused as typed/pasted (real ids/secrets are well under 100). */
    const val MAX_FIELD_LENGTH = 512

    fun accepts(input: String): Boolean = input.length <= MAX_FIELD_LENGTH

    /** The trimmed token, or null while either field is blank or too long. */
    fun credentials(clientId: String, clientSecret: String): ServiceToken? {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isEmpty() || secret.isEmpty()) return null
        if (id.length > MAX_FIELD_LENGTH || secret.length > MAX_FIELD_LENGTH) return null
        return ServiceToken(id, secret)
    }

    /** Null for [BootstrapResult.Success]. */
    fun errorFor(result: BootstrapResult): SetupError? = when (result) {
        is BootstrapResult.Success -> null
        BootstrapResult.InvalidToken -> SetupError.REJECTED
        BootstrapResult.StrictModeOrNoCookie -> SetupError.NO_COOKIE
        BootstrapResult.Network -> SetupError.NETWORK
        is BootstrapResult.Unexpected -> SetupError.UNEXPECTED
    }

    /** First 4 characters of the client id followed by an ellipsis; never more of the id. */
    fun maskClientId(clientId: String): String = clientId.trim().take(4) + "…"
}
