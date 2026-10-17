package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PickedFileNameTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val taken = ZonedDateTime.of(2026, 10, 15, 14, 30, 12, 0, zone).toInstant().toEpochMilli()
    private val picked = ZonedDateTime.of(2026, 10, 17, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
    private val extensions = { type: String -> mapOf("image/jpeg" to "jpg", "image/png" to "png")[type] }

    private fun name(displayName: String, type: String, dateTaken: Long? = taken) =
        pickedFileName(displayName, type, { dateTaken }, { picked }, zone, extensions)

    @Test
    fun `a photo the picker names by media id is named by its capture date, with the extension of its type`() {
        assertEquals("IMG_20261015_143012.jpg", name("34.jpg", "image/jpeg"))
        assertEquals("IMG_20261015_143012.png", name("1000000034.png", "image/png"))
        // The type decides the extension, not whatever the provider put after the id.
        assertEquals("IMG_20261015_143012.jpg", name("34.png", "image/jpeg"))
        assertEquals("IMG_20261015_143012.jpg", name("34", "image/jpeg"))
    }

    @Test
    fun `with no capture date the moment it was picked names it`() {
        assertEquals("IMG_20261017_090500.jpg", name("34.jpg", "image/jpeg", dateTaken = null))
    }

    @Test
    fun `a type with no extension of its own keeps the one the provider gave, or none`() {
        assertEquals("IMG_20261015_143012.heic", name("34.heic", "image/heic"))
        assertEquals("IMG_20261015_143012", name("34", "application/octet-stream"))
    }

    @Test
    fun `any other name is kept as it is, and the capture date is not even asked for`() {
        var asked = false
        val kept = listOf("IMG_20240101_000000.jpg", "2024 report.png", "photo.jpg", ".png", "attachment")
        for (displayName in kept) {
            assertEquals(displayName, pickedFileName(displayName, "image/jpeg", { asked = true; taken }, { picked }, zone, extensions))
        }
        assertFalse(asked)
    }
}
