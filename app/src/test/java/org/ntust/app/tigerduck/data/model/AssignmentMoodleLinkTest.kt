package org.ntust.app.tigerduck.data.model

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where an assignment opens: the page Moodle gave, and nowhere without one
 * (tigerduck-app-android#313).
 */
class AssignmentMoodleLinkTest {

    private fun assignment(moodleUrl: String?) = Assignment(
        assignmentId = "4567",
        courseNo = "CS3204301",
        courseName = "Operating Systems",
        title = "Lab 2",
        dueDate = Date(0),
        moodleUrl = moodleUrl,
    )

    @Test
    fun `the Moodle app's link redirects to the page Moodle gave`() {
        assertEquals(
            "moodlemobile://https://moodle2.ntust.edu.tw?redirect=/mod/assign/view.php?id=890",
            assignment("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890").moodleDeepLink,
        )
    }

    @Test
    fun `an assignment Moodle gave no address has no link`() {
        // The assignment id is not the course-module id view.php takes, so no
        // page can be guessed from it; iOS's deep link is nil here too.
        assertNull(assignment(moodleUrl = null).moodleDeepLink)
    }

    @Test
    fun `a blank address is no address`() {
        assertNull(assignment(moodleUrl = " ").moodleDeepLink)
    }
}
