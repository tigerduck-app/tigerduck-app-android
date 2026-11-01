package org.ntust.app.tigerduck.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.MoodleColorBook
import org.ntust.app.tigerduck.shared.Course

/**
 * A course moving to its Moodle color frees the app palette slot it held. The phone's color map,
 * which keeps what it assigned, and the widgets, which assign from scratch, must still agree
 * (tigerduck-app/tigerduck-app-android#638).
 */
class MoodleColorTransitionTest {

    // "CS1" and "CS12" hash to the same palette slot; CS1 sorts first and takes it.
    private val first = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1")
    private val second = Course(courseNo = "CS12", courseName = "CS12")
    private val courses = listOf(first, second)

    @Before
    fun setUp() {
        MoodleColorBook.shared.clear()
        MoodleColorBook.shared.load(saved = null, legacyIdents = emptySet())
        TigerDuckTheme.clearCourseColorMap()
    }

    @After
    fun tearDown() {
        MoodleColorBook.shared.clear()
        TigerDuckTheme.clearCourseColorMap()
    }

    @Test
    fun `the phone and the widgets give the second course the same color after the first moves to Moodle's`() {
        TigerDuckTheme.buildCourseColorMap(courses)
        val before = TigerDuckTheme.courseColorVibrant("CS12")

        MoodleColorBook.shared.setIds(mapOf("1151CS1" to 4127))
        TigerDuckTheme.buildCourseColorMap(courses)

        val widgets = buildCourseColorAssignments(courses)
        assertEquals(widgets.getValue("CS12"), TigerDuckTheme.courseColorVibrant("CS12"))
        assertEquals(widgets.getValue("CS1"), TigerDuckTheme.courseColorVibrant("CS1"))
        // It did have to move: the slot it probed to is the one the first course left.
        assertEquals(false, before == TigerDuckTheme.courseColorVibrant("CS12"))
    }

    @Test
    fun `a late id that reconciled first still lays the phone out as the widgets do`() {
        TigerDuckTheme.buildCourseColorMap(courses)

        // CourseColorStore.saveMoodleIds reconciles before it asks for the rebuild.
        MoodleColorBook.shared.setIds(mapOf("1151CS1" to 4127))
        MoodleColorBook.shared.reconcile(courses)
        TigerDuckTheme.buildCourseColorMap(courses)

        assertEquals(buildCourseColorAssignments(courses).getValue("CS12"), TigerDuckTheme.courseColorVibrant("CS12"))
    }

    @Test
    fun `another term's course of the same number does not keep the Moodle color of this one`() {
        MoodleColorBook.shared.setIds(mapOf("1151CS1" to 4127))
        TigerDuckTheme.buildCourseColorMap(listOf(first))
        val spring = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1152CS1")

        TigerDuckTheme.buildCourseColorMap(listOf(spring))

        assertEquals(buildCourseColorAssignments(listOf(spring)).getValue("CS1"), TigerDuckTheme.courseColorVibrant("CS1"))
        assertEquals(false, Color(0xFF000000L or MoodleColorBook.forCourseId(4127).toLong()) == TigerDuckTheme.courseColorVibrant("CS1"))
    }

    @Test
    fun `building one term's colors leaves the other courses' colors as they were`() {
        MoodleColorBook.shared.setIds(mapOf("1151CS1" to 4127))
        TigerDuckTheme.buildCourseColorMap(listOf(first, second))
        val before = TigerDuckTheme.courseColorVibrant("CS1")
        val other = Course(courseNo = "MA7", courseName = "MA7", moodleIdNumber = "1142MA7", customColorHex = "#123456")

        TigerDuckTheme.buildCourseColorMap(listOf(other))

        assertEquals(before, TigerDuckTheme.courseColorVibrant("CS1"))
    }
}
