package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.util.Date

/**
 * Whether each sync write of [PushApiClient] goes out, on every combination of the master switch,
 * the four category switches and the re-enable hold: the client's own gates, which read the
 * switches and never the hold (the hold is its callers' to read, and Use Local sends while it
 * holds). Each is checked against today's rule written out on the same preferences, so moving the
 * gates to `SyncPolicy` cannot change one. Both flavors run this; on F-Droid nothing goes.
 */
class SyncWriteGatesTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    private val client = pushApiClientAt(v4, backend.client, prefs)
    private val key = CourseRef(courseKey = "client:1151:CS1")
    private val signIn = testSignIn()

    private val categories = listOf("courses", "course_colors", "course_names", "assignments")

    /** Every set of the four categories. */
    private val everySet: List<Set<String>> = (0 until 16).map { bits -> categories.filterIndexed { i, _ -> bits shr i and 1 == 1 }.toSet() }

    private suspend fun sent(call: suspend () -> Unit): Boolean {
        val before = backend.sent.size
        call()
        return backend.sent.size > before
    }

    @Test
    fun `each write goes out exactly when today's switches say, whatever the hold`() = runTest {
        var row = 0
        for (master in listOf(false, true)) for (on in everySet) for (held in everySet) {
            prefs.cloudSyncEnabled = master
            prefs.syncCourses = "courses" in on
            prefs.syncCourseColors = "course_colors" in on
            prefs.syncCourseNames = "course_names" in on
            prefs.syncAssignments = "assignments" in on
            assertTrue(prefs.school.pendingConflictCategories.set(held, signIn))
            val cloud = prefs.cloudSyncEnabled
            val at = "master=$master on=$on held=$held"
            fun check(what: String, today: Boolean, ours: Boolean) = assertEquals("$what at $at", today, ours)
            // A list of its own each row, so no upload ledger holds one back as unchanged.
            val course = listOf(Course(courseNo = "CS$row", courseName = "CS$row"))
            val homework = listOf(Assignment("$row", "CS1", "CS1", "HW$row", Date(0)))

            check("holiday", cloud, sent { client.putHolidayOverrideAnswered(3, true, signIn, madeAtMs = null) })
            check("mark", cloud && prefs.syncAssignments, sent { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null) })
            check("color", cloud && prefs.syncCourseColors, sent { client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000") })
            check("name", cloud && prefs.syncCourseNames, sent { client.patchCourseOverrideAnswered(key, signIn, customName = "N", locale = "en") })
            check(
                "color and name",
                cloud && prefs.syncCourseColors && prefs.syncCourseNames,
                sent { client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", customName = "N", locale = "en") },
            )
            check(
                "ignore",
                cloud && prefs.syncAssignments && prefs.syncCourses,
                sent { client.patchCourseIgnoreAnswered(key, true, null, signIn, madeAtMs = null) },
            )
            check("assignment upload", cloud && prefs.syncAssignments, sent { client.uploadAssignments(homework, signIn) })
            check("course upload", cloud && prefs.syncCourses, sent { client.uploadCourses(course, "1151", signIn) })
            check("course delete", cloud && prefs.syncCourses, sent { client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null) })
            check("term reset", cloud && prefs.syncCourses, sent { client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null) })
            row++
        }
        assertEquals(512, row)
    }

    @Test
    fun `a course override the caller holds back right before it goes is not sent`() = runTest {
        prefs.cloudSyncEnabled = true
        prefs.syncCourseColors = true

        val result = client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", allowed = { false })

        assertEquals(false, result.sent)
        assertEquals(emptyList<Any>(), backend.sent)
    }
}
