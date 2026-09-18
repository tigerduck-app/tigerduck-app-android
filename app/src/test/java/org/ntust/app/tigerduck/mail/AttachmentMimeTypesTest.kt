package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveAttachmentMimeType] never reaches the real `android.webkit.MimeTypeMap` here -- these
 * tests supply a fake `extensionLookup` so the resolution stays exercisable on the plain JVM,
 * including the "m4a absent from MimeTypeMap on some API levels" case its extension-override
 * table exists to cover.
 */
class AttachmentMimeTypesTest {
    private val noKnownExtensions: (String) -> String? = { null }

    @Test
    fun `a known x- alias is mapped to the type Android actually registers`() {
        assertEquals("audio/mp4", resolveAttachmentMimeType("audio/x-m4a", "voicemail.m4a", noKnownExtensions))
        assertEquals("audio/mp4", resolveAttachmentMimeType("audio/m4a", "voicemail.m4a", noKnownExtensions))
    }

    @Test
    fun `a generic octet-stream declared type falls back to the extension override table`() {
        assertEquals(
            "audio/mp4",
            resolveAttachmentMimeType("application/octet-stream", "recording.m4a", noKnownExtensions),
        )
    }

    @Test
    fun `a missing extension override falls through to the injected MimeTypeMap lookup`() {
        val lookup: (String) -> String? = { ext -> if (ext == "pdf") "application/pdf" else null }
        assertEquals("application/pdf", resolveAttachmentMimeType("", "contract.pdf", lookup))
        assertEquals("application/pdf", resolveAttachmentMimeType("application/octet-stream", "contract.pdf", lookup))
    }

    @Test
    fun `a specific declared type is preserved`() {
        assertEquals("application/pdf", resolveAttachmentMimeType("application/pdf", "contract.pdf", noKnownExtensions))
        assertEquals("image/jpeg", resolveAttachmentMimeType("image/jpeg", "photo.jpg", noKnownExtensions))
    }

    @Test
    fun `parameters on the declared type are stripped and case is normalised`() {
        assertEquals("text/plain", resolveAttachmentMimeType("Text/Plain; charset=utf-8", "note.txt", noKnownExtensions))
    }

    @Test
    fun `an unknown extension with no declared type degrades safely to octet-stream`() {
        assertEquals("application/octet-stream", resolveAttachmentMimeType("", "mystery.xyz123", noKnownExtensions))
        assertEquals("application/octet-stream", resolveAttachmentMimeType("application/octet-stream", "no-extension-at-all", noKnownExtensions))
    }

    @Test
    fun `a blank declared type with no extension at all degrades safely`() {
        assertEquals("application/octet-stream", resolveAttachmentMimeType("   ", "noextension", noKnownExtensions))
    }
}
