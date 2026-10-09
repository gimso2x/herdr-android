package com.gimso2x.herdr.shell

/** What to do with a WebView permission request. */
sealed interface PermissionDecision {
    /** Grant exactly [resources]. */
    data class Grant(val resources: List<String>) : PermissionDecision

    /** Allowed in principle; ask for the Android runtime permission first. */
    data object RequestRuntime : PermissionDecision

    data object Deny : PermissionDecision
}

/**
 * Pure WebView permission policy. The only grantable capability is microphone capture
 * ([AUDIO_CAPTURE]) for the base origin, and only once Android's RECORD_AUDIO is granted. Camera,
 * geolocation, protected media, MIDI, mixed requests and every other origin are denied.
 */
object PermissionPolicy {
    /** Value of android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE. */
    const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    fun decide(
        origin: String?,
        resources: List<String>,
        baseUrl: String,
        recordAudioGranted: Boolean,
    ): PermissionDecision {
        if (!BaseOrigin.matches(origin, baseUrl)) return PermissionDecision.Deny
        // Exactly the microphone: a request that also wants anything else is refused whole.
        if (resources.isEmpty() || resources.any { it != AUDIO_CAPTURE }) return PermissionDecision.Deny
        return if (recordAudioGranted) {
            PermissionDecision.Grant(listOf(AUDIO_CAPTURE))
        } else {
            PermissionDecision.RequestRuntime
        }
    }
}
