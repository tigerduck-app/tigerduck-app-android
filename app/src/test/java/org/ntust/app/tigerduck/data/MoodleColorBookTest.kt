package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.MoodleColorState
import org.ntust.app.tigerduck.shared.Course

/**
 * Moodle's default course colors (tigerduck-app/tigerduck-app-android#638, after
 * tigerduck-app/tigerduck-app#928): the ten colors made readable, which course takes one, and
 * the wait of a course whose Moodle id is not known yet. Hand-made courses and ids throughout.
 */
class MoodleColorBookTest {

    private val ids = mapOf("1151CS1" to 4127, "1151CS2" to 5337, "1151CS3" to 902)

    private fun course(no: String, moodle: Boolean = true, pick: String? = null) = Course(
        courseNo = no,
        courseName = no,
        moodleIdNumber = if (moodle) "1151$no" else null,
        customColorHex = pick,
    )

    private fun book(saved: MoodleColorState? = null, legacy: Set<String> = emptySet(), known: Map<String, Int> = ids) =
        MoodleColorBook().apply {
            setIds(known)
            load(saved, legacy)
        }

    @Test
    fun `the ten colors come out readable on both cards, pinned to the iPhone app's values`() {
        assertEquals(
            listOf(0x569E9E, 0x5E96CF, 0x8F88E0, 0x8F9395, 0x00A685, 0x0984E3, 0x8B9498, 0xAF8C4C, 0xE16C96, 0x6C5CE7),
            MoodleColorBook.READABLE,
        )
    }

    @Test
    fun `a course id picks the color by its last digit`() {
        assertEquals(0xAF8C4C, MoodleColorBook.forCourseId(4127))
        assertEquals(0x569E9E, MoodleColorBook.forCourseId(90))
        assertEquals(0x569E9E, MoodleColorBook.forCourseId(0))
    }

    @Test
    fun `a new course with a Moodle id takes the color Moodle draws it in`() {
        val book = book()

        assertTrue(book.reconcile(listOf(course("CS1"))))

        assertEquals(mapOf("CS1" to 0xAF8C4C), book.colors())
    }

    @Test
    fun `a new course with no Moodle id waits and takes no Moodle color`() {
        val saved = mutableListOf<MoodleColorState>()
        val book = book().apply { persistWith { saved += it } }

        assertFalse(book.reconcile(listOf(course("CS4"), course("CS5", moodle = false))))

        assertTrue(book.colors().isEmpty())
        assertEquals(listOf("CS4", "CS5"), saved.last().awaiting)
    }

    @Test
    fun `a waiting course moves to its Moodle color when the id arrives, and stays there`() {
        val book = book(known = emptyMap())
        val courses = listOf(course("CS1"))
        book.reconcile(courses)
        assertTrue(book.colors().isEmpty())

        book.setIds(ids)

        assertTrue(book.reconcile(courses))
        assertEquals(mapOf("CS1" to 0xAF8C4C), book.colors())
        assertFalse(book.reconcile(courses))
        book.setIds(emptyMap())
        book.reconcile(courses)
        assertEquals(mapOf("CS1" to 0xAF8C4C), book.colors())
    }

    @Test
    fun `a pick, even of the same color, ends the wait`() {
        val book = book(known = emptyMap())
        book.reconcile(listOf(course("CS1")))

        book.reconcile(listOf(course("CS1", pick = "#AF8C4C")))
        book.setIds(ids)

        assertFalse(book.reconcile(listOf(course("CS1", pick = "#AF8C4C"))))
        assertTrue(book.colors().isEmpty())
    }

    @Test
    fun `a color pulled for a waiting course ends the wait too`() {
        val book = book(known = emptyMap())
        book.reconcile(listOf(course("CS1")))

        book.reconcile(listOf(course("CS1", pick = "#123456")))
        book.setIds(ids)
        book.reconcile(listOf(course("CS1", pick = "#123456")))

        assertTrue(book.colors().isEmpty())
    }

    @Test
    fun `a course colored before this existed never moves, whatever the ids say`() {
        val book = book(saved = null, legacy = setOf("CS1", "CS3"))

        assertFalse(book.reconcile(listOf(course("CS1"), course("CS3"))))

        assertTrue(book.colors().isEmpty())
    }

    @Test
    fun `an upgrade keeps old courses and gives a course added later its Moodle color`() {
        val book = book(saved = null, legacy = setOf("CS1"))

        book.reconcile(listOf(course("CS1"), course("CS2")))

        assertEquals(mapOf("CS2" to MoodleColorBook.forCourseId(5337)), book.colors())
    }

    @Test
    fun `nothing is given before the book is loaded`() {
        val book = MoodleColorBook().apply { setIds(ids) }

        assertFalse(book.reconcile(listOf(course("CS1"))))
        assertTrue(book.colors().isEmpty())
    }

    @Test
    fun `a reset gives the Moodle color to each course with an id and ends every wait`() {
        val saved = mutableListOf<MoodleColorState>()
        val book = book(known = emptyMap()).apply { persistWith { saved += it } }
        val courses = listOf(course("CS1"), course("CS5", moodle = false))
        book.reconcile(courses)
        book.setIds(ids)

        val fresh = book.reset(courses)

        assertEquals(mapOf("CS1" to 0xAF8C4C), fresh)
        assertTrue(saved.last().awaiting.orEmpty().isEmpty())
    }

    @Test
    fun `two courses whose ids end alike share the color, and the book keeps both`() {
        val book = book(known = mapOf("1151CS1" to 4127, "1151CS2" to 17))

        book.reconcile(listOf(course("CS1"), course("CS2")))

        assertEquals(mapOf("CS1" to 0xAF8C4C, "CS2" to 0xAF8C4C), book.colors())
    }

    @Test
    fun `a sign-out forgets every course`() {
        val book = book()
        book.reconcile(listOf(course("CS1")))

        book.clear()
        book.setIds(ids)

        assertTrue(book.colors().isEmpty())
        // The next account's course of the same number is new to the book.
        assertTrue(book.reconcile(listOf(course("CS1"))))
    }

    @Test
    fun `a state saved without a field reads back, as one from an older build would`() {
        val book = book(saved = MoodleColorState(known = listOf("CS1")))

        assertFalse(book.reconcile(listOf(course("CS1"))))
        assertTrue(book.colors().isEmpty())
    }
}
