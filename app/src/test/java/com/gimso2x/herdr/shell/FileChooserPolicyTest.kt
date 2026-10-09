package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class FileChooserPolicyTest {
    @Test
    fun firstMimeTypeWins() {
        assertEquals("image/*", FileChooserPolicy.mimeType(arrayOf("image/*", "application/pdf")))
        assertEquals("application/pdf", FileChooserPolicy.mimeType(arrayOf("", "application/pdf")))
        assertEquals("image/png", FileChooserPolicy.mimeType(arrayOf("image/png,image/jpeg")))
    }

    @Test
    fun missingOrJunkFallsBackToAny() {
        assertEquals("*/*", FileChooserPolicy.mimeType(null))
        assertEquals("*/*", FileChooserPolicy.mimeType(emptyArray()))
        assertEquals("*/*", FileChooserPolicy.mimeType(arrayOf("")))
        assertEquals("*/*", FileChooserPolicy.mimeType(arrayOf(".pdf")))
        assertEquals("*/*", FileChooserPolicy.mimeType(arrayOf("not a mime")))
    }
}
