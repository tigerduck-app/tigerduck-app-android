package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException

/**
 * A course the sync's colour pass moved off a colour another course took is
 * sent its new colour ([sendMovedColors]), so the server stops holding the
 * two on one colour (tigerduck-app-android#295).
 */
class SendMovedColorsTest {

    private fun course(no: String, moodleId: String?) =
        Course(courseNo = no, courseName = no, moodleIdNumber = moodleId)

    @Test
    fun `each moved course is sent by its Moodle id, in course-number order`() = runTest {
        val sent = mutableListOf<Pair<String, String>>()

        sendMovedColors(
            courses = listOf(course("B", "1141B"), course("A", "1141A"), course("C", null)),
            moved = mapOf("B" to "#4ECDC4", "C" to "#FFE66D", "A" to "#FF6B6B"),
        ) { moodleId, hex -> sent += moodleId to hex }

        assertEquals(listOf("1141A" to "#FF6B6B", "1141B" to "#4ECDC4"), sent)
    }

    @Test
    fun `a failed request does not stop the ones after it`() = runTest {
        val sent = mutableListOf<String>()

        sendMovedColors(
            courses = listOf(course("A", "1141A"), course("B", "1141B")),
            moved = mapOf("A" to "#FF6B6B", "B" to "#4ECDC4"),
        ) { moodleId, _ ->
            if (moodleId == "1141A") throw IOException("offline")
            sent += moodleId
        }

        assertEquals(listOf("1141B"), sent)
    }
}
