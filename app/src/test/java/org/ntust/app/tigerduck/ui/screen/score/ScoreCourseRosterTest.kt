package org.ntust.app.tigerduck.ui.screen.score

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.shared.Course

/**
 * How a score row finds its class-table course, and how that course's schedule reads in the
 * course's detail (tigerduck-app-android#270). Mirrors iOS `ScoreCourseDetailSheet`.
 */
class ScoreCourseRosterTest {
    @get:Rule val main = MainDispatcherRule()

    private fun grade(code: String, term: String = "1141") =
        CourseGrade(term = term, code = code, name = "Course $code")

    private fun rosterCourse(courseNo: String) =
        Course.fromSchedule(courseNo = courseNo, courseName = "Roster $courseNo")

    /**
     * A cache row as DataCache accepts it with only a number and a name: Gson's Unsafe path
     * skips the Kotlin defaults, so every other String field is null despite its type.
     */
    private fun sparseCachedCourse(): Course {
        val json = """[{"courseNo":"CS1001301","courseName":"Calculus"}]"""
        val type = object : TypeToken<List<Course>>() {}.type
        return DataCache.usableRows(Gson().fromJson(json, type)).single()
    }

    private fun viewModel(rosterCourses: suspend (String) -> List<Course>) = ScoreViewModel(
        authState = MutableStateFlow(true),
        storedStudentId = { "B11300001" },
        storedPassword = { "password" },
        cachedSnapshot = { null },
        fetchReport = { _, _, _ -> error("not fetched here") },
        isOnline = { false },
        isDemo = { false },
        strings = { "string $it" },
        rosterCourses = rosterCourses,
    )

    @Test
    fun `a score row is the roster course with its course number`() {
        val calculus = rosterCourse("CS1001301")
        val roster = listOf(rosterCourse("CS1002302"), calculus)

        assertEquals(calculus, ScoreCourseRoster.match(roster, grade("CS1001301")))
    }

    @Test
    fun `a course number the roster lacks has no roster course`() {
        assertNull(ScoreCourseRoster.match(listOf(rosterCourse("CS1002302")), grade("CS1001301")))
        assertNull(ScoreCourseRoster.match(emptyList(), grade("CS1001301")))
    }

    /** A blank number would otherwise pair with a hand-added course that has none. */
    @Test
    fun `a score row without a course number matches nothing`() {
        val roster = listOf(rosterCourse(""), rosterCourse(" "))

        assertNull(ScoreCourseRoster.match(roster, grade("")))
        assertNull(ScoreCourseRoster.match(roster, grade("  ")))
    }

    @Test
    fun `surrounding whitespace does not keep a course from matching`() {
        val calculus = rosterCourse(" CS1001301 ")

        assertEquals(calculus, ScoreCourseRoster.match(listOf(calculus), grade("CS1001301 ")))
    }

    // --- Term to class-table semester code ---

    /** The class table keys its cache by the portal's four characters, summer included. */
    @Test
    fun `a four-character term is the class-table code already`() {
        assertEquals("1141", ScoreCourseRoster.semesterCode("1141"))
        assertEquals("1142", ScoreCourseRoster.semesterCode(" 1142 "))
        assertEquals("114H", ScoreCourseRoster.semesterCode("114H"))
        assertEquals("114H", ScoreCourseRoster.semesterCode("114h"))
    }

    /** The parser's own fixtures spell a term "11201"; the cache file for it is courses_1121.json. */
    @Test
    fun `a five-character term collapses to its year and term digit`() {
        assertEquals("1121", ScoreCourseRoster.semesterCode("11201"))
        assertEquals("1122", ScoreCourseRoster.semesterCode("11202"))
    }

    /** Anything else is left unmatched rather than guessed at, so no wrong file is ever read. */
    @Test
    fun `a term in no known form has no code`() {
        listOf("", "  ", "92", "114", "11203", "1141-2", "202401", "114-1").forEach {
            assertNull("term '$it'", ScoreCourseRoster.semesterCode(it))
        }
    }

    // --- Schedule code ---

    @Test
    fun `the schedule reads as the school's node codes, Monday first, periods in order`() {
        assertEquals(
            "M3, M4, T1",
            ScoreCourseRoster.scheduleCode(mapOf(2 to listOf("1"), 1 to listOf("4", "3"))),
        )
    }

    @Test
    fun `evening periods follow the tenth and every weekday has its letter`() {
        assertEquals(
            "M9, M10, MA, MB",
            ScoreCourseRoster.scheduleCode(mapOf(1 to listOf("A", "10", "B", "9"))),
        )
        assertEquals(
            "M1, T1, W1, R1, F1, S1, U1",
            ScoreCourseRoster.scheduleCode((1..7).associateWith { listOf("1") }),
        )
    }

    @Test
    fun `a weekday with no letter is left out and an unknown period goes last`() {
        assertEquals(
            "M2, MX",
            ScoreCourseRoster.scheduleCode(mapOf(1 to listOf("X", "2"), 8 to listOf("1"), 0 to listOf("1"))),
        )
    }

    @Test
    fun `a course with no periods has no schedule code`() {
        assertEquals("", ScoreCourseRoster.scheduleCode(emptyMap()))
        assertEquals("", ScoreCourseRoster.scheduleCode(mapOf(1 to emptyList())))
    }

    // --- Detail rows ---

    @Test
    fun `the rows are instructor, enrolment, deduped classroom and schedule, each only when known`() {
        val full = Course.fromSchedule(
            courseNo = "CS1001301",
            courseName = "Calculus",
            instructor = " Prof. Lin ",
            classroom = "TR-313, TR-313",
            enrolledCount = 35,
            maxCount = 60,
            schedule = mapOf(1 to listOf("3", "4"), 2 to listOf("1")),
        )

        assertEquals(
            listOf(
                R.string.course_detail_instructor_label to "Prof. Lin",
                R.string.score_course_detail_enrollment_label to "35 / 60",
                R.string.course_detail_classroom_label to "TR-313",
                R.string.score_course_detail_schedule_label to "M3, M4, T1",
            ),
            ScoreCourseRoster.rows(full),
        )
        assertEquals(emptyList<Pair<Int, String>>(), ScoreCourseRoster.rows(rosterCourse("CS1001301")))
    }

    /** The fields are declared non-null, but a row this sparse holds null in them; see CLAUDE.md. */
    @Test
    fun `a sparse cached row still matches and yields no rows instead of crashing`() {
        val sparse = sparseCachedCourse()

        assertSame(sparse, ScoreCourseRoster.match(listOf(sparse), grade("CS1001301")))
        assertEquals(emptyList<Pair<Int, String>>(), ScoreCourseRoster.rows(sparse))
    }

    // --- View model ---

    /** NTUST reissues course numbers across terms, so the roster asked for is the row's own term's. */
    @Test
    fun `the view model looks the course up in the roster of its own term`() = runTest {
        val termsAsked = mutableListOf<String>()
        val retake = rosterCourse("CS1001301")
        val vm = viewModel { term ->
            termsAsked += term
            if (term == "1132") listOf(retake) else emptyList()
        }

        assertEquals(retake, vm.rosterCourse(grade("CS1001301", term = "1132")))
        assertNull(vm.rosterCourse(grade("CS1001301", term = "1141")))
        assertEquals(listOf("1132", "1141"), termsAsked)
    }

    /** A populated courses_1121.json must not read as "no roster" for a row whose term says 11201. */
    @Test
    fun `a five-character score term reads the four-character class-table cache`() = runTest {
        val termsAsked = mutableListOf<String>()
        val calculus = rosterCourse("CS1001301")
        val vm = viewModel { term ->
            termsAsked += term
            if (term == "1121") listOf(calculus) else emptyList()
        }

        assertEquals(calculus, vm.rosterCourse(grade("CS1001301", term = "11201")))
        assertEquals(listOf("1121"), termsAsked)
    }

    @Test
    fun `a term in no known form asks the cache for nothing`() = runTest {
        val termsAsked = mutableListOf<String>()
        val vm = viewModel { term -> termsAsked += term; emptyList() }

        assertNull(vm.rosterCourse(grade("CS1001301", term = "92")))
        assertTrue(termsAsked.isEmpty())
    }
}
