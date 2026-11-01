package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableCourseMerge
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableTermFetch
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableTermSource
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Home's and the background sync's commit of a fetch, [commitFetchedTerm], against a past term's
 * rebuild on the class table that lands while the fetch's lookups run. The calendar still has the
 * term in session while course selection and the transcript have moved past it, so the fetch only
 * gains, and it looks up every course the term held as it began, the rebuild's junk included.
 * Course numbers are made up.
 */
class FetchedTermCommitTest {

    private fun newCache(): DataCache {
        val context = JvmContext()
        return DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    private var cache = newCache()

    /** The term's one class is [ENROLLED], and a later term is listed: the class table rebuilds it. */
    private val report = ScoreReport(
        student = "",
        currentTerm = "",
        rankings = emptyList(),
        courses = listOf(
            CourseGrade(term = TERM, code = ENROLLED, name = ENROLLED, status = GradeStatus.GRADED),
            CourseGrade(term = LATER_TERM, code = LATER, name = LATER, status = GradeStatus.PENDING),
        ),
        creditSummary = CreditSummary.EMPTY,
    )

    private fun portal(no: String) = Course(courseNo = no, courseName = no, moodleIdNumber = "$TERM$no")

    /** As the school answers a lookup of [no] now: with class times and a room. */
    private fun answered(no: String) =
        Course.fromSchedule(no, "$no now", classroom = "IB-202", schedule = mapOf(2 to listOf("5")), moodleIdNumber = "$TERM$no")

    /** [TERM] rebuilt from [report] and saved, as the class table's fetchTermCourses commits one. */
    private suspend fun rebuildTerm() {
        val fetch = ClassTableTermFetch { _, courseNo, _, _, _ -> LookedUpCourse(courseNo, answered(courseNo), answered = true) }
        val source = ClassTableTermSource.of(TERM, selection = null, report, servedBySelection = false, cached = cache.loadCourses(TERM))
        assertTrue(source is ClassTableTermSource.Rebuild)
        val answers = fetch.lookUp(TERM, source.rosterNos, emptyMap(), before = cache.loadCourses(TERM))
        val rebuilt = fetch.committed(
            answers, source, TERM, moodleNos = emptyList(),
            latest = { cache.loadCourses(TERM) },
            waitingAdds = { emptySet() },
            droppedNos = { cache.loadSelectionDroppedNos()[TERM].orEmpty().toSet() },
            recordFinalRoster = { cache.recordFinalRoster(TERM, it.roster, alsoListed = it.dropping) },
        ) { drawn ->
            fetch.fold(drawn, cache.loadCourses(TERM), emptyMap(), "zh", CourseTombstoneKeys.hiddenIn(TERM, cache.loadDeletedCourseNos()))
        }
        cache.saveCourses(rebuilt!!, TERM)
    }

    /**
     * The term only gaining from the transcript on disk, its courses looked up from the list read
     * as the fetch began, with [duringLookups] run while they are out, and drawn as Home's
     * fetchCoursesAndAssignments and the background sync's syncCourses draw them.
     */
    private suspend fun gainingFetch(duringLookups: suspend () -> Unit): Pair<TranscriptTerms.TermCourses, List<Course>> {
        val cached = cache.loadCourses(TERM)
        val term = TranscriptTerms.termFrom(emptyList(), report, TERM, cached)!!
        assertTrue(term.onlyGains)
        val looked = term.courseNos.map(::answered)
        duringLookups()
        return term to TranscriptTerms.drawnCourses(looked, fromSelection = term.roster != null, cached = cached.mapTo(mutableSetOf()) { it.courseNo })
    }

    /** Home's fetch, committed as fetchData commits it; the list it shows. */
    private suspend fun homeFetch(waitingAdds: Set<String> = emptySet(), duringLookups: suspend () -> Unit): List<Course> {
        val (term, fetched) = gainingFetch(duringLookups)
        return commitFetchedTerm(cache, TERM, fetched, term.onlyGains, answer = null, waitingAdds = { waitingAdds }) { rows, current, hidden ->
            HomeCourseMerge.mergeRemote(rows, current, emptyMap(), "zh", hidden, onlyGains = term.onlyGains)
        }!!
    }

    /** The background sync's fetch, committed as syncCourses commits it; the list it uploads. */
    private suspend fun workerFetch(waitingAdds: Set<String> = emptySet(), duringLookups: suspend () -> Unit): List<Course> {
        val (term, fetched) = gainingFetch(duringLookups)
        val unresolvedNos = term.courseNos.toSet() - fetched.mapTo(mutableSetOf()) { it.courseNo }
        return commitFetchedTerm(cache, TERM, fetched, term.onlyGains, answer = null, waitingAdds = { waitingAdds }) { rows, current, hidden ->
            ClassTableCourseMerge.mergeFetched(
                rows, current, emptyMap(), "zh", hidden,
                alsoKeeps = { term.onlyGains || it.courseNo in unresolvedNos },
            )
        }!!
    }

    private suspend fun rebuildDroppingJunk() {
        rebuildTerm()
        assertEquals(setOf(JUNK), cache.loadSelectionDroppedNos()[TERM].orEmpty().toSet())
        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `Home's gaining fetch does not bring back a course a rebuild dropped while its lookups ran`() = runTest {
        cache.saveCourses(listOf(portal(ENROLLED), portal(JUNK)), TERM)

        val shown = homeFetch { rebuildDroppingJunk() }

        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(listOf(ENROLLED), shown.map { it.courseNo })
        // Left out, not hidden: listed again, it comes back.
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
    }

    @Test
    fun `the background sync's gaining fetch neither brings back nor uploads a course a rebuild dropped while its lookups ran`() = runTest {
        cache.saveCourses(listOf(portal(ENROLLED), portal(JUNK)), TERM)

        val uploaded = workerFetch { rebuildDroppingJunk() }

        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(listOf(ENROLLED), uploaded.map { it.courseNo })
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
    }

    /**
     * No drop takes a course added by hand: one added here, with no Moodle ID, or one whose add is
     * still waiting to go up. Each takes the fetch's answer, as it would with no drop at all,
     * rather than keeping the row it was added with.
     */
    @Test
    fun `a course added back by hand after the rebuild dropped it takes the fetch's answer, in Home and the background sync`() = runTest {
        for (commit in listOf(::homeFetch, ::workerFetch)) {
            cache = newCache()
            cache.saveCourses(listOf(portal(ENROLLED), portal(JUNK), portal(WAITING)), TERM)

            val committed = commit(setOf(WAITING)) {
                rebuildTerm()
                assertEquals(setOf(JUNK, WAITING), cache.loadSelectionDroppedNos()[TERM].orEmpty().toSet())
                // Both added back by hand: WAITING with the Moodle ID it was picked with, its add not up yet.
                val stale = mapOf(1 to listOf("1"))
                cache.saveCourses(
                    cache.loadCourses(TERM) +
                        Course.fromSchedule(JUNK, JUNK, classroom = "TR-313", schedule = stale, isManual = true) +
                        Course.fromSchedule(WAITING, WAITING, classroom = "TR-313", schedule = stale, isManual = true, moodleIdNumber = "$TERM$WAITING"),
                    TERM,
                )
            }

            val term = cache.loadCourses(TERM)
            assertEquals(committed.map { it.courseNo }, term.map { it.courseNo })
            for (no in listOf(JUNK, WAITING)) {
                val row = term.single { it.courseNo == no }
                assertTrue(row.isManual)
                assertEquals("IB-202", row.classroom)
            }
            assertNull(term.single { it.courseNo == JUNK }.moodleIdNumber)
        }
    }

    /**
     * A running term's transcript may still list a course another device's roster dropped since,
     * and a fetch that only gains would put it back at every fetch for each sync to take out
     * again. Read as the commit saves, as a sync may have found the drop while the lookups ran.
     * Course selection's answer, which this phone always asks the school for, saves what it
     * lists, and its roster enrolls it again. A course added here stays either way. As the
     * iPhone's `savedCoursesSkipServerDrops` (tigerduck-app#918).
     */
    @Test
    fun `a fetch only gaining leaves out a course a roster dropped on the server, but one added here, and an answer saves it`() = runTest {
        val fetched = listOf(answered(ENROLLED), answered(DROPPED), answered(ADDED_HERE))
        suspend fun commit(onlyGains: Boolean) = commitFetchedTerm(
            cache, TERM, fetched, onlyGains, answer = null, waitingAdds = { emptySet() },
        ) { rows, current, hidden ->
            ClassTableCourseMerge.mergeFetched(rows, current, emptyMap(), "zh", hidden, alsoKeeps = { onlyGains })
        }!!
        cache.saveCourses(listOf(portal(ENROLLED), Course(courseNo = ADDED_HERE, courseName = ADDED_HERE, isManual = true)), TERM)
        cache.recordServerDrops(TERM, mapOf(DROPPED to null, ADDED_HERE to null))

        assertEquals(listOf(ENROLLED, ADDED_HERE), commit(onlyGains = true).map { it.courseNo })
        assertEquals(listOf(ENROLLED, ADDED_HERE), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(listOf(ENROLLED, DROPPED, ADDED_HERE), commit(onlyGains = false).map { it.courseNo })
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
    }

    /**
     * A fetch only gaining draws every course the term held as it began. While its lookups ran, a
     * full sync found one dropped on the server, and the drop list here names the other: every
     * course it drew is dropped as it saves. Their answers are left out, and so is the term's own
     * copy of them, which a term only gaining would keep, shown and uploaded as written. A course
     * saved meanwhile stays, as the term only gains, and none is hidden. A course added here, and
     * one whose add is waiting, stay though the drops name them. As the iPhone's
     * `savedCoursesSkipServerDrops`, which saves such a term empty (tigerduck-app#918).
     */
    @Test
    fun `a fetch only gaining whose every drawn course was dropped as it saves takes them off the term, in Home and the background sync`() = runTest {
        for (commit in listOf(::homeFetch, ::workerFetch)) {
            cache = newCache()
            cache.saveCourses(
                listOf(
                    portal(ENROLLED),
                    portal(DROPPED),
                    Course(courseNo = ADDED_HERE, courseName = ADDED_HERE, isManual = true),
                    Course(courseNo = WAITING, courseName = WAITING, isManual = true, moodleIdNumber = "$TERM$WAITING"),
                ),
                TERM,
            )

            val committed = commit(setOf(WAITING)) {
                cache.recordServerDrops(TERM, mapOf(ENROLLED to null, ADDED_HERE to null))
                cache.saveSelectionDroppedNos(mapOf(TERM to listOf(DROPPED, WAITING)))
                // Another fetch's save, which this one did not draw.
                cache.saveCourses(cache.loadCourses(TERM) + portal(KEPT), TERM)
            }

            assertEquals(listOf(KEPT, ADDED_HERE, WAITING), cache.loadCourses(TERM).map { it.courseNo })
            assertEquals(listOf(KEPT, ADDED_HERE, WAITING), committed.map { it.courseNo })
            assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
        }
    }

    /**
     * Course selection's answer is the term's whole list: it saves every course it lists, though
     * a sync found each dropped while its lookups ran, and a course fetched here that it does not
     * list leaves the term.
     */
    @Test
    fun `course selection's answer saves every course it lists though the drops name each, in Home and the background sync`() = runTest {
        for (homeMerge in listOf(true, false)) {
            cache = newCache()
            cache.saveCourses(listOf(portal(ENROLLED), portal(DROPPED), portal(JUNK)), TERM)
            val answer = cache.recordSelectionRoster(TERM, listOf(ENROLLED, DROPPED))?.answer!!
            // A full sync while the lookups ran, its drops of no known time, which no answer is newer than.
            cache.recordServerDrops(TERM, mapOf(ENROLLED to null))
            cache.saveSelectionDroppedNos(mapOf(TERM to listOf(DROPPED, JUNK)))

            val saved = commitFetchedTerm(
                cache, TERM, listOf(answered(ENROLLED), answered(DROPPED)), onlyGains = false, answer,
                waitingAdds = { emptySet() },
            ) { rows, current, hidden ->
                if (homeMerge) HomeCourseMerge.mergeRemote(rows, current, emptyMap(), "zh", hidden, onlyGains = false)
                else ClassTableCourseMerge.mergeFetched(rows, current, emptyMap(), "zh", hidden)
            }!!

            assertEquals(listOf(ENROLLED, DROPPED), saved.map { it.courseNo })
            assertEquals(listOf(ENROLLED, DROPPED), cache.loadCourses(TERM).map { it.courseNo })
        }
    }

    private companion object {
        const val TERM = "1151"
        const val LATER_TERM = "1152"
        const val ENROLLED = "XA0000001"
        const val DROPPED = "XB0000002"
        const val ADDED_HERE = "XF0000006"
        const val KEPT = "XK0000011"
        const val JUNK = "XH0000008"
        const val WAITING = "XW0000023"
        const val LATER = "XZ0000099"
    }
}
