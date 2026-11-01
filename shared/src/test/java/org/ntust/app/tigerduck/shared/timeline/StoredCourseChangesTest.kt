package org.ntust.app.tigerduck.shared.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * `CourseChangeStore` (in :app) keeps the feed's own JSON under the `payload` key and reads it back by hand
 * with `CourseChanges.decode`; no Gson and no class name is in that copy. This fixture is such a
 * copy as the current build stores it (the feed's body, one row of a kind this build skips), so a
 * build that moves the types still has to read what the last one wrote.
 */
class StoredCourseChangesTest {

    // The module's working directory is its own folder under Gradle, in :app and in :shared alike.
    private val stored = File("../shared/src/test/resources/timeline/course_changes_v1.json").readText()

    @Test
    fun `a stored copy from the current build still decodes`() {
        val changes = CourseChanges.decode(stored)

        assertEquals("1151", changes.term)
        assertEquals(
            listOf(101, 102, 103, 104),
            changes.changes("CC900", thursday).map { it.id },
        )
        assertEquals(
            listOf(
                CourseChange.Kind.CANCELLED,
                CourseChange.Kind.ROOM,
                CourseChange.Kind.END,
                CourseChange.Kind.NOTE,
            ),
            changes.changes("CC900", thursday).map { it.kind },
        )
        assertEquals("TR-313", changes.changes("CC900", thursday)[1].room)
        assertEquals("11:45", changes.changes("CC900", thursday)[2].end)
        assertEquals("the English note falls back to the Chinese one", "期中考", changes.changes("CC900", thursday)[3].note(Locale.ENGLISH))
        assertEquals(listOf(thursday), changes.days("CC900"))
        assertTrue(changes.changes("CC900", LocalDate.of(2026, 11, 12)).isEmpty())

        val slots = CourseMeetings.buildSlots(listOf(CourseChangeFixture.course()), thursday, changes)
        assertEquals(listOf("CC900_20261105_2", "CC900_20261105_7"), slots.map { it.id })
        assertEquals(CourseChangeFixture.time("11:45"), slots[0].end)
        assertEquals(listOf(false, true), slots.map { it.isCancelled })
    }
}
