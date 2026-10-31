package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.shared.Course

/**
 * Everything the user did to a timetable has to survive the next refresh.
 * Each case here is one thing they could lose.
 */
class ClassTableCourseMergeTest {

    private fun course(
        no: String,
        name: String = "Course $no",
        color: String? = null,
        manual: Boolean = false,
        override: String? = null,
    ) = Course(
        courseNo = no,
        courseName = name,
        customColorHex = color,
        isManual = manual,
        customCourseName = override,
    )

    private fun merge(
        fetched: List<Course>,
        cached: List<Course> = emptyList(),
        names: CustomNameMap = emptyMap(),
        locale: String = "zh",
        deletedNos: Set<String> = emptySet(),
    ) = ClassTableCourseMerge.mergeFetched(fetched, cached, names, locale, deletedNos)

    @Test
    fun `the colour the user picked is carried across`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("A", color = "#FF0000")),
        )
        assertEquals("#FF0000", out.single().customColorHex)
    }

    @Test
    fun `the manual flag is carried across when the school also lists the course`() {
        // Losing it would send a later delete down the wrong path.
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("A", manual = true)),
        )
        assertTrue(out.single().isManual)
    }

    @Test
    fun `a course added here stays added here when the school also lists it`() {
        // Its Moodle identity stays the one it was added with, none, as a lookup again keeps it:
        // with the school's, it would read as merged, and a past term's rebuild could drop it.
        val out = merge(
            fetched = listOf(course("A").copy(moodleIdNumber = "1141A", moodleNumericCourseId = 42)),
            cached = listOf(course("A", manual = true)),
        ).single()
        assertTrue(out.isManual)
        assertNull(out.moodleIdNumber)
        assertNull(out.moodleNumericCourseId)
    }

    @Test
    fun `a merged course takes the school's Moodle identity`() {
        val out = merge(
            fetched = listOf(course("A").copy(moodleIdNumber = "1141A")),
            cached = listOf(course("A", manual = true).copy(moodleIdNumber = "1141A-old")),
        ).single()
        assertEquals("1141A", out.moodleIdNumber)
    }

    @Test
    fun `a course only the user knows about is kept`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("MINE", manual = true)),
        )
        assertEquals(setOf("A", "MINE"), out.map { it.courseNo }.toSet())
    }

    @Test
    fun `a non-manual cached course the roster dropped is gone`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("A"), course("STALE")),
        )
        assertEquals(listOf("A"), out.map { it.courseNo })
    }

    /**
     * A fetch that only gains from the transcript removes nothing: a school course it does not
     * list, saved by another fetch while its lookups ran, stays, renamed as any other, and a
     * course deleted meanwhile is still taken off (tigerduck-app#890).
     */
    @Test
    fun `a school course the fetch does not list stays when the merge is told to keep it, but not one deleted`() {
        val out = ClassTableCourseMerge.mergeFetched(
            fetched = listOf(course("A")),
            cached = listOf(course("A"), course("B"), course("GONE"), course("STALE")),
            names = CourseNameOverrides.withOverride(emptyMap(), "B", "zh", "Renamed"),
            locale = "zh",
            deletedNos = setOf("GONE"),
            alsoKeeps = { it.courseNo != "STALE" },
        )
        assertEquals(listOf("A", "B"), out.map { it.courseNo })
        assertEquals("Renamed", out[1].displayName)
    }

    @Test
    fun `the rename for the current locale is applied`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "A", "en", "Renamed")
        val out = merge(fetched = listOf(course("A")), names = names, locale = "en")
        assertEquals("Renamed", out.single().customCourseName)
    }

    @Test
    fun `a rename in another locale is not applied`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "A", "en", "Renamed")
        val out = merge(fetched = listOf(course("A")), names = names, locale = "zh")
        assertNull(out.single().customCourseName)
    }

    @Test
    fun `a stale override on the fetched course is cleared, not kept`() {
        // The fetched rows can carry an override from an earlier locale's
        // cache; the map is authoritative, so an unset name must clear.
        val out = merge(fetched = listOf(course("A", override = "stale")), names = emptyMap())
        assertNull(out.single().customCourseName)
    }

    @Test
    fun `a manual leftover still gets its rename`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "MINE", "zh", "我的課")
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("MINE", manual = true)),
            names = names,
        )
        assertEquals("我的課", out.single { it.courseNo == "MINE" }.customCourseName)
    }

    @Test
    fun `a deleted course does not come back through the roster`() {
        val out = merge(fetched = listOf(course("A"), course("GONE")), deletedNos = setOf("GONE"))
        assertEquals(listOf("A"), out.map { it.courseNo })
    }

    @Test
    fun `a deleted course does not come back through the manual leftovers`() {
        val out = merge(
            fetched = listOf(course("A")),
            cached = listOf(course("GONE", manual = true)),
            deletedNos = setOf("GONE"),
        )
        assertEquals(listOf("A"), out.map { it.courseNo })
    }

    @Test
    fun `a course absent from cache gets no colour and is not manual`() {
        val out = merge(fetched = listOf(course("NEW", color = "#00FF00", manual = true)))
        assertNull(out.single().customColorHex)
        assertFalse(out.single().isManual)
    }

    // --- Looking a stored course up again (tigerduck-app-android#264) ---

    /** A hand-added course as stored: the snapshot taken when it was added, plus what the user did to it. */
    private val stored = Course.fromSchedule(
        courseNo = "MINE",
        courseName = "Old name",
        instructor = "Old teacher",
        credits = 2f,
        classroom = "T4-101",
        enrolledCount = 10,
        maxCount = 40,
        schedule = mapOf(1 to listOf("3", "4")),
        classroomMap = mapOf("1-3" to "T4-101", "1-4" to "T4-101"),
        moodleIdNumber = "1151MINE",
        moodleNumericCourseId = 77,
        isManual = true,
        dimension = null,
        allYear = "H",
    ).copy(customColorHex = "#FF0000", customCourseName = "我的課")

    /** The same course as QueryCourse has it now: moved to another room and day, renamed. */
    private val fresh = Course.fromSchedule(
        courseNo = "MINE",
        courseName = "New name",
        instructor = "New teacher",
        credits = 3f,
        classroom = "IB-202",
        enrolledCount = 25,
        maxCount = 50,
        schedule = mapOf(2 to listOf("5", "6")),
        classroomMap = mapOf("2-5" to "IB-202", "2-6" to "IB-202"),
        moodleIdNumber = "1151MINE-from-lookup",
        moodleNumericCourseId = null,
        dimension = "C",
        allYear = "F",
    )

    @Test
    fun `a course looked up again takes the school's fields and keeps the user's and its identity`() {
        val out = ClassTableCourseMerge.refreshed(stored, fresh)
        // The school's view: what a room or time change has to reach.
        assertEquals("New name", out.courseName)
        assertEquals("New teacher", out.instructor)
        assertEquals(3f, out.credits)
        assertEquals("IB-202", out.classroom)
        assertEquals(25, out.enrolledCount)
        assertEquals(50, out.maxCount)
        assertEquals(mapOf(2 to listOf("5", "6")), out.schedule)
        assertEquals(mapOf("2-5" to "IB-202", "2-6" to "IB-202"), out.classroomMap)
        assertEquals("C", out.dimension)
        assertEquals("F", out.allYear)
        // The user's: the colour, the rename, the manual flag.
        assertEquals("#FF0000", out.customColorHex)
        assertEquals("我的課", out.customCourseName)
        assertTrue(out.isManual)
        // Its identity: the overrides and the Moodle link are keyed by these.
        assertEquals("MINE", out.courseNo)
        assertEquals("1151MINE", out.moodleIdNumber)
        assertEquals(77, out.moodleNumericCourseId)
    }

    /**
     * As the iPhone's `filledHandAddKeepsNoMoodleId` (tigerduck-app#890). The iPhone fills a
     * hand-added course's empty schedule from its server row; here a lookup fills it, and the sync
     * merge never does. Either way the course keeps having no Moodle ID, so a drop still passes it.
     */
    @Test
    fun `a course added here filled in from a lookup keeps having no Moodle ID`() {
        val added = Course.fromSchedule("XB0000002", "Added here", isManual = true)
        val looked = Course.fromSchedule(
            "XB0000002", "Added here", schedule = mapOf(2 to listOf("3", "4")), moodleIdNumber = "1141XB0000002",
        )

        val filled = ClassTableCourseMerge.refreshed(added, looked)

        assertEquals(mapOf(2 to listOf("3", "4")), filled.schedule)
        assertNull(filled.moodleIdNumber)
        assertTrue(TranscriptTerms.isAddedHere(filled))
    }

    @Test
    fun `the manual courses the roster does not list are the ones to look up again`() {
        val cached = listOf(
            course("A"),
            course("LISTED", manual = true),
            course("MINE", manual = true),
        )
        // LISTED's own lookup is the roster's; A is not the user's to refresh.
        assertEquals(listOf("MINE"), ClassTableCourseMerge.manualNosToLookUp(cached, listOf("A", "LISTED")))
    }

    @Test
    fun `a looked-up manual course is refreshed in place and the rest left alone`() {
        val cached = listOf(course("A"), stored, course("OTHER", manual = true))
        val out = ClassTableCourseMerge.withLookedUp(cached, mapOf("MINE" to fresh, "UNKNOWN" to fresh))
        assertEquals(listOf("A", "MINE", "OTHER"), out.map { it.courseNo })
        assertEquals("IB-202", out[1].classroom)
        assertTrue(out[1].isManual)
        assertEquals(course("A"), out[0])
        assertEquals(course("OTHER", manual = true), out[2])
    }

    @Test
    fun `a looked-up manual course carries its new room through the merge`() {
        val cached = ClassTableCourseMerge.withLookedUp(listOf(course("A"), stored), mapOf("MINE" to fresh))
        val out = merge(fetched = listOf(course("A")), cached = cached)
        val mine = out.single { it.courseNo == "MINE" }
        assertEquals("IB-202", mine.classroom)
        assertTrue(mine.isManual)
        assertEquals("#FF0000", mine.customColorHex)
    }

    @Test
    fun `the courses without a room are looked up again, stubs without a schedule not`() {
        val timed = Course.fromSchedule("TIMED", "Timed", schedule = mapOf(1 to listOf("1")))
        val roomed = Course.fromSchedule("ROOMED", "Roomed", classroom = "T4-101", schedule = mapOf(1 to listOf("1")))
        // A historical term's Moodle stub: QueryCourse does not index it, so asking again is a request for nothing.
        val stub = Course.fromSchedule("STUB", "Stub")
        assertEquals(listOf("TIMED"), ClassTableCourseMerge.nosWithoutRoom(listOf(roomed, timed, stub)))
    }

    @Test
    fun `a room found takes the course's row, a room still missing changes nothing`() {
        val noRoom = Course.fromSchedule("A", "A", schedule = mapOf(1 to listOf("1")), isManual = true)
            .copy(customColorHex = "#00FF00")
        val roomed = Course.fromSchedule("B", "B", classroom = "T4-101", schedule = mapOf(1 to listOf("1")))
        val found = Course.fromSchedule("A", "A now", classroom = "IB-202", schedule = mapOf(1 to listOf("1", "2")))
        val stillNone = Course.fromSchedule("A", "A now", schedule = mapOf(1 to listOf("1", "2")))

        val out = ClassTableCourseMerge.withRoomsFound(listOf(noRoom, roomed), mapOf("A" to found, "B" to found))
        assertEquals("IB-202", out!![0].classroom)
        assertEquals(mapOf(1 to listOf("1", "2")), out[0].schedule)
        assertEquals("#00FF00", out[0].customColorHex)
        assertTrue(out[0].isManual)
        // B had a room; the room pass is not a refresh of it.
        assertEquals(roomed, out[1])

        assertNull(ClassTableCourseMerge.withRoomsFound(listOf(noRoom, roomed), mapOf("A" to stillNone)))
        assertNull(ClassTableCourseMerge.withRoomsFound(listOf(noRoom, roomed), emptyMap()))
    }
}
