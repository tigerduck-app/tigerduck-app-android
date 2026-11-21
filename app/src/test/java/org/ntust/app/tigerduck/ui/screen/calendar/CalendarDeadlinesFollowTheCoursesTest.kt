package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.announcements.testSessions
import org.ntust.app.tigerduck.util.bare
import org.ntust.app.tigerduck.util.set
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The deadlines that open follow the cache as the class table and Moodle change it: hiding a
 * course stops its deadlines from opening, as on the iPhone, and a new assignment opens once kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CalendarDeadlinesFollowTheCoursesTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var cache: DataCache
    private lateinit var calendar: CalendarViewModel
    private lateinit var semester: String

    private val auth = bare<AuthService>().also { it.set("authState", MutableStateFlow(true)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        val academic = AcademicCalendarStore(context, OkHttpClient(), prefs)
        cache = DataCache(context, academic)
        semester = cache.currentSemesterCode()
        val sessions = testSessions(context)
        sessions.school.signedInFrom(MutableStateFlow(true))
        calendar = CalendarViewModel(
            bare(), bare(), bare(), auth, cache, prefs, UiLanguageMonitor(prefs), context, academic,
            bare(), bare(), bare(), bare(), sessions,
        )
        runBlocking {
            cache.saveCourses(listOf(course("CS101"), course("MA201")), semester, testSignIn())
            cache.saveAssignments(listOf(assignment("1", "CS101"), assignment("2", "MA201")), testSignIn())
        }
        // As the screen collects it.
        calendar.viewModelScope.launch { calendar.openableAssignmentIds.collect {} }
    }

    @After
    fun tearDown() {
        calendar.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun course(courseNo: String) = Course(courseNo = courseNo, courseName = courseNo)

    private fun assignment(id: String, courseNo: String) =
        Assignment(assignmentId = id, courseNo = courseNo, courseName = courseNo, title = "HW $id", dueDate = Date(0))

    /**
     * [change] made on the page's own thread, with the test's main thread run meanwhile: a read of
     * the page's holds the cache's lock while it waits for that thread, so a write blocking it waits for good.
     */
    private fun write(change: suspend () -> Unit) {
        val job = calendar.viewModelScope.launch { change() }
        repeat(200) {
            dispatcher.scheduler.runCurrent()
            if (job.isCompleted) return
            Thread.sleep(10)
        }
        error("the write never finished")
    }

    /** The set once it reads [expected], or as it stands when it never does. */
    private fun openableWhen(expected: Set<String>): Set<String> {
        repeat(100) {
            dispatcher.scheduler.runCurrent()
            if (calendar.openableAssignmentIds.value == expected) return expected
            Thread.sleep(20)
        }
        return calendar.openableAssignmentIds.value
    }

    @Test
    fun `hiding a course stops its deadlines from opening`() {
        assertEquals(setOf("1", "2"), openableWhen(setOf("1", "2")))

        // As the class table deletes a course: its hide, then the term without it.
        write {
            cache.saveDeletedCourseNos(setOf(CourseTombstoneKeys.key(semester, "MA201")), testSignIn())
            cache.saveCourses(listOf(course("CS101")), semester, testSignIn())
        }

        assertEquals(setOf("1"), openableWhen(setOf("1")))
    }

    @Test
    fun `a course hidden by a sync stops its deadlines from opening, though the term still lists it`() {
        assertEquals(setOf("1", "2"), openableWhen(setOf("1", "2")))

        write { cache.saveDeletedCourseNos(setOf(CourseTombstoneKeys.key(semester, "MA201")), testSignIn()) }
        cache.notifyBackgroundSyncComplete()

        assertEquals(setOf("1"), openableWhen(setOf("1")))
    }

    @Test
    fun `a new assignment opens once the cache keeps it`() {
        assertEquals(setOf("1", "2"), openableWhen(setOf("1", "2")))

        write {
            cache.saveAssignments(listOf(assignment("1", "CS101"), assignment("2", "MA201"), assignment("3", "CS101")), testSignIn())
        }

        assertEquals(setOf("1", "2", "3"), openableWhen(setOf("1", "2", "3")))
    }
}
