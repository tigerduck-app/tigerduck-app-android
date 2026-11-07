package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.R

/**
 * What a result row in Add course offers: a course added in this session can
 * be tapped again to take it back out, one that was already on the class
 * table stays as it is, and anything else can be added. As on the iPhone
 * (tigerduck-app#84, tigerduck-app-android#266).
 */
class AddCourseRowStateTest {

    private val calculus = "CS1003301"

    @Test
    fun `a course not on the class table can be added`() {
        assertEquals(
            AddCourseRowState.Addable,
            addCourseRowState(calculus, existingCourseNos = emptySet(), sessionAddedCourseNos = emptySet()),
        )
    }

    /** Tap-to-remove would be a dangerous escape hatch for courses the user already had. */
    @Test
    fun `a course that was already there cannot be touched`() {
        assertEquals(
            AddCourseRowState.AlreadyThere,
            addCourseRowState(calculus, existingCourseNos = setOf(calculus), sessionAddedCourseNos = emptySet()),
        )
    }

    /** Once added, the class table lists it too, and the session still wins. */
    @Test
    fun `a course added in this session can be taken back out`() {
        assertEquals(
            AddCourseRowState.AddedThisSession,
            addCourseRowState(calculus, existingCourseNos = setOf(calculus), sessionAddedCourseNos = setOf(calculus)),
        )
    }

    /**
     * Each mark looks and reads differently: a course already there is a gray check read as
     * added and disabled, one added here an accent-filled check whose hint is Remove, and the
     * rest a plus whose hint is Add. As the iPhone's `AddCourseRowMark` (tigerduck-app#876).
     */
    @Test
    fun `each row mark looks and reads differently`() {
        val marks = AddCourseRowState.entries
        assertEquals(marks.size, marks.map { it.icon }.toSet().size)
        assertEquals(marks.size, marks.map { Triple(it.value, it.hint, it == AddCourseRowState.AlreadyThere) }.toSet().size)
        assertNull(AddCourseRowState.Addable.value)
        assertEquals(R.string.action_add, AddCourseRowState.Addable.hint)
        assertEquals(R.string.add_course_added, AddCourseRowState.AddedThisSession.value)
        assertEquals(R.string.action_remove, AddCourseRowState.AddedThisSession.hint)
        assertEquals(R.string.add_course_added, AddCourseRowState.AlreadyThere.value)
        assertNull(AddCourseRowState.AlreadyThere.hint)
    }
}
