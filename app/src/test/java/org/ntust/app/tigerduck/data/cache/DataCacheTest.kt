package org.ntust.app.tigerduck.data.cache

import android.content.Context
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/** The cache over real files, in each test's own app folders. */
class DataCacheTest {

    private lateinit var context: Context
    private lateinit var cache: DataCache

    @Before
    fun setUp() {
        context = JvmContext()
        val calendar = AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context))
        cache = DataCache(context, calendar)
    }

    private fun assignment(id: String, completed: Boolean = false) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(1_700_000_000_000),
        isCompleted = completed,
    )

    @Test
    fun `an assignment's extension is kept, and a cache with none reads as none`() = runTest {
        val extended = assignment("extended").copy(extensionDueDate = Date(1_700_300_000_000))
        cache.saveAssignments(listOf(extended, assignment("plain")))

        // The plain one is written with no extension key at all, as a cache from before it was.
        val json = java.io.File(context.cacheDir, "TigerDuckCache/assignments.json").readText()
        assertEquals(1, Regex("extensionDueDate").findAll(json).count())
        val loaded = cache.loadAssignments().associateBy { it.assignmentId }
        assertEquals(Date(1_700_300_000_000), loaded.getValue("extended").extensionDueDate)
        assertEquals(null, loaded.getValue("plain").extensionDueDate)
        assertEquals(Date(1_700_000_000_000), loaded.getValue("plain").studentDueDate)
    }

    @Test
    fun `outstanding assignments leave out submitted, ignored and marked-done work`() = runTest {
        cache.saveAssignments(
            listOf(
                assignment("open"),
                assignment("submitted", completed = true),
                assignment("ignored"),
                assignment("marked"),
            )
        )
        cache.saveIgnoredAssignments(setOf("ignored"))
        cache.saveMarkedCompletedAssignments(setOf("marked"))

        assertEquals(listOf("open"), cache.loadOutstandingAssignments().map { it.assignmentId })
    }

    @Test
    fun `outstanding assignments leave out every assignment of an ignored course`() = runTest {
        cache.saveAssignments(
            listOf(
                assignment("open"),
                assignment("course-ignored").copy(courseNo = "IGNORED"),
            )
        )
        cache.saveIgnoredCourses(setOf(CourseIgnores.key(cache.currentSemesterCode(), "IGNORED")))

        assertEquals(listOf("open"), cache.loadOutstandingAssignments().map { it.assignmentId })
    }

    @Test
    fun `an assignment kept from an earlier term stays under that term's course ignore`() = runTest {
        cache.saveAssignments(listOf(assignment("kept").copy(courseNo = "OLD", semester = "1091")))
        cache.saveIgnoredCourses(setOf(CourseIgnores.key("1091", "OLD")))

        assertEquals(emptyList<String>(), cache.loadOutstandingAssignments().map { it.assignmentId })
    }

    @Test
    fun `signing out forgets the ignored courses and their unsent changes`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:CS1001301"))
        cache.savePendingCourseIgnores(
            listOf(PendingCourseIgnore("1141", "CS1001301", "1141CS1001301", null, true, 1L))
        )

        cache.clearAllUserData()

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(emptyList<PendingCourseIgnore>(), cache.loadPendingCourseIgnores())
        assertFalse(File(context.filesDir, "TigerDuckData/ignored_courses.json").exists())
        assertFalse(File(context.filesDir, "TigerDuckData/pending_course_ignores.json").exists())
    }

    // A process death between a write's temporary file and its rename leaves that file beside
    // the one it was to replace, holding the departing student's data.
    @Test
    fun `signing out deletes what a write cut short left behind`() = runTest {
        val leftInCache = File(context.cacheDir, "TigerDuckCache/assignments.json.tmp").apply { writeText("[]") }
        val leftInData = File(context.filesDir, "TigerDuckData/manual_courses_1141.json.tmp").apply { writeText("[]") }

        cache.clearAllUserData()

        assertFalse(leftInCache.exists())
        assertFalse(leftInData.exists())
    }

    @Test
    fun `a waiting change too damaged to send is left out`() = runTest {
        cache.savePendingCourseIgnores(
            listOf(
                PendingCourseIgnore("1141", "CS1001301", "1141CS1001301", null, true, 1L),
                PendingCourseIgnore(null, "CS1001302", "1141CS1001302", null, true, 1L),
                PendingCourseIgnore("1141", "CS1001303", null, null, true, 1L),
            )
        )

        assertEquals(
            listOf("1141:CS1001301"),
            cache.loadPendingCourseIgnores().map { it.key },
        )
    }

    @Test
    fun `work begun before a sign-out writes nothing after it`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()

        withContext(DataCache.Generation(generation)) {
            cache.saveAssignments(listOf(assignment("left behind")))
            cache.saveCourseCustomNames(mapOf("CS1001301" to mapOf("zh" to "Old name")))
        }

        assertEquals(emptyList<Assignment>(), cache.loadAssignments())
        assertEquals(emptyMap<String, Map<String, String>>(), cache.loadCourseCustomNames())
    }

    @Test
    fun `work tied to the account still signed in writes as usual`() = runTest {
        withContext(DataCache.Generation(cache.userDataGeneration)) {
            cache.saveAssignments(listOf(assignment("current")))
        }

        assertEquals(listOf("current"), cache.loadAssignments().map { it.assignmentId })
    }

    @Test
    fun `updating the assignments stores what the change makes of them`() = runTest {
        cache.saveAssignments(listOf(assignment("a"), assignment("b")))

        val written = cache.updateAssignments { all ->
            all.map { if (it.assignmentId == "a") it.copy(isCompleted = true) else it }
        }

        assertEquals(listOf(true, false), cache.loadAssignments().map { it.isCompleted })
        assertEquals("and hands back the list as written", cache.loadAssignments(), written)
    }

    @Test
    fun `a save made while the assignments update lands after it, not undone by it`() = runBlocking {
        cache.saveAssignments(listOf(assignment("read")))
        val changing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val update = async(Dispatchers.IO) {
            cache.updateAssignments { all ->
                changing.countDown()
                assertTrue("never told to finish", finish.await(WAIT_SECONDS, TimeUnit.SECONDS))
                all.map { it.copy(isCompleted = true) }
            }
        }
        assertTrue("the update never reached its change", changing.await(WAIT_SECONDS, TimeUnit.SECONDS))

        // Another writer, such as the worker, saving while the update holds what it read.
        // Undispatched, it runs here until it first suspends: on the lock the update
        // holds, or, were the read and the write locked apart, inside its own write.
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.saveAssignments(listOf(assignment("saved")))
        }
        finish.countDown()
        withTimeout(WAIT_SECONDS * 1000) {
            update.await()
            save.join()
        }

        assertEquals(listOf("saved"), cache.loadAssignments().map { it.assignmentId })
    }

    @Test
    fun `an update begun before a sign-out writes nothing after it`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()
        cache.saveAssignments(listOf(assignment("next account")))

        var changed = false
        val written = cache.updateAssignments(generation) { all ->
            changed = true
            all.map { it.copy(isCompleted = true) }
        }

        assertNull(written)
        assertFalse("the change does not even run", changed)
        assertEquals(listOf(false), cache.loadAssignments().map { it.isCompleted })
    }

    @Test
    fun `writing a term's courses tells readers of other terms to look again`() = runTest {
        val before = cache.coursesVersion.value

        cache.saveCourses(listOf(Course(courseNo = "CS1010001", courseName = "A")), "1151")
        cache.updateCourses("1142") { courses, _ -> courses }

        assertEquals(before + 2, cache.coursesVersion.value)
    }

    @Test
    fun `writing the ignored or marked-done set tells readers to look again`() = runTest {
        val before = cache.assignmentMarksVersion.value

        cache.replaceIgnoredAssignments(setOf("a"))
        cache.replaceMarkedCompletedAssignments(setOf("b"))

        assertEquals(before + 2, cache.assignmentMarksVersion.value)
    }

    @Test
    fun `marks a re-enable answer adopts are written and announced, a swipe's are not`() = runTest {
        // Home re-reads its marks on the announcement, before its next swipe
        // can write the old ones back (tigerduck-app-android#189). Its own
        // writes must not set that off: reading them back could undo a newer
        // swipe.
        val before = cache.marksAdoptedVersion.value

        cache.adoptAssignmentMarks(ignored = setOf("a"), completed = setOf("b"))
        cache.saveIgnoredAssignments(setOf("c"))
        cache.saveMarkedCompletedAssignments(setOf("d"))

        assertEquals(before + 1, cache.marksAdoptedVersion.value)
        cache.adoptAssignmentMarks(ignored = setOf("a"), completed = setOf("b"))
        assertEquals(setOf("a"), cache.loadIgnoredAssignments())
        assertEquals(setOf("b"), cache.loadMarkedCompletedAssignments())
    }

    @Test
    fun `the closed assignments round-trip and go with the account`() = runTest {
        cache.saveClosedAssignments(mapOf(7 to 1_800_000_000_000L))

        assertEquals(mapOf(7 to 1_800_000_000_000L), cache.loadClosedAssignments())
        cache.clearAllUserData()
        assertEquals(emptyMap<Int, Long>(), cache.loadClosedAssignments())
    }

    private companion object {
        // Only a broken lock waits this long; it fails the test instead of hanging the suite.
        const val WAIT_SECONDS = 5L
    }

    @Test
    fun `a term's portal courses count as cached only while their file holds some`() = runTest {
        assertFalse("nothing cached", cache.hasPortalCourses("1151"))

        cache.saveCourses(listOf(Course(courseNo = "MY1", courseName = "Mine", isManual = true)), "1151")
        assertFalse("hand-typed alone", cache.hasPortalCourses("1151"))

        cache.saveCourses(
            listOf(
                Course(courseNo = "CS1", courseName = "CS1"),
                Course(courseNo = "MY1", courseName = "Mine", isManual = true),
            ),
            "1151",
        )
        assertTrue(cache.hasPortalCourses("1151"))

        // What the system evicting cacheDir leaves.
        File(context.cacheDir, "TigerDuckCache/courses_1151.json").delete()
        assertFalse(cache.hasPortalCourses("1151"))
    }
}
