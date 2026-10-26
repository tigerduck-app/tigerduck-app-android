package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the upload sheet offers for a page's file field. */
class BrowserUploadTest {

    private val mimes = mapOf("pdf" to "application/pdf", "png" to "image/png", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

    @Test
    fun `an image field gets photos, the camera and files`() {
        listOf(listOf("image/*"), listOf(".jpg,.png"), listOf("image/png", ".heic")).forEach {
            assertEquals(it.toString(), listOf(UploadSource.PHOTOS, UploadSource.CAMERA, UploadSource.FILES), BrowserUpload.sources(it))
            assertTrue(it.toString(), BrowserUpload.wantsImagesOnly(it))
        }
    }

    @Test
    fun `a field that takes anything gets every source but does not ask for an image`() {
        listOf(emptyList(), listOf(""), listOf("*/*"), listOf<String?>(null)).forEach {
            assertEquals(it.toString(), listOf(UploadSource.PHOTOS, UploadSource.CAMERA, UploadSource.FILES), BrowserUpload.sources(it))
            assertFalse(it.toString(), BrowserUpload.wantsImagesOnly(it))
        }
    }

    @Test
    fun `a field for other files gets files alone, and one with images among them every source`() {
        assertEquals(listOf(UploadSource.FILES), BrowserUpload.sources(listOf(".pdf", "application/msword")))
        assertEquals(listOf(UploadSource.PHOTOS, UploadSource.CAMERA, UploadSource.FILES), BrowserUpload.sources(listOf(".pdf,image/*")))
        assertFalse(BrowserUpload.wantsImagesOnly(listOf(".pdf,image/*")))
    }

    @Test
    fun `the document picker gets each type, extensions as their MIME types`() {
        assertArrayEquals(
            arrayOf("application/pdf", "image/*"),
            BrowserUpload.documentTypes(listOf(".pdf, image/*", ".PDF")) { mimes[it] },
        )
        assertArrayEquals(arrayOf("*/*"), BrowserUpload.documentTypes(emptyList()) { mimes[it] })
        assertArrayEquals(arrayOf("*/*"), BrowserUpload.documentTypes(listOf(".unknownext")) { mimes[it] })
    }
}
