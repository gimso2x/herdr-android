package com.gimso2x.herdr.shell

/** Pure choice of the MIME type handed to the system picker. */
object FileChooserPolicy {
    const val ANY = "*/*"
    private val MIME = Regex("""[A-Za-z0-9*][\w.+*-]*/[A-Za-z0-9*][\w.+*-]*""")

    /** First accept type if it is a MIME type; extensions (".pdf"), blanks and junk fall back to any. */
    fun mimeType(acceptTypes: Array<String>?): String {
        val first = acceptTypes?.firstOrNull { it.isNotBlank() }
            ?.substringBefore(',')?.trim().orEmpty()
        return if (first.matches(MIME)) first else ANY
    }
}
