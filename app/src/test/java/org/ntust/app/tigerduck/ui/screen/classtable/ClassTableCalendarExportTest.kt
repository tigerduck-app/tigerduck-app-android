package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.data.storage.AppStorage
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Export ICS writes the shown term's file where the screenshot export writes its image. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableCalendarExportTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val calendar = AcademicCalendar(
        1L, listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 8))), emptyList(),
    )

    private val term = ClassTableCalendarFile.term("1151", ownTable = true, calendar = calendar, optedIn = emptySet())!!

    private val structures = Course.fromSchedule(
        courseNo = "CS1001301", courseName = "Data Structures", schedule = mapOf(3 to listOf("3", "4")),
    )

    private val ming = ImportedPerson("5F0B6B1E", "B11300001", "Ming Lee", Instant.EPOCH, null, emptyList())

    private val title get() = context.getString(R.string.feature_class_table).trim().replace(Regex("\\s+"), "_")

    private suspend fun write(shown: ShownClassTable): File? =
        writeClassTableCalendar(context, shown, term, "115-1", Instant.parse("2026-11-06T04:00:00Z"))

    @Test
    fun `the user's own table is written as an ICS file under the export directory`() = runTest {
        val file = write(ShownClassTable(null, "1151", listOf("1151"), listOf(structures), 0))!!

        assertEquals("${title}_115-1.ics", file.name)
        assertEquals(File(context.cacheDir, AppStorage.CLASS_TABLE_EXPORT_DIR), file.parentFile!!.parentFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text, text.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(text, text.contains("SUMMARY:Data Structures\r\n"))
        assertTrue(text, text.contains("UID:1151-CS1001301-3-3@tigerduck.app\r\n"))
    }

    @Test
    fun `someone else's table is named with their nickname and carries their uids`() = runTest {
        val file = write(ShownClassTable(ming, "1151", listOf("1151"), listOf(structures), 0))!!

        assertEquals("${title}_115-1_Ming_Lee.ics", file.name)
        assertTrue(file.readText().contains("UID:1151-CS1001301-3-3-5F0B6B1E@tigerduck.app\r\n"))
    }

    @Test
    fun `a table with no class in the term writes nothing`() = runTest {
        val root = File(context.cacheDir, AppStorage.CLASS_TABLE_EXPORT_DIR)
        val before = root.listFiles()?.size ?: 0
        val thesis = Course.fromSchedule(courseNo = "GE1100001", courseName = "Thesis")

        assertNull(write(ShownClassTable(null, "1151", listOf("1151"), listOf(thesis), 0)))
        assertEquals(before, root.listFiles()?.size ?: 0)
    }
}
