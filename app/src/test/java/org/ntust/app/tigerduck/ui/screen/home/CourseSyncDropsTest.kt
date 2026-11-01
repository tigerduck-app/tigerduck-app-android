package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.SelectionAnswer
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
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableTermFetch
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableTermSource
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

    private fun row(no: String, source: String? = "ntust_portal", status: String? = null, updatedAt: String? = null) =
        ServerCourse(no, no, TERM, moodleId = "$TERM$no", source = source, enrollmentStatus = status, updatedAt = updatedAt)

    private fun reconcile(
        local: List<Course>,
        rows: List<ServerCourse>,
        selectionDropped: Set<String> = emptySet(),
        tombstones: Set<String> = emptySet(),
        serverKnown: Set<String> = local.mapTo(mutableSetOf()) { it.courseNo },
        answer: SelectionAnswer? = null,
        fetchedAtMs: Long = 0L,
    ) = CourseSyncReconciler.reconcileSemester(
        semester = TERM,
        localCourses = local,
        serverRows = rows,
        tombstoneNos = emptySet(),
        tombstones = tombstones,
        serverKnownNos = serverKnown,
        selectionDroppedNos = selectionDropped,
        answer = answer,
        fetchedAtMs = fetchedAtMs,
    )

    /** [ms] as the backend writes a row's `updated_at`. */
    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC).toString()

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
     * [FETCHED] leaves the courses fetched here too, as on the iPhone (tigerduck-app#914), with no
     * answer here to say otherwise. Nothing hides it, which would keep it out even once it is
     * enrolled again.
     */
    @Test
    fun `a course a roster dropped is not hidden, and its row does not bring back a course hidden here`() {
        val out = reconcile(
            local = listOf(fetched(ENROLLED), fetched(FETCHED), addedHere(ADDED), mergedCopy(MERGED)),
            rows = rowsAfterARoster,
            tombstones = setOf("$TERM:$ELSEWHERE"),
        )

        assertEquals(setOf("$TERM:$ELSEWHERE"), out.tombstones)
        assertEquals(setOf(MERGED, FETCHED), out.left)
        assertTrue(out.merged.none { it.courseNo == FETCHED || it.courseNo == ELSEWHERE })
    }

    /**
     * A course this phone's course selection listed after another device's roster dropped it is
     * enrolled again by this phone's roster, so the drop does not take it. An answer older than
     * the drop, or within the clock margin of it, says nothing about it, and the course leaves:
     * an old answer must not hold off a newer drop for good. As the iPhone's
     * `serverDropLeavesFetchedCourses` (tigerduck-app#914).
     */
    @Test
    fun `a course a roster dropped leaves the courses fetched here, unless course selection listed it since the drop`() {
        val droppedAt = 1_800_000_000_000L
        val hour = 3_600_000L
        fun read(answeredAt: Long?) = reconcile(
            local = listOf(fetched(ENROLLED), fetched(FETCHED), mergedCopy(MERGED)),
            rows = listOf(
                row(ENROLLED, status = "enrolled"),
                row(FETCHED, status = "dropped", updatedAt = iso(droppedAt)),
                row(MERGED, status = "dropped", updatedAt = iso(droppedAt)),
            ),
            answer = answeredAt?.let { SelectionAnswer(setOf(ENROLLED, FETCHED, MERGED), it) },
            fetchedAtMs = droppedAt + 2 * hour,
        )

        for (answeredAt in listOf(null, droppedAt - hour, droppedAt + 60_000L)) {
            val out = read(answeredAt)
            assertEquals("answered at $answeredAt", setOf(FETCHED, MERGED), out.left)
            assertEquals(emptySet<String>(), out.tombstones)
        }
        val listedSince = read(droppedAt + hour)
        assertEquals(emptySet<String>(), listedSince.left)
        assertTrue(listedSince.merged.isEmpty())
        assertEquals(emptySet<String>(), listedSince.tombstones)
    }

    /**
     * The backend sends each row's `updated_at`, which its drop sets. Without one the drop is no
     * later than the full sync that read it, so only an answer since that sync exempts the course.
     */
    @Test
    fun `a drop with no time of its own counts from the full sync that read it`() {
        val fetchedAt = 1_800_000_000_000L
        val hour = 3_600_000L
        fun left(answeredAt: Long, fetchedAtMs: Long = fetchedAt) = reconcile(
            local = listOf(fetched(ENROLLED), fetched(FETCHED)),
            rows = listOf(row(ENROLLED), row(FETCHED, status = "dropped")),
            answer = SelectionAnswer(setOf(ENROLLED, FETCHED), answeredAt),
            fetchedAtMs = fetchedAtMs,
        ).left

        assertEquals(setOf(FETCHED), left(answeredAt = fetchedAt - hour))
        assertEquals(emptySet<String>(), left(answeredAt = fetchedAt + hour))
        // A snapshot with no time either: no answer is known to be newer.
        assertEquals(setOf(FETCHED), left(answeredAt = fetchedAt + hour, fetchedAtMs = 0L))
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
            droppedOnServer = mapOf(OTHER_DEVICE to null, ELSEWHERE to null),
        )

        // MERGED was listed again since the reconcile.
        assertEquals(listOf(ENROLLED, MERGED), committed.map { it.courseNo })
    }

    /**
     * The commit reads the term's latest course selection answer again, as a fetch may have
     * answered since the reconcile. It kept any course that answer listed, so an answer from
     * before another device's roster dropped the course held the drop off for good.
     */
    @Test
    fun `at the commit, only a course selection answer newer than a drop keeps the course`() {
        val droppedAt = 1_800_000_000_000L
        val hour = 3_600_000L
        val read = listOf(fetched(ENROLLED), fetched(FETCHED), mergedCopy(MERGED))
        fun committed(answeredAt: Long) = CourseSyncReconciler.committedCourses(
            read, read, kept = read, merged = emptyList(), hidden = emptySet(),
            left = setOf(FETCHED, MERGED),
            droppedOnServer = mapOf(FETCHED to droppedAt, MERGED to droppedAt),
            answer = SelectionAnswer(setOf(ENROLLED, FETCHED, MERGED), answeredAt),
        ).map { it.courseNo }

        assertEquals(listOf(ENROLLED), committed(answeredAt = droppedAt - hour))
        assertEquals(listOf(ENROLLED, FETCHED, MERGED), committed(answeredAt = droppedAt + hour))
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
            { _, _, _, _, _, _, _ -> false },
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
        val fetchedAtMs = System.currentTimeMillis()
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
                answer = cache.selectionAnswerIn(TERM),
                fetchedAtMs = fetchedAtMs,
            )
            cache.recordServerDrops(TERM, outcome.serverDrops)
            beforeCommit()
            cache.saveServerKnownNos(mapOf(TERM to outcome.serverKnownNos.sorted()))
            val kept = CourseSyncReconciler.pruneHidden(TERM, localCourses, outcome.tombstones)
            commitReconciledTerm(
                cache, courseSync, TERM, stored, outcome.tombstones, emptySet(), localCourses, kept, outcome.left,
                outcome.droppedOnServer, outcome.merged, outcome.handAddedNos, overrides,
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

    /**
     * The snapshot's drop is another device's roster, older than course selection's answer here
     * that lists the merged copy, which the fetch keeps manual with its Moodle ID: the older
     * commit took it out all the same. Once an answer leaves it out, the drop takes it.
     */
    @Test
    fun `a merged copy course selection lists stays though the snapshot has it dropped, until an answer leaves it out`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED), mergedCopy(MERGED)), TERM)
        val anHourAgo = iso(System.currentTimeMillis() - 3_600_000L)

        syncListing(row(ENROLLED), row(MERGED, status = "dropped", updatedAt = anHourAgo)) {
            cache.recordSelectionRoster(TERM, listOf(ENROLLED, MERGED))
            cache.saveCourses(listOf(fetched(ENROLLED), mergedCopy(MERGED)), TERM)
        }
        assertEquals(listOf(ENROLLED, MERGED), cache.loadCourses(TERM).map { it.courseNo })

        cache.recordSelectionRoster(TERM, listOf(ENROLLED))
        syncListing(row(ENROLLED), row(MERGED, status = "dropped", updatedAt = anHourAgo))
        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
    }

    /**
     * Round 7's finding: the term's latest course selection answer kept every course it listed,
     * however old, so an answer from before another device's roster dropped a course kept it on
     * this phone, and its reminders, for good. Only an answer since the drop keeps it now, and
     * the next course selection answer that lists it brings it back.
     */
    @Test
    fun `a course selection answer older than a roster's drop does not keep the course, and a newer one does`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED), fetched(FETCHED), mergedCopy(MERGED)), TERM)
        cache.recordSelectionRoster(TERM, listOf(ENROLLED, FETCHED, MERGED))
        val inAnHour = iso(System.currentTimeMillis() + 3_600_000L)

        syncListing(
            row(ENROLLED),
            row(FETCHED, status = "dropped", updatedAt = inAnHour),
            row(MERGED, status = "dropped", updatedAt = inAnHour),
        )

        assertEquals(listOf(ENROLLED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
        assertEquals(setOf(FETCHED, MERGED), cache.serverDroppedIn(TERM))

        cache.saveCourses(listOf(fetched(ENROLLED), fetched(FETCHED)), TERM)
        syncListing(row(ENROLLED), row(FETCHED, status = "dropped", updatedAt = iso(System.currentTimeMillis() - 3_600_000L)))
        assertEquals(listOf(ENROLLED, FETCHED), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(emptySet<String>(), cache.serverDroppedIn(TERM))
    }

    /** As above for a past term's rebuild, which keeps no answer but takes the course's drop off. */
    @Test
    fun `a merged copy a rebuild lists again while the sync ran stays though the snapshot has it dropped`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED), mergedCopy(MERGED)), TERM)
        cache.recordFinalRoster(TERM, listOf(ENROLLED), alsoListed = listOf(MERGED))

        syncListing(row(ENROLLED), row(MERGED, status = "dropped")) {
            cache.recordFinalRoster(TERM, listOf(ENROLLED, MERGED), alsoListed = emptyList())
            cache.saveCourses(listOf(fetched(ENROLLED), mergedCopy(MERGED)), TERM)
        }

        assertEquals(listOf(ENROLLED, MERGED), cache.loadCourses(TERM).map { it.courseNo })
    }

    /**
     * [TERM] rebuilt from a transcript that lists only [ENROLLED], as the class table rebuilds an
     * ended term: its classes looked up, its drops recorded, then the term folded and saved.
     * [moodleNos] are the term's Moodle sites, which the rebuild drops.
     */
    private suspend fun rebuildTerm(moodleNos: List<String>) {
        val fetch = ClassTableTermFetch { _, courseNo, _, _, _ ->
            LookedUpCourse(courseNo, Course.fromSchedule(courseNo, courseNo, schedule = mapOf(2 to listOf("3", "4"))), answered = true)
        }
        val report = ScoreReport(
            student = "",
            currentTerm = "",
            rankings = emptyList(),
            courses = listOf(
                CourseGrade(term = TERM, code = ENROLLED, name = ENROLLED, status = GradeStatus.GRADED),
                CourseGrade(term = LATER_TERM, code = NO_STATUS, name = NO_STATUS, status = GradeStatus.PENDING),
            ),
            creditSummary = CreditSummary.EMPTY,
        )
        val source = ClassTableTermSource.of(TERM, selection = null, report, servedBySelection = false, cached = cache.loadCourses(TERM))
        assertTrue(source is ClassTableTermSource.Rebuild)
        val answers = fetch.lookUp(TERM, source.rosterNos, emptyMap(), before = cache.loadCourses(TERM))
        val rebuilt = fetch.committed(
            answers, source, TERM, moodleNos,
            latest = { cache.loadCourses(TERM) },
            waitingAdds = { emptySet() },
            droppedNos = { emptySet() },
            recordFinalRoster = { cache.recordFinalRoster(TERM, it.roster, alsoListed = it.dropping) },
        ) { drawn ->
            fetch.fold(drawn, cache.loadCourses(TERM), emptyMap(), "zh", CourseTombstoneKeys.hiddenIn(TERM, cache.loadDeletedCourseNos()))
        }
        cache.saveCourses(rebuilt!!, TERM)
    }

    /**
     * A sync reads the drop list before its reconcile and commits seconds later. A rebuild that
     * recorded its drops and saved the term in between had taken a Moodle site this phone never
     * cached out of it, and the sync's commit put the site back as a merged copy, its row
     * prepared before the drop. The commit checks its merges against the drop list as it stands.
     */
    @Test
    fun `a row a rebuild dropped while the sync ran is not merged, but one added by hand is`() = runTest {
        cache.saveCourses(listOf(fetched(ENROLLED)), TERM)

        syncListing(row(ENROLLED), row(JUNK), row(OTHER_DEVICE, source = "user_added"), row(ADDED)) {
            rebuildTerm(moodleNos = listOf(JUNK, OTHER_DEVICE, ADDED))
            assertTrue(cache.loadSelectionDroppedNos()[TERM].orEmpty().containsAll(listOf(JUNK, OTHER_DEVICE, ADDED)))
            // The rebuild took ADDED off, and the student added it back by hand.
            cache.saveCourses(cache.loadCourses(TERM) + addedHere(ADDED), TERM)
        }

        val term = cache.loadCourses(TERM)
        // Added by hand on another device, so no drop takes it.
        assertEquals(listOf(ENROLLED, ADDED, OTHER_DEVICE), term.map { it.courseNo })
        // The course added here, not the sync's merged copy of it.
        assertNull(term.single { it.courseNo == ADDED }.moodleIdNumber)
        assertEquals(emptySet<String>(), cache.loadDeletedCourseNos())
    }

    private companion object {
        const val TERM = "1151"
        const val LATER_TERM = "1152"
        const val JUNK = "XH0000008"
        const val ENROLLED = "XA0000001"
        const val ADDED = "XB0000002"
        const val MERGED = "XC0000003"
        const val OTHER_DEVICE = "XD0000004"
        const val NO_STATUS = "XE0000005"
        const val FETCHED = "XF0000006"
        const val ELSEWHERE = "XG0000007"
    }
}
