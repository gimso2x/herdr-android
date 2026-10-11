package com.gimso2x.herdr.watch

/**
 * `herdr://pane/<percent-encoded pane id>`: the data URI of an alert's open-pane intent. Android
 * matches PendingIntents by request code and [android.content.Intent.filterEquals], which ignores
 * extras, so the complete pane id must live in a field filterEquals compares. Unlike
 * `String.hashCode()` (`w10P:p1` and `w111:p1` collide) the encoding is injective.
 */
object PaneLink {
    private const val PREFIX = "herdr://pane/"

    // Same unreserved set as android.net.Uri.encode: everything else is percent-encoded as UTF-8.
    private const val UNRESERVED = "_-!.~'()*"

    fun uri(paneId: String): String = PREFIX + encode(paneId)

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
