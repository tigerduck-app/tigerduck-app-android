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

    private val ALL = listOf(
        course("CS1"), course("CS2"), course("CS3"), course("CS4"), course("CS5", moodle = false),
    )

    private val ids = mapOf("1151CS1" to 4127, "1151CS2" to 5337, "1151CS3" to 902)

    private fun course(no: String, moodle: Boolean = true, pick: String? = null) = Course(
        courseNo = no,
        courseName = no,
        moodleIdNumber = if (moodle) "1151$no" else null,
        customColorHex = pick,
    )

    private fun book(
        saved: MoodleColorState? = null,
        legacy: Set<String> = emptySet(),
        known: Map<String, Int> = ids,
        upgrading: Boolean = false,
    ) = MoodleColorBook().apply {
        setIds(known)
        load(saved, legacy, upgrading)
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

        assertEquals(mapOf("CS1" to 0xAF8C4C), book.colorsFor(ALL))
    }

    @Test
    fun `a new course with no Moodle id waits and takes no Moodle color`() {
        val saved = mutableListOf<MoodleColorState>()
        val book = book().apply { persistWith { saved += it } }

        assertFalse(book.reconcile(listOf(course("CS4"), course("CS5", moodle = false))))

        assertTrue(book.colorsFor(ALL).isEmpty())
        assertEquals(listOf("1151CS4", "CS5"), saved.last().awaiting)
    }

    @Test
    fun `a waiting course moves to its Moodle color when the id arrives, and stays there`() {
        val book = book(known = emptyMap())
        val courses = listOf(course("CS1"))
        book.reconcile(courses)
        assertTrue(book.colorsFor(ALL).isEmpty())

        book.setIds(ids)

        assertTrue(book.reconcile(courses))
        assertEquals(mapOf("CS1" to 0xAF8C4C), book.colorsFor(ALL))
        assertFalse(book.reconcile(courses))
        book.setIds(emptyMap())
        book.reconcile(courses)
        assertEquals(mapOf("CS1" to 0xAF8C4C), book.colorsFor(ALL))
    }

    @Test
    fun `a pick, even of the same color, ends the wait`() {
        val book = book(known = emptyMap())
        book.reconcile(listOf(course("CS1")))

        book.reconcile(listOf(course("CS1", pick = "#AF8C4C")))
        book.setIds(ids)

        assertFalse(book.reconcile(listOf(course("CS1", pick = "#AF8C4C"))))
        assertTrue(book.colorsFor(ALL).isEmpty())
    }

    @Test
    fun `a color pulled for a waiting course ends the wait too`() {
        val book = book(known = emptyMap())
        book.reconcile(listOf(course("CS1")))

        book.reconcile(listOf(course("CS1", pick = "#123456")))
        book.setIds(ids)
        book.reconcile(listOf(course("CS1", pick = "#123456")))

        assertTrue(book.colorsFor(ALL).isEmpty())
    }

    @Test
    fun `a course colored before this existed never moves, whatever the ids say`() {
        val book = book(saved = null, legacy = setOf("1151CS1", "1151CS3"))

        assertFalse(book.reconcile(listOf(course("CS1"), course("CS3"))))

        assertTrue(book.colorsFor(ALL).isEmpty())
    }

    @Test
    fun `an upgrade keeps old courses and gives a course added later its Moodle color`() {
        val book = book(saved = null, legacy = setOf("1151CS1"))

        book.reconcile(listOf(course("CS1"), course("CS2")))

        assertEquals(mapOf("CS2" to MoodleColorBook.forCourseId(5337)), book.colorsFor(ALL))
    }

    @Test
    fun `nothing is given before the book is loaded`() {
        val book = MoodleColorBook().apply { setIds(ids) }

        assertFalse(book.reconcile(listOf(course("CS1"))))
        assertTrue(book.colorsFor(ALL).isEmpty())
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

        assertEquals(mapOf("CS1" to 0xAF8C4C, "CS2" to 0xAF8C4C), book.colorsFor(ALL))
    }

    @Test
    fun `a sign-out forgets every course`() {
        val book = book()
        book.reconcile(listOf(course("CS1")))

        book.clear()
        book.setIds(ids)

        assertTrue(book.colorsFor(ALL).isEmpty())
        // The next account's course of the same number is new to the book.
        assertTrue(book.reconcile(listOf(course("CS1"))))
    }

    @Test
    fun `a course number back in another term with another Moodle id is a different course`() {
        val book = book(known = mapOf("1151CS1" to 4127, "1152CS1" to 5339))
        val fall = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1")
        val spring = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1152CS1")

        book.reconcile(listOf(fall))
        book.reconcile(listOf(spring))

        assertEquals(mapOf("CS1" to MoodleColorBook.forCourseId(4127)), book.colorsFor(listOf(fall)))
        assertEquals(mapOf("CS1" to MoodleColorBook.forCourseId(5339)), book.colorsFor(listOf(spring)))
    }

    @Test
    fun `a legacy course is legacy in its own term only`() {
        val book = book(saved = null, legacy = setOf(MoodleColorBook.identOf(course("CS1"))))
        val spring = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1152CS1")
        book.setIds(mapOf("1152CS1" to 5339))

        book.reconcile(listOf(course("CS1"), spring))

        assertEquals(mapOf("CS1" to MoodleColorBook.forCourseId(5339)), book.colorsFor(listOf(spring)))
        assertTrue(book.colorsFor(listOf(course("CS1"))).isEmpty())
    }

    @Test
    fun `an upgrade with nothing cached keeps whatever the first refresh brings`() {
        val book = book(saved = null, legacy = emptySet(), upgrading = true)

        assertFalse(book.reconcile(listOf(course("CS1"), course("CS2"))))
        assertTrue(book.colorsFor(ALL).isEmpty())
        // The first refresh has come: a course added afterwards is new.
        assertTrue(book.reconcile(listOf(course("CS1"), course("CS2"), course("CS3"))))
        assertEquals(setOf("CS3"), book.colorsFor(ALL).keys)
    }

    @Test
    fun `a list of hand-added courses alone does not end the upgrade`() {
        val book = book(saved = null, legacy = emptySet(), upgrading = true)
        val hand = course("CS9").copy(isManual = true)

        book.reconcile(listOf(hand))
        book.reconcile(listOf(hand, course("CS1")))

        assertTrue(book.colorsFor(ALL).isEmpty())
    }

    @Test
    fun `a reset keeps the Moodle color the book remembers or the id on the course, with no id map`() {
        val book = book()
        val remembered = course("CS1")
        book.reconcile(listOf(remembered))
        book.setIds(emptyMap())
        val numeric = course("CS3").copy(moodleNumericCourseId = 902)
        val neither = course("CS5", moodle = false)

        val fresh = book.reset(listOf(remembered, numeric, neither))

        assertEquals(mapOf("CS1" to 0xAF8C4C, "CS3" to MoodleColorBook.forCourseId(902)), fresh)
    }

    @Test
    fun `the watch is sent the Moodle color of a course with no pick, and a pick is left alone`() {
        val book = book()
        val courses = listOf(course("CS1"), course("CS2", pick = "#123456"), course("CS5", moodle = false))
        book.reconcile(courses)

        val sent = book.withColors(courses)

        assertEquals(listOf("#AF8C4C", "#123456", null), sent.map { it.customColorHex })
    }

    @Test
    fun `a state saved without a field reads back, as one from an older build would`() {
        val book = book(saved = MoodleColorState(known = listOf("1151CS1")))

        assertFalse(book.reconcile(listOf(course("CS1"))))
        assertTrue(book.colorsFor(ALL).isEmpty())
    }
}
