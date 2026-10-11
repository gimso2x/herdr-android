package com.gimso2x.herdr.shell

import java.util.Locale

/** What to do with a WebView download. */
sealed interface DownloadDecision {
    /** Hand to DownloadManager with the session cookie (base origin only). */
    data class Enqueue(val url: String, val fileName: String, val mimeType: String) : DownloadDecision

    /** Not ours: open with ACTION_VIEW, no cookie, no DownloadManager. */
    data class External(val url: String) : DownloadDecision

    data object Block : DownloadDecision
}

/**
 * Pure download policy. Only an exact base-origin (scheme + host + port, no userinfo) http(s) URL
 * is downloaded in-app, so the Access cookie is attached to nothing else. [guessFileName] is
 * URLUtil.guessFileName on device; its result is never trusted and is always sanitized.
 */
object DownloadPolicy {
    const val FALLBACK_MIME = "application/octet-stream"
    const val FALLBACK_NAME = "download"
    private const val MAX_NAME = 120
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")

    fun decide(
        url: String?,
        contentDisposition: String?,
        mimeType: String?,
        baseUrl: String,
        guessFileName: (String, String?, String?) -> String,
    ): DownloadDecision {
        if (url.isNullOrBlank()) return DownloadDecision.Block
        val trimmed = url.trim()
        if (!isWebUrl(trimmed)) return DownloadDecision.Block
        if (!BaseOrigin.matches(trimmed, baseUrl)) return DownloadDecision.External(trimmed)
        val guessed = try {
            guessFileName(trimmed, contentDisposition, mimeType)
        } catch (_: Exception) {
            FALLBACK_NAME
        }
        return DownloadDecision.Enqueue(trimmed, sanitizeFileName(guessed), normalizeMime(mimeType))
    }

    /** Last path segment only, no separators/control chars/leading dots, bounded length. */
    fun sanitizeFileName(raw: String?): String {
        if (raw.isNullOrBlank()) return FALLBACK_NAME
        val lastSegment = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = lastSegment.replace(UNSAFE, "_").trim().trimStart('.', ' ').trimEnd('.', ' ')
        if (cleaned.isEmpty()) return FALLBACK_NAME
        return if (cleaned.length > MAX_NAME) cleaned.takeLast(MAX_NAME).trimStart('.', ' ').ifEmpty { FALLBACK_NAME } else cleaned
    }

    private const val MAX_UNIQUE_TRIES = 9_999

    /**
     * [name] if [exists] says it is free, else `base (1).ext`, `base (2).ext`, ... (readable basename and
     * extension kept, total length bounded). DownloadManager never overwrites and fails a request whose
     * destination exists, so every enqueue goes through this.
     */
    fun uniqueFileName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        fun candidate(suffix: String): String {
            val room = (MAX_NAME - ext.length - suffix.length).coerceAtLeast(1)
            return base.take(room) + suffix + ext
        }
        for (i in 1..MAX_UNIQUE_TRIES) {
            val next = candidate(" ($i)")
            if (!exists(next)) return next
        }
        return candidate(" (${java.util.UUID.randomUUID().toString().take(8)})")
    }

    fun normalizeMime(raw: String?): String {
        val base = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (base.matches(MIME)) base else FALLBACK_MIME
    }

    private val MIME = Regex("""[a-z0-9][a-z0-9!#&^_.+-]*/[a-z0-9][a-z0-9!#&^_.+-]*""")

    private fun isWebUrl(url: String): Boolean = WebUrls.parse(url) != null
}
