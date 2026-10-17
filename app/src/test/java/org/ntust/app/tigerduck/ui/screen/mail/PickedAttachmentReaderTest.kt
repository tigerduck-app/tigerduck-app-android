package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PickedAttachmentReaderTest {
    /**
     * Answers as the system Photo Picker does: the display name is the media id with an
     * extension, the capture date sits under the picker's own `date_taken` column, and a column
     * it has not got is an error rather than a null.
     */
    class PickerProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val cursor = MatrixCursor(columns)
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "34.png"
                        OpenableColumns.SIZE -> 3L
                        "date_taken" -> TAKEN_MS
                        else -> throw IllegalArgumentException("no column $column")
                    }
                },
            )
            return cursor
        }

        override fun getType(uri: Uri) = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    }

    @Test
    fun `a photo from the picker is described under its capture date, not its media id`() {
        Robolectric.buildContentProvider(PickerProvider::class.java).create("picker")
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png")
        val reader = ContentResolverAttachmentReader(RuntimeEnvironment.getApplication())

        val attachment = reader.describe(Uri.parse("content://picker/media/34"))!!

        val taken = Instant.ofEpochMilli(TAKEN_MS).atZone(ZoneId.systemDefault())
        assertEquals("IMG_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(taken) + ".png", attachment.fileName)
        assertEquals("image/png", attachment.contentType)
        assertEquals(3L, attachment.sizeBytes)
    }

    private companion object {
        const val TAKEN_MS = 1_792_000_212_000L
    }
}
