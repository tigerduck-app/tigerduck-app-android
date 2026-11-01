package org.ntust.app.tigerduck.ui.theme

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
}
