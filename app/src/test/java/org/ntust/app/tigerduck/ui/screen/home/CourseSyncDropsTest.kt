package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The sync merge's handling of dropped courses: the ones this phone's course selection, or a
 * past term's transcript, put on the drop list, and the rows a roster upload marked `dropped` on
 * the server (backend 3.10.1, tigerduck-app-android#595). A drop never takes a course added by
 * hand, here or on another device, and a merged copy of a dropped course leaves the term with no
 * hide, so it comes back once the course is enrolled again.
 *
 * As the iPhone's `SelectionDropsTests` (tigerduck-app#914) and the reconcile cases of its
 * `PastTermRebuildTests` (tigerduck-app#890). Course numbers are made up.
 */
class CourseSyncDropsTest {

    private fun fetched(no: String) = Course(courseNo = no, courseName = no)

    /** Added by hand on this phone: manual, with no Moodle ID. */
    private fun addedHere(no: String) = Course(courseNo = no, courseName = no, isManual = true)

    /** Merged down from a server row, whose Moodle ID it takes. */
    private fun mergedCopy(no: String) = Course(courseNo = no, courseName = no, isManual = true, moodleIdNumber = "$TERM$no")

    private fun row(no: String, source: String? = "ntust_portal", status: String? = null) =
        ServerCourse(no, no, TERM, moodleId = "$TERM$no", source = source, enrollmentStatus = status)

    private fun reconcile(
        local: List<Course>,
        rows: List<ServerCourse>,
        selectionDropped: Set<String> = emptySet(),
        tombstones: Set<String> = emptySet(),
        serverKnown: Set<String> = local.mapTo(mutableSetOf()) { it.courseNo },
    ) = CourseSyncReconciler.reconcileSemester(
        semester = TERM,
        localCourses = local,
        serverRows = rows,
        tombstoneNos = emptySet(),
        tombstones = tombstones,
        serverKnownNos = serverKnown,
        selectionDroppedNos = selectionDropped,
    )

    /** The term's rows once another device's roster upload stopped listing four of them. */
    private val rowsAfterARoster = listOf(
        row(ENROLLED, status = "enrolled"),
        row(NO_STATUS),
        row(ADDED, status = "dropped"),
        row(MERGED, status = "dropped"),
        row(FETCHED, status = "dropped"),
        row(ELSEWHERE, status = "dropped"),
    )

    // --- SelectionDropsTests ---

    @Test
    fun `a dropped course added by hand here or on another device keeps its row, and one merged from the server does not`() {
        val out = reconcile(
            local = listOf(fetched(ENROLLED), addedHere(ADDED), mergedCopy(MERGED)),
            rows = listOf(row(ENROLLED), row(ADDED), row(MERGED), row(OTHER_DEVICE, source = "user_added")),
            selectionDropped = setOf(ADDED, MERGED, OTHER_DEVICE),
        )

        // Added by hand on another device: its row stays, and merges down.
        assertEquals(listOf(OTHER_DEVICE), out.merged.map { it.courseNo })
        assertEquals(setOf(MERGED), out.left)
        // Neither the course added here nor the merged copy is read as deleted elsewhere.
        assertEquals(emptySet<String>(), out.tombstones)
    }

    @Test
    fun `a row a roster dropped is not merged and takes its merged copy along, and a course added here stays`() {
        val out = reconcile(
            local = listOf(fetched(ENROLLED), addedHere(ADDED), mergedCopy(MERGED)),
            rows = rowsAfterARoster,
        )

        // A row without the field counts as enrolled.
        assertEquals(listOf(NO_STATUS), out.merged.map { it.courseNo })
        assertEquals(setOf(MERGED), out.left)
        assertEquals(emptySet<String>(), out.tombstones)
    }

    /**
     * The iPhone also takes [FETCHED] out of the courses fetched here, unless course selection
     * listed it live since the drop. That needs the time of course selection's answer, which this
     * phone does not keep yet, so here it stays for course selection to settle. Either way nothing
     * hides it, which would keep it out even once it is enrolled again.
     */
    @Test
    fun `a course a roster dropped is not hidden, and its row does not bring back a course hidden here`() {
        val out = reconcile(
            local = listOf(fetched(ENROLLED), fetched(FETCHED), addedHere(ADDED), mergedCopy(MERGED)),
            rows = rowsAfterARoster,
            tombstones = setOf("$TERM:$ELSEWHERE"),
        )

        assertEquals(setOf("$TERM:$ELSEWHERE"), out.tombstones)
        assertEquals(setOf(MERGED), out.left)
        assertTrue(out.merged.none { it.courseNo == FETCHED || it.courseNo == ELSEWHERE })
    }

    @Test
    fun `a term whose every row a roster dropped is not empty on the server, so its courses do not go up again`() {
        fun read(rows: List<ServerCourse>) =
            reconcile(local = listOf(fetched(ENROLLED)), rows = rows, selectionDropped = setOf(MERGED))

        val dropped = read(listOf(row(FETCHED, status = "dropped")))
        assertFalse(dropped.uploadLocal)
        assertTrue(dropped.merged.isEmpty())
        // Nor is the course fetched here read as deleted elsewhere.
        assertEquals(emptySet<String>(), dropped.tombstones)
        assertTrue(read(emptyList()).uploadLocal)
        // A course only this phone's drop list names leaves the term empty on the server.
        assertTrue(read(listOf(row(MERGED, status = "enrolled"))).uploadLocal)
    }

    @Test
    fun `a merged copy leaves when every row of its term was dropped on the server, and a course added here stays`() {
        val out = reconcile(
            local = listOf(addedHere(ADDED), mergedCopy(MERGED)),
            rows = rowsAfterARoster.filter { it.courseNo != ADDED }.map { it.copy(enrollmentStatus = "dropped") },
        )

        assertTrue(out.merged.isEmpty())
        assertEquals(setOf(MERGED), out.left)
        assertTrue(out.toUpload.isEmpty())
        assertEquals(emptySet<String>(), out.tombstones)
    }

    /**
     * Spec 3.3's existing bug: a course on the drop list added back by hand here had its row
     * dropped with the rest, so once the server knew the course, the next sync read its silence as
     * a delete elsewhere and hid it.
     */
    @Test
    fun `a course added back by hand after a drop keeps its row and is not hidden`() {
        val out = reconcile(
            local = listOf(fetched(ENROLLED), addedHere(ADDED)),
            rows = listOf(row(ENROLLED), row(ADDED, source = null)),
            selectionDropped = setOf(ADDED),
        )

        assertEquals(emptySet<String>(), out.tombstones)
        assertTrue(out.merged.isEmpty())
        assertTrue(out.left.isEmpty())
    }

    // --- PastTermRebuildTests ---

    @Test
    fun `a course added by hand stays through a rebuild and the reconcile, and junk merged from the server does not come back`() {
        val report = ScoreReport(
            student = "",
            currentTerm = "",
            rankings = emptyList(),
            courses = listOf(CourseGrade(term = TERM, code = ENROLLED, name = ENROLLED, status = GradeStatus.GRADED)),
            creditSummary = CreditSummary.EMPTY,
        )
        val handAdded = listOf(addedHere(ADDED), mergedCopy(OTHER_DEVICE), mergedCopy(MERGED))
        val timed = Course.fromSchedule(ENROLLED, ENROLLED, schedule = mapOf(2 to listOf("3", "4")))
        val rebuilt = TranscriptTerms.rebuiltPastTerm(
            listOf(LookedUpCourse(ENROLLED, timed, answered = true)), report, TERM,
            cached = listOf(fetched(ENROLLED)) + handAdded,
            moodleNos = listOf(ADDED, MERGED),
            waitingAdds = emptySet(),
        )
        assertFalse(ADDED in rebuilt.dropping)

        val dropped = CourseRosterMerge.selectionDrops(emptySet(), rebuilt.dropping, rebuilt.roster)
        val out = reconcile(
            local = rebuilt.courses + handAdded,
            rows = listOf(row(ENROLLED), row(ADDED), row(OTHER_DEVICE, source = "user_added"), row(MERGED)),
            selectionDropped = dropped,
        )

        assertTrue(out.merged.isEmpty())
        assertEquals(setOf(MERGED), out.left)
        assertEquals(emptySet<String>(), out.tombstones)
    }

    @Test
    fun `junk merged from the server leaves even when the drops leave the term no server row, and a course added here stays`() {
        val out = reconcile(
            local = listOf(addedHere(ADDED), mergedCopy(MERGED)),
            rows = listOf(row(MERGED)),
            selectionDropped = setOf(ADDED, MERGED),
        )

        assertEquals(setOf(MERGED), out.left)
        // The server holds nothing of the term: the course added here goes up, the junk does not.
        assertEquals(listOf(ADDED), out.toUpload.map { it.courseNo })
        assertEquals(emptySet<String>(), out.tombstones)
    }

    // --- The commit ---

    @Test
    fun `a dropped course's merged copy leaves the term, unless it was added back by hand since the read`() {
        val read = listOf(fetched(ENROLLED), mergedCopy(MERGED), mergedCopy(ADDED))
        val current = listOf(fetched(ENROLLED), mergedCopy(MERGED), addedHere(ADDED))

        val committed = CourseSyncReconciler.committedCourses(
            current, read, kept = read, merged = emptyList(), hidden = emptySet(), left = setOf(MERGED, ADDED),
            droppedHere = setOf(MERGED, ADDED),
        )

        assertEquals(listOf(ENROLLED, ADDED), committed.map { it.courseNo })
    }

    @Test
    fun `a dropped course leaves at the commit only while the drop list or the snapshot still drops it`() {
        val read = listOf(fetched(ENROLLED), fetched(FETCHED), mergedCopy(MERGED), mergedCopy(OTHER_DEVICE), fetched(ELSEWHERE))

        val committed = CourseSyncReconciler.committedCourses(
            read, read, kept = read, merged = emptyList(), hidden = emptySet(),
            left = setOf(FETCHED, MERGED, OTHER_DEVICE, ELSEWHERE),
            droppedHere = setOf(FETCHED),
            droppedOnServer = setOf(OTHER_DEVICE, ELSEWHERE),
        )

        // MERGED was listed again since the reconcile; a course fetched here that only the server
        // dropped stays for course selection to settle.
        assertEquals(listOf(ENROLLED, MERGED, ELSEWHERE), committed.map { it.courseNo })
    }

    private val context = JvmContext()

    private lateinit var cache: DataCache

    /** The colors and names waiting to go up, which nothing sends here. */
    private lateinit var overrides: PendingCourseOverrides

    @Before
    fun setUp() {
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        overrides = PendingCourseOverrides(
            cache,
            { _, _, _, _, _, _ -> false },
            records = { true },
            sends = { false },
            signInGeneration = { 0 },
        )
    }

    /**
     * A full sync of the term whose snapshot lists [rows], reconciled and committed as
     * HomeBackendSync does, with [beforeCommit] run between the two.
     */
    private suspend fun syncListing(vararg rows: ServerCourse, beforeCommit: suspend () -> Unit = {}) {
        val courseSync = cache.beginCourseSync()
        courseSync.fetching()
        try {
            val localCourses = cache.loadCourses(TERM)
            val stored = cache.loadDeletedCourseNos()
            val outcome = CourseSyncReconciler.reconcileSemester(
                semester = TERM,
                localCourses = localCourses,
                serverRows = rows.toList(),
                tombstoneNos = emptySet(),
                tombstones = stored,
                serverKnownNos = cache.loadServerKnownNos()[TERM].orEmpty().toSet(),
                selectionDroppedNos = cache.loadSelectionDroppedNos()[TERM].orEmpty().toSet(),
            )
            beforeCommit()
            cache.saveServerKnownNos(mapOf(TERM to outcome.serverKnownNos.sorted()))
            val kept = CourseSyncReconciler.pruneHidden(TERM, localCourses, outcome.tombstones)
            commitReconciledTerm(
                cache, courseSync, TERM, stored, outcome.tombstones, emptySet(), localCourses, kept, outcome.left,
                outcome.droppedOnServer, outcome.merged, overrides,
            )
        } finally {
            cache.endCourseSync(courseSync)
        }
    }

    @Test
    fun `a merged copy a roster dropped leaves the term with no hide, and comes back once enrolled again`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED)), TERM)
        syncListing(row(ENROLLED), row(MERGED))
        assertEquals(listOf(ENROLLED, MERGED), cache.loadCourses(TERM).map { it.courseNo })

        syncListing(row(ENROLLED), row(MERGED, status = "dropped"))
        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())

        syncListing(row(ENROLLED), row(MERGED, status = "enrolled"))
        assertEquals(listOf(ENROLLED, MERGED), cache.loadCourses(TERM).map { it.courseNo })
    }

    /**
     * A fetch puts what course selection stopped listing on the drop list before it saves the
     * term. Cut short between the two, it left the dropped course in the term for good: its row,
     * dropped, could neither hide it nor stand for it.
     */
    @Test
    fun `a course a fetch cut short dropped leaves the term with no hide, and comes back once listed again`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED), fetched(FETCHED)), TERM)
        cache.recordSelectionRoster(TERM, listOf(ENROLLED))

        syncListing(row(ENROLLED), row(FETCHED))
        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())

        cache.recordSelectionRoster(TERM, listOf(ENROLLED, FETCHED))
        syncListing(row(ENROLLED), row(FETCHED))
        assertEquals(listOf(ENROLLED, FETCHED), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `a fetched course on the drop list leaves the term with no hide, and does not go up`() {
        val listed = reconcile(
            local = listOf(fetched(ENROLLED), fetched(FETCHED)),
            rows = listOf(row(ENROLLED), row(FETCHED)),
            selectionDropped = setOf(FETCHED),
        )
        assertEquals(setOf(FETCHED), listed.left)
        assertEquals(emptySet<String>(), listed.tombstones)

        val empty = reconcile(
            local = listOf(fetched(ENROLLED), fetched(FETCHED)),
            rows = emptyList(),
            selectionDropped = setOf(FETCHED),
        )
        assertEquals(setOf(FETCHED), empty.left)
        assertEquals(listOf(ENROLLED), empty.toUpload.map { it.courseNo })
    }

    /**
     * A roster that lists the course again since the reconcile clears its drop, and the fetch
     * keeps the merged copy's manual flag and Moodle ID. The older commit took it out all the
     * same, and the color waiting for it with it.
     */
    @Test
    fun `a merged copy listed again while the sync ran stays, with the color waiting for it`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED), mergedCopy(MERGED)), TERM)
        cache.recordFinalRoster(TERM, listOf(ENROLLED), alsoListed = listOf(MERGED))
        overrides.send(moodleId = "$TERM$MERGED", colorHex = "#2E7D32", semester = TERM, courseNo = MERGED)

        syncListing(row(ENROLLED), row(MERGED)) {
            cache.recordFinalRoster(TERM, listOf(ENROLLED, MERGED), alsoListed = emptyList())
            cache.saveCourses(listOf(fetched(ENROLLED), mergedCopy(MERGED)), TERM)
        }

        assertEquals(listOf(ENROLLED, MERGED), cache.loadCourses(TERM).map { it.courseNo })
        assertTrue(overrides.hasPending())
    }

    private companion object {
        const val TERM = "1151"
        const val ENROLLED = "XA0000001"
        const val ADDED = "XB0000002"
        const val MERGED = "XC0000003"
        const val OTHER_DEVICE = "XD0000004"
        const val NO_STATUS = "XE0000005"
        const val FETCHED = "XF0000006"
        const val ELSEWHERE = "XG0000007"
    }
}
