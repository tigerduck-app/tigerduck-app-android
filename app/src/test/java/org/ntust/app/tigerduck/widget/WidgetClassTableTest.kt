package org.ntust.app.tigerduck.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments
import org.ntust.app.tigerduck.util.JvmContext
import java.time.Instant
import java.time.LocalDate

/**
 * Whose class table a Today or Week widget draws ([WidgetClassTable], [widgetTable]), on made-up
 * courses and people: the user's own unless the widget is set to someone still imported, and
 * someone else's on the current term, with the app's course colors, the school's course names and
 * every holiday quiet. The widgets redraw when the imported people change
 * ([WidgetUpdater.peopleChanges]), so one set to someone deleted goes back to the user's own.
 */
class WidgetClassTableTest {

    private val holiday: LocalDate = LocalDate.parse("2026-11-11")

    private val calendar = AcademicCalendar(
        revision = 1L,
        terms = listOf(SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2027-01-08"))),
        holidays = listOf(Holiday(7, "校慶", "Anniversary", holiday, holiday)),
    )

    /** The user's own holidays, with classes kept on the anniversary. */
    private val keptAnniversary = QuietDays(calendar, setOf(7))

    private val own = listOf(
        Course.fromSchedule("ZZ1001301", "Own Course", classroom = "RB-101", schedule = mapOf(1 to listOf("3")))
            .copy(customColorHex = "#123456", customCourseName = "My Name"),
        Course.fromSchedule("ZZ2002301", "Other Own", schedule = mapOf(2 to listOf("5"))),
    )

    private val theirs = listOf(
        // A course the user takes too, as the share code's lookup returned it.
        Course.fromSchedule("ZZ1001301", "Own Course", classroom = "RB-101", schedule = mapOf(1 to listOf("3")))
            .copy(customCourseName = "Their Name", customColorHex = "#ABCDEF"),
        Course.fromSchedule("ZZ3003301", "Their Course", classroom = "TR-212", schedule = mapOf(3 to listOf("6", "7"))),
    )

    private val ming = ImportedPerson(
        id = "ming",
        studentId = null,
        nickname = "Ming",
        importedAt = Instant.EPOCH,
        sharedAt = null,
        terms = listOf(
            ImportedPerson.Term("1142", listOf("ZZ9009301"), listOf(Course.fromSchedule("ZZ9009301", "Old Term"))),
            ImportedPerson.Term("1151", theirs.map { it.courseNo }, theirs),
        ),
    )

    private fun tableOf(personId: String?, people: List<ImportedPerson> = listOf(ming), isLoggedIn: Boolean = true) =
        widgetTable(WidgetClassTable.personOf(personId, people), "1151", own, isLoggedIn, keptAnniversary)

    @Test
    fun `a widget with no choice draws the user's own table`() {
        val table = tableOf(null)

        assertSame(own, table.courses)
        assertSame(keptAnniversary, table.quietDays)
        assertEquals(buildCourseColorAssignments(own), table.courseColors)
    }

    @Test
    fun `a widget set to someone deleted since draws the user's own table`() {
        assertNull(WidgetClassTable.personOf("ming", emptyList()))

        val table = tableOf("ming", people = emptyList())

        assertSame(own, table.courses)
        assertSame(keptAnniversary, table.quietDays)
    }

    @Test
    fun `someone else's table is their current term, under the school's course names`() {
        val table = tableOf("ming")

        assertEquals(listOf("ZZ1001301", "ZZ3003301"), table.courses.map { it.courseNo })
        assertEquals(listOf("Own Course", "Their Course"), table.courses.map { it.displayName })
        assertEquals(listOf("RB-101", "TR-212"), table.courses.map { it.classroom })
    }

    @Test
    fun `someone else's table has none of their term on another term`() {
        val table = widgetTable(ming, "1152", own, isLoggedIn = true, quietDays = keptAnniversary)

        assertTrue(table.courses.isEmpty())
    }

    @Test
    fun `a holiday the user kept classes on stays quiet on someone else's table`() {
        assertFalse(holiday in keptAnniversary)

        val table = tableOf("ming")

        assertTrue(holiday in table.quietDays)
    }

    @Test
    fun `someone else's courses take the app's course colors`() {
        val table = tableOf("ming")

        // The course the user takes too keeps the user's color; theirs has none of its own.
        val usersColor = buildCourseColorAssignments(own).getValue("ZZ1001301")
        assertEquals(mapOf("ZZ1001301" to usersColor), table.courseColors)
        assertTrue(table.courses.all { it.customColorHex == null })
    }

    @Test
    fun `someone else's table needs no sign-in, as on the class table page`() {
        assertFalse(tableOf(null, isLoggedIn = false).isLoggedIn)
        assertTrue(tableOf("ming", isLoggedIn = false).isLoggedIn)
    }

    private fun people(): ImportedClassTableStore {
        val context = JvmContext()
        return ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { "B11300000" }, Dispatchers.Unconfined)
    }

    private fun code(studentId: String) =
        ClassTableShareCode(ClassTableShareCode.Format.TIGER_DUCK, studentId, null, listOf(ClassTableShareCode.Term("1151", listOf("ZZ3003301"))))

    @Test
    fun `the widgets redraw on a change to the imported people, not on the first read or a switch of the page's table`() = runTest {
        val store = people()
        val ming = store.add(code("B11300001"), "Ming", emptyMap())!!
        val seen = mutableListOf<List<String>>()
        val watching = launch(UnconfinedTestDispatcher(testScheduler)) {
            WidgetUpdater.peopleChanges(store).collect { people -> seen += people.map { it.nickname } }
        }

        store.show(ming.id)
        store.rename(ming.id, "Ming 2")
        store.delete(ming.id)
        watching.cancel()

        assertEquals(listOf(listOf("Ming 2"), emptyList<String>()), seen)
    }

    @Test
    fun `a sign-out's clearing of the imported people reaches the widgets`() = runTest {
        val store = people()
        store.add(code("B11300001"), "Ming", emptyMap())
        val seen = mutableListOf<List<String>>()
        val watching = launch(UnconfinedTestDispatcher(testScheduler)) {
            WidgetUpdater.peopleChanges(store).collect { people -> seen += people.map { it.nickname } }
        }

        store.clearForSignOut()
        watching.cancel()

        assertEquals(listOf(emptyList<String>()), seen)
    }
}
