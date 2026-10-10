package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPolicyTest {
    private val base = "https://herdr.example.com"
    private val mic = listOf(PermissionPolicy.AUDIO_CAPTURE)

    private fun decide(
        origin: String?,
        resources: List<String>,
        granted: Boolean,
        baseUrl: String = base,
    ) = PermissionPolicy.decide(origin, resources, baseUrl, granted)

    @Test
    fun audioConstantMatchesWebKit() {
        assertEquals("android.webkit.resource.AUDIO_CAPTURE", PermissionPolicy.AUDIO_CAPTURE)
    }

    @Test
    fun micForBaseOriginAfterRuntimeGrantIsGranted() {
        assertEquals(PermissionDecision.Grant(mic), decide("https://herdr.example.com/", mic, true))
        assertEquals(PermissionDecision.Grant(mic), decide("https://herdr.example.com", mic, true))
        assertEquals(PermissionDecision.Grant(mic), decide("HTTPS://Herdr.example.com:443/x", mic, true))
    }

    @Test
    fun micBeforeRuntimeGrantAsksFirst() {
        assertEquals(PermissionDecision.RequestRuntime, decide("https://herdr.example.com/", mic, false))
    }

    @Test
    fun cameraGeolocationAndProtectedMediaAreDenied() {
        val origin = "https://herdr.example.com/"
        for (granted in listOf(true, false)) {
            assertEquals(PermissionDecision.Deny, decide(origin, listOf("android.webkit.resource.VIDEO_CAPTURE"), granted))
            assertEquals(PermissionDecision.Deny, decide(origin, listOf("android.webkit.resource.PROTECTED_MEDIA_ID"), granted))
            assertEquals(PermissionDecision.Deny, decide(origin, listOf("android.webkit.resource.MIDI_SYSEX"), granted))
            assertEquals(PermissionDecision.Deny, decide(origin, listOf("geolocation"), granted))
            assertEquals(PermissionDecision.Deny, decide(origin, listOf("anything-else"), granted))
        }
    }

    @Test
    fun micMixedWithAnythingElseIsDeniedWhole() {
        val mixed = listOf(PermissionPolicy.AUDIO_CAPTURE, "android.webkit.resource.VIDEO_CAPTURE")
        assertEquals(PermissionDecision.Deny, decide("https://herdr.example.com/", mixed, true))
        assertEquals(PermissionDecision.Deny, decide("https://herdr.example.com/", mixed, false))
    }

    @Test
    fun emptyRequestIsDenied() {
        assertEquals(PermissionDecision.Deny, decide("https://herdr.example.com/", emptyList(), true))
    }

    @Test
    fun foreignOriginsAreDenied() {
        val origins = listOf(
            "https://evil.example/",
            "https://herdr.example.com.evil.example/",
            "https://evilherdr.example.com/",
            "https://team.cloudflareaccess.com/",
            "http://herdr.example.com/",
            "https://herdr.example.com:8443/",
            "https://user@herdr.example.com/",
            "https://herdr.example.com@evil.example/",
        )
        for (origin in origins) {
            assertEquals(origin, PermissionDecision.Deny, decide(origin, mic, true))
        }
    }

    @Test
    fun malformedOriginIsDenied() {
        for (origin in listOf(null, "", "   ", "not a url", "file:///x", "about:blank", "javascript:alert(1)", "https://")) {
            assertEquals(origin, PermissionDecision.Deny, decide(origin, mic, true))
        }
    }

    @Test
    fun debugFixtureBaseIsHonoured() {
        val fixture = "http://127.0.0.1:41234"
        assertEquals(PermissionDecision.Grant(mic), decide("http://127.0.0.1:41234/", mic, true, fixture))
        assertEquals(PermissionDecision.Deny, decide("http://127.0.0.1:9/", mic, true, fixture))
        // the real host is not the base while an override is active
        assertEquals(PermissionDecision.Deny, decide("https://herdr.example.com/", mic, true, fixture))
    }

    @Test
    fun unparsableBaseDeniesEverything() {
        assertEquals(PermissionDecision.Deny, decide("https://herdr.example.com/", mic, true, "garbage"))
    }
}
