package com.gimso2x.herdr.watch

/**
 * `herdr://pane/<percent-encoded pane id>[?machine=<machine id>]`: the data URI of an alert's
 * open-pane intent; a local pane has no query, so its URI is the same as before machines existed. Android
 * matches PendingIntents by request code and [android.content.Intent.filterEquals], which ignores
 * extras, so the complete pane id must live in a field filterEquals compares. Unlike
 * `String.hashCode()` (`w10P:p1` and `w111:p1` collide) the encoding is injective.
 */
object PaneLink {
    private const val PREFIX = "herdr://pane/"

    // Same unreserved set as android.net.Uri.encode: everything else is percent-encoded as UTF-8.
    private const val UNRESERVED = "_-!.~'()*"

    const val LOCAL = "local"
    private const val MACHINE_QUERY = "?machine="
    private val MACHINE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun uri(paneId: String, machineId: String = LOCAL): String =
        PREFIX + encode(paneId) + if (machineId == LOCAL) "" else MACHINE_QUERY + machineId

    /** [raw] when it is a well-formed machine id, else null. */
    fun validMachineId(raw: String?): String? = raw?.takeIf { MACHINE_ID.matches(it) }

    /** Machine id carried by an [uri] result; [LOCAL] when absent, malformed or not a pane link. */
    fun machineOf(uri: String?): String {
        if (uri == null || !uri.startsWith(PREFIX)) return LOCAL
        // Encoded pane ids never contain '?', so the first one starts the machine query.
        val query = uri.substringAfter('?', "")
        if (!query.startsWith("machine=")) return LOCAL
        return validMachineId(query.removePrefix("machine=")) ?: LOCAL
    }

    /**
     * Notification tag of a pane: the bare pane id for the local machine (as before), else machine
     * and pane joined by U+001F. Injective by construction: a remote key starts with a machine id
     * ([MACHINE_ID] characters only, never a control char) and contains U+001F; a bare local key
     * contains no control char; a local id that does contain one is prefixed with U+0018 (CAN), so it
     * starts with a control char and cannot equal a bare or remote key.
     */
    fun alertKey(paneId: String, machineId: String = LOCAL): String = when {
        machineId != LOCAL -> "$machineId\u001F$paneId"
        paneId.any { it.isISOControl() } -> "\u0018$paneId"
        else -> paneId
    }

    internal fun encode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED) {
                out.append(c)
            } else {
                out.append('%').append("0123456789ABCDEF"[byte.toInt() shr 4 and 0xF]).append("0123456789ABCDEF"[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }
}
