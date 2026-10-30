package org.ntust.app.tigerduck.wear.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The start-up copy of the phone's language, which MainActivity applies before the first frame.
 * A copy left behind the snapshot relaunched the watch app forever.
 */
@RunWith(RobolectricTestRunner::class)
class LanguageTagCopyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val persistence = SchedulePersistence(context)

    private fun snapshotSays(tag: String) = runBlocking {
        persistence.write(
            coursesGzipped = gzip("[]"), accentHex = "#112233", accentEndHex = null, accentDirectionCode = null,
            syncedAtMs = 1L, loggedIn = true, languageTag = tag, disableScreenCaptureProtection = false, quietDays = null,
            visualPreset = null, courseChanges = null,
        )
    }

    private fun copySays(tag: String) {
        context.getSharedPreferences("wear_schedule_bootstrap", Context.MODE_PRIVATE).edit().putString("language_tag", tag).commit()
    }

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    @Test
    fun `a copy left behind the snapshot is brought up to it`() = runBlocking {
        snapshotSays("en")
        copySays("en-US")

        assertTrue(persistence.syncLanguageTag("en"))
        assertEquals("en", persistence.readLanguageTagBlocking())
    }

    @Test
    fun `a language the snapshot no longer says is not written over a newer one`() = runBlocking {
        snapshotSays("ja")

        // An effect for an older snapshot, still running.
        assertFalse(persistence.syncLanguageTag("en"))
        assertEquals("ja", persistence.readLanguageTagBlocking())
    }
}
