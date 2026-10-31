package org.ntust.app.tigerduck.data.cache

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * An ended term's roster from its transcript (iOS `DataCache.recordSelectionRoster` with
 * `isFinal`, tigerduck-app#890): it drops what the term held and what it may hold on the server,
 * even when it lists no class, and is no course selection answer. Made-up course numbers.
 */
class DataCacheFinalRosterTest {

    private val context = JvmContext()
    private lateinit var cache: DataCache

    private val timed = Course.fromSchedule("XA0000001", "Timed", schedule = mapOf(2 to listOf("3")))
    private val junk = Course.fromSchedule("XC0000003", "Junk", moodleIdNumber = "${TERM}XC0000003")
    private val addedHere = Course.fromSchedule("XF0000006", "Added here", isManual = true)

    @Before
    fun setUp() {
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    private suspend fun dropped(): Set<String> = cache.loadSelectionDroppedNos()[TERM].orEmpty().toSet()

    @Test
    fun `the drops are the earlier ones, the term's portal courses and those listed with it, but the roster`() = runTest {
        cache.saveSelectionDroppedNos(mapOf(TERM to listOf("XD0000004"), "1142" to listOf("XJ0000009")))
        cache.saveCourses(listOf(timed, junk, addedHere), TERM)

        cache.recordFinalRoster(TERM, roster = listOf("XA0000001", "XD0000004"), alsoListed = listOf("XN0000015"))

        assertEquals(setOf("XC0000003", "XN0000015"), dropped())
        assertEquals(listOf("XJ0000009"), cache.loadSelectionDroppedNos()["1142"])
    }

    @Test
    fun `an empty final roster drops everything the term held, but a course added by hand`() = runTest {
        cache.saveCourses(listOf(timed, junk, addedHere), TERM)

        cache.recordFinalRoster(TERM, roster = emptyList(), alsoListed = listOf("XH0000008"))

        assertEquals(setOf("XA0000001", "XC0000003", "XH0000008"), dropped())
    }

    @Test
    fun `a final roster is no course selection answer`() = runTest {
        cache.saveCourses(listOf(timed, addedHere), TERM)

        cache.recordFinalRoster(TERM, roster = listOf("XA0000001"), alsoListed = emptyList())

        assertFalse(File(context.filesDir, "TigerDuckData/selection_rosters.json").exists())
    }

    /**
     * A hand-added course deleted after course selection's answer left it out holds a marked hide,
     * which only a later course selection answer lifts: the student enrolled in it after all. A
     * transcript naming it says nothing new, so the hide binds.
     */
    @Test
    fun `a final roster lifts no hide of a course added by hand`() = runTest {
        cache.saveCourses(listOf(timed, addedHere), TERM)
        cache.recordSelectionRoster(TERM, listOf("XA0000001"))
        cache.hideDeletedCourse(addedHere.courseNo, TERM, addedHere)
        cache.saveCourses(listOf(timed), TERM)

        cache.recordFinalRoster(TERM, roster = listOf("XA0000001", "XF0000006"), alsoListed = emptyList())

        assertTrue("XF0000006" in CourseTombstoneKeys.hiddenIn(TERM, cache.loadDeletedCourseNos()))
        // The hide was marked: course selection's answer naming the course lifts it.
        cache.recordSelectionRoster(TERM, listOf("XA0000001", "XF0000006"))
        assertFalse("XF0000006" in CourseTombstoneKeys.hiddenIn(TERM, cache.loadDeletedCourseNos()))
    }

    private companion object {
        const val TERM = "1141"
    }
}
