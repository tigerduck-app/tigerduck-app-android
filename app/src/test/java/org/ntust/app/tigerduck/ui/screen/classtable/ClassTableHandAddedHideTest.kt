package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.commitReconciledTerm
import java.io.File
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A hand-added course the student deletes and later really enrolls in (tigerduck-app-android#425,
 * as iOS tigerduck-app#771). The backend lifts its tombstone once this device's roster upload
 * names the course, so the course selection answer that lists it has to bring it back here first;
 * every other hide keeps binding.
 */
class ClassTableHandAddedHideTest {

    private val context = JvmContext()

    private lateinit var cache: DataCache

    /** Only its fold is used: the lookups' answers are made here. */
    private val termFetch = ClassTableTermFetch { _, courseNo, _, _, _ -> LookedUpCourse(courseNo, null, answered = false) }

    private val enrolled = Course.fromSchedule("CS1010001", "Algorithms", moodleIdNumber = "${TERM}CS1010001")
    private val handAdded = Course.fromSchedule("GE1010009", "Art", schedule = mapOf(3 to listOf("2")), isManual = true)
    private val portal = Course.fromSchedule("ME1010005", "Statics", moodleIdNumber = "${TERM}ME1010005")

    /** The colours and names waiting to go up, which nothing sends here. */
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

    /** As the class table's delete leaves the store and the term, reading the course off the term. */
    private suspend fun delete(course: Course) {
        val held = cache.loadCourses(TERM).first { it.courseNo == course.courseNo }
        cache.hideDeletedCourse(held.courseNo, TERM, held)
        cache.saveCourses(cache.loadCourses(TERM).filter { it.courseNo != held.courseNo }, TERM)
    }

    /**
     * [block], a sync's reconcile, within [sync] when one began earlier, or else a course sync of
     * its own, begun before it reads anything as HomeBackendSync begins one before its fetch.
     */
    private suspend fun reconciling(sync: DataCache.CourseSync?, block: suspend (DataCache.CourseSync) -> Unit) {
        if (sync != null) return block(sync)
        val own = cache.beginCourseSync()
        own.fetching()
        try {
            block(own)
        } finally {
            cache.endCourseSync(own)
        }
    }

    /**
     * The sync's reconcile as HomeBackendSync stores it, for a tombstone another device wrote for
     * [course], held after it reads the term's courses for [afterCoursesRead] and before its commit
     * for [beforeCommit].
     */
    private suspend fun tombstonedElsewhere(
        course: Course,
        afterCoursesRead: suspend () -> Unit = {},
        beforeCommit: suspend () -> Unit = {},
        resetElsewhere: Boolean = false,
    ) = reconciling(null) { sync ->
        // As both syncs do once the snapshot reads a course reset made on another device.
        if (resetElsewhere) sync.supersedeAddsBack()
        val localCourses = cache.loadCourses(TERM)
        afterCoursesRead()
        val restored = cache.restoredCoursesIn(TERM, Long.MAX_VALUE)
        val stored = CourseSyncReconciler.withoutRestored(TERM, cache.loadDeletedCourseNos(), restored)
        val outcome = CourseSyncReconciler.reconcileSemester(
            semester = TERM,
            localCourses = localCourses,
            serverRows = listOf(ServerCourse(enrolled.courseNo, enrolled.courseName, TERM, moodleId = enrolled.moodleIdNumber)),
            tombstoneNos = setOf(course.courseNo),
            tombstones = stored,
            serverKnownNos = setOf(enrolled.courseNo, course.courseNo),
            restoredNos = restored,
        )
        beforeCommit()
        val kept = CourseSyncReconciler.pruneHidden(TERM, localCourses, outcome.tombstones)
        commitReconciledTerm(cache, sync, TERM, stored, outcome.tombstones, setOf(course.courseNo), localCourses, kept, outcome.left, outcome.droppedOnServer, outcome.merged, overrides)
    }

    /**
     * A full sync whose snapshot, fetched at [fetchedAtMs] (by default after everything so far),
     * lists [serverNos] for the term, with no tombstone, as HomeBackendSync makes it: the hides read
     * first ([hides], when the sync read them earlier), then the term's courses, reconciled, pruned
     * and committed. Within [sync], when the sync began earlier.
     */
    private suspend fun syncListing(
        vararg serverNos: String,
        fetchedAtMs: Long = Long.MAX_VALUE,
        hides: Set<String>? = null,
        sync: DataCache.CourseSync? = null,
        beforeCommit: suspend () -> Unit = {},
    ) = reconciling(sync) { courseSync ->
        val localCourses = cache.loadCourses(TERM)
        val restored = cache.restoredCoursesIn(TERM, fetchedAtMs)
        val stored = CourseSyncReconciler.withoutRestored(TERM, hides ?: cache.loadDeletedCourseNos(), restored)
        val outcome = CourseSyncReconciler.reconcileSemester(
            semester = TERM,
            localCourses = localCourses,
            serverRows = serverNos.map { ServerCourse(it, it, TERM, moodleId = "$TERM$it") },
            tombstoneNos = emptySet(),
            tombstones = stored,
            serverKnownNos = cache.loadServerKnownNos()[TERM].orEmpty().toSet(),
            restoredNos = restored,
        )
        beforeCommit()
        cache.restoredCoursesListed(TERM, serverNos.toList(), fetchedAtMs)
        cache.saveServerKnownNos(mapOf(TERM to outcome.serverKnownNos.sorted()))
        val kept = CourseSyncReconciler.pruneHidden(TERM, localCourses, outcome.tombstones)
        commitReconciledTerm(cache, courseSync, TERM, stored, outcome.tombstones, emptySet(), localCourses, kept, outcome.left, outcome.droppedOnServer, outcome.merged, overrides)
    }

    /**
     * A fetch of the term whose course selection answer lists [courseNos], as the class table makes
     * it: the answer recorded before the merge, then the roster's rows folded onto the term. What it
     * returns is what the class table shows, saves and uploads.
     */
    private suspend fun fetchListing(vararg courseNos: String): List<String> {
        cache.recordSelectionRoster(TERM, courseNos.toList())
        val fetched = courseNos.map { Course.fromSchedule(it, it, moodleIdNumber = "$TERM$it") }
        val merged = termFetch.foldLatest(
            ClassTableTermFetch.Answers(fetched, emptyMap(), answered = true), TERM,
            editGeneration = { 0 },
            cachedCourses = { cache.loadCourses(TERM) },
            storedTombstones = { cache.loadDeletedCourseNos() },
            shownCourses = { null },
            withPendingEdits = { it },
            names = { emptyMap() },
            locale = "zh",
        )!!
        cache.saveCourses(merged, TERM)
        return merged.map { it.courseNo }.sorted()
    }

    /**
     * As the class table's add of [course] back by hand: the term's hide lifted, then the longer
     * term saved and the upload sent.
     */
    private suspend fun addBack(course: Course) {
        cache.addCourseBack(course.courseNo, TERM)
        cache.saveCourses(cache.loadCourses(TERM).filter { it.courseNo != course.courseNo } + course, TERM)
        cache.courseAddSent(course.courseNo, TERM)
    }

    /**
     * Home's sync pinning the legacy bare hides to the terms whose cached courses carry them,
     * held for [whileRead] after it reads them. Returns the hides it goes on to reconcile.
     */
    private suspend fun pinLegacy(whileRead: suspend () -> Unit): Set<String> {
        val stored = cache.loadDeletedCourseNos()
        val rosters = mapOf(TERM to cache.loadCourses(TERM).mapTo(mutableSetOf()) { it.courseNo })
        whileRead()
        return cache.pinLegacyCourseHides(stored, rosters)
    }

    /** The colours waiting to go up, by Moodle id. */
    private suspend fun waitingColors(): Map<String?, String?> =
        cache.loadPendingCourseOverrides().filter { it.colorHex != null }.associate { it.moodleId to it.colorHex }

    /** What Home and the background refresh leave out of the term. */
    private suspend fun hidden(): Set<String> = CourseTombstoneKeys.hiddenIn(TERM, cache.loadDeletedCourseNos())

    private suspend fun courseNos(): List<String> = cache.loadCourses(TERM).map { it.courseNo }.sorted()

    /** Whether `restored_courses.json` still holds [course]. */
    private fun holds(course: Course): Boolean =
        File(context.filesDir, "TigerDuckData/restored_courses.json")
            .takeIf { it.isFile }?.readText()?.contains("\"$TERM:${course.courseNo}\"") == true

    /** The clock once it has moved past now, so a time taken from it is later than everything before. */
    private fun nextMillis(): Long {
        val now = System.currentTimeMillis()
        while (System.currentTimeMillis() <= now) Thread.sleep(1)
        return System.currentTimeMillis()
    }

    @Test
    fun `a deleted hand-added course shows again and goes up with the roster once course selection lists it`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)

        delete(handAdded)
        assertEquals(setOf(handAdded.courseNo), hidden())

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `a deleted enrolled course stays hidden when course selection lists it again`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)

        delete(enrolled)

        assertEquals(listOf(handAdded.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
        assertEquals(setOf(enrolled.courseNo), hidden())
    }

    @Test
    fun `a hide from another device's tombstone stays when course selection lists the course`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        tombstonedElsewhere(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
        assertEquals(setOf(handAdded.courseNo), hidden())
    }

    @Test
    fun `a hand-added course that course selection already listed when it was deleted stays hidden`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        // Enrolled in since it was added by hand: the row keeps its manual flag.
        fetchListing(enrolled.courseNo, handAdded.courseNo)

        delete(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
        assertEquals(setOf(handAdded.courseNo), hidden())
    }

    @Test
    fun `a hide lifted by adding the course back is not lifted again once another device's tombstone hides it`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        addBack(handAdded)

        tombstonedElsewhere(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
        assertEquals(setOf(handAdded.courseNo), hidden())
    }

    @Test
    fun `a deleted hand-added course comes back only once its delete has reached the backend`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        // Its DELETE has not landed, as PendingCourseDeletes keeps it: landing after the roster
        // upload, it would take the course off the server again.
        cache.savePendingCourseDeletes(mapOf(TERM to mapOf(handAdded.courseNo to 1L)))

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))

        cache.savePendingCourseDeletes(emptyMap())
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a sign-out takes the marked hides with it`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)
        delete(handAdded)

        cache.clearAllUserData()
        // The next student's own sync hides the same course for another of their devices.
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        tombstonedElsewhere(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a sign-out takes the course selection answers with it`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)

        cache.clearAllUserData()
        // The next student added the same course by hand and deletes it before any answer of theirs:
        // the last one's answer says nothing of what theirs lists.
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        delete(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a hand-added course deleted before any course selection answer stays hidden`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)

        delete(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a course merged from another device's row stays hidden once deleted`() = runTest {
        cache.saveCourses(listOf(enrolled), TERM)
        // Merged down as CourseSyncReconciler.toCourse makes it: manual, with the Moodle id the
        // server holds. The server may not have it as a hand-added course's row.
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)

        delete(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a mark that lost its hide does not lift a later hide of the course`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        // The hides gone from under the mark, as a damaged file leaves them.
        File(context.filesDir, "TigerDuckData/deleted_courses.json").delete()

        tombstonedElsewhere(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a sync that read the hides before a course came back does not hide it again`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        val read = cache.loadDeletedCourseNos()

        // While the sync reconciles what it read, a fetch brings the course back.
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
        // The sync commits what it made of its read: another course hidden for a tombstone.
        cache.updateDeletedCourseNos(read, CourseTombstoneKeys.hide("ZZ1010008", TERM, read))

        assertEquals(setOf("ZZ1010008"), hidden())
    }

    @Test
    fun `a sync that read the hides before a course came back keeps it in the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)

        // Home's sync reads the hides, and is held there while a fetch brings the course back,
        // before it reads the term's courses, reconciles and commits.
        val read = cache.loadDeletedCourseNos()
        fetchListing(enrolled.courseNo, handAdded.courseNo)
        syncListing(enrolled.courseNo, hides = read)

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), cache.loadCourses(TERM).map { it.courseNo }.sorted())
        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `a sync that read a course as brought back does not undo its delete made since`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        val read = cache.loadDeletedCourseNos()
        fetchListing(enrolled.courseNo, handAdded.courseNo)

        // The sync has read the course as brought back and is held before its commit, while the
        // student deletes it again.
        syncListing(enrolled.courseNo, hides = read, beforeCommit = { delete(handAdded) })

        assertEquals(setOf(handAdded.courseNo), hidden())
    }

    @Test
    fun `a delete made while a sync merges another course down stays out of the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        val read = cache.loadDeletedCourseNos()
        fetchListing(enrolled.courseNo, handAdded.courseNo)

        // The sync merges down a course another device added, which writes the term's courses,
        // and is held before its commit while the student deletes the course again.
        syncListing(enrolled.courseNo, MERGED, hides = read, beforeCommit = { delete(handAdded) })

        assertEquals(setOf(handAdded.courseNo), hidden())
        assertEquals(listOf(enrolled.courseNo, MERGED), cache.loadCourses(TERM).map { it.courseNo }.sorted())
    }

    @Test
    fun `a course brought back while a sync that read its hide waits stays in the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        // The delete has written its hide, but not yet the shorter term, when Home's sync reads both.
        cache.hideDeletedCourse(handAdded.courseNo, TERM, handAdded)

        syncListing(enrolled.courseNo, beforeCommit = {
            // The delete saves the shorter term, and a fetch whose course selection answer lists
            // the course brings it back.
            cache.saveCourses(cache.loadCourses(TERM).filter { it.courseNo != handAdded.courseNo }, TERM)
            fetchListing(enrolled.courseNo, handAdded.courseNo)
        })

        assertEquals(emptySet<String>(), hidden())
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo).sorted(), cache.loadCourses(TERM).map { it.courseNo }.sorted())
    }

    @Test
    fun `a hand-added course deleted while a sync finds another device's tombstone for it stays hidden`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)

        // The sync has found the tombstone and not yet committed when the student deletes the course.
        tombstonedElsewhere(handAdded, beforeCommit = { delete(handAdded) })

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a course brought back while a sync that found another device's tombstone for it waits is hidden again`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)

        // The sync has read the marked hide and found the tombstone when a course selection
        // answer that lists the course brings it back.
        tombstonedElsewhere(handAdded, beforeCommit = { fetchListing(enrolled.courseNo, handAdded.courseNo) })

        assertEquals(setOf(handAdded.courseNo), hidden())
        assertEquals(listOf(enrolled.courseNo), cache.loadCourses(TERM).map { it.courseNo })
        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a course brought back after a sync read the term goes again with the hide another device's tombstone writes`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)

        // A course selection answer brings the course back after the sync has read the term's
        // courses and before it reads which courses are being brought back.
        tombstonedElsewhere(handAdded, afterCoursesRead = { fetchListing(enrolled.courseNo, handAdded.courseNo) })

        assertEquals(setOf(handAdded.courseNo), hidden())
        assertEquals(listOf(enrolled.courseNo), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `a delete of a course a legacy hide already hides is kept for its term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        // A bare entry from before hides were kept per term, hiding the course in every term.
        cache.saveDeletedCourseNos(setOf(enrolled.courseNo))
        delete(enrolled)

        // A sync of another term that lists the course lifts the bare entry with that term's hide.
        cache.updateDeletedCourseNos(
            setOf(enrolled.courseNo),
            CourseTombstoneKeys.unhide(enrolled.courseNo, "1131", setOf(enrolled.courseNo)),
        )

        assertEquals(setOf(enrolled.courseNo), hidden())
    }

    @Test
    fun `a course deleted on a screen still showing it after another device's tombstone hid it stays hidden`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        tombstonedElsewhere(handAdded)

        // The class table has yet to reload the term the sync pruned, and the student deletes the row.
        cache.hideDeletedCourse(handAdded.courseNo, TERM, handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a course hidden here that another device adds back returns with one sync`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        delete(enrolled)
        syncListing(handAdded.courseNo)

        // Another device adds the course back, and the next snapshot lists it.
        syncListing(enrolled.courseNo, handAdded.courseNo)

        assertEquals(emptySet<String>(), hidden())
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo).sorted(), cache.loadCourses(TERM).map { it.courseNo }.sorted())
    }

    @Test
    fun `a newer sync that finds a brought-back course listed leaves an older sync its hold`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        // Home's sync fetches its snapshot and reads the hides, then waits while the rest happens.
        val olderFetchedAt = System.currentTimeMillis() - 1_000
        val read = cache.loadDeletedCourseNos()
        fetchListing(enrolled.courseNo, handAdded.courseNo)
        // The background sync's snapshot, fetched since, lists the course: its upload landed.
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = System.currentTimeMillis() + 1_000)

        // Home's older snapshot, from before the upload, does not list it.
        syncListing(enrolled.courseNo, hides = read, fetchedAtMs = olderFetchedAt)

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), cache.loadCourses(TERM).map { it.courseNo }.sorted())
        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `an older sync that resumes long after a newer one listed a brought-back course still leaves it be`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        val olderFetchedAt = System.currentTimeMillis() - 1_000
        val read = cache.loadDeletedCourseNos()
        fetchListing(enrolled.courseNo, handAdded.courseNo)
        val now = System.currentTimeMillis()
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = now + 1_000)
        // Newer syncs go on while the older one is held, past any grace a minute long.
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = now + 600_000)

        syncListing(enrolled.courseNo, hides = read, fetchedAtMs = olderFetchedAt)

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), cache.loadCourses(TERM).map { it.courseNo }.sorted())
        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `a course a sync merges down that is fetched and deleted while it runs stays out of the term`() = runTest {
        cache.saveCourses(listOf(enrolled), TERM)

        // The sync is to merge down a course another device added; held before its commit, a
        // fetch brings the same course and the student deletes it.
        syncListing(enrolled.courseNo, MERGED, beforeCommit = {
            cache.saveCourses(cache.loadCourses(TERM) + Course.fromSchedule(MERGED, MERGED, moodleIdNumber = "$TERM$MERGED"), TERM)
            delete(Course.fromSchedule(MERGED, MERGED))
        })

        assertEquals(setOf(MERGED), hidden())
        assertEquals(listOf(enrolled.courseNo), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `a snapshot fetched before a course came back does not end its hold`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        fetchListing(enrolled.courseNo, handAdded.courseNo)

        // Fetched before its DELETE landed, so it still lists the old row, and reconciled only now.
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = 0L)
        syncListing(enrolled.courseNo)

        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `another device's tombstone makes a marked hide bind`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        fetchListing(enrolled.courseNo)
        delete(handAdded)

        // The backend leaves this device's own tombstone out of its snapshot: one there is another's.
        tombstonedElsewhere(handAdded)

        assertEquals(listOf(enrolled.courseNo), fetchListing(enrolled.courseNo, handAdded.courseNo))
    }

    @Test
    fun `a course brought back stays through a sync that does not list it before its upload lands`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        // The server has had both: the hand-added course went up when it was added.
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        // Its DELETE landed; the backend leaves the tombstone out of this device's snapshot.
        syncListing(enrolled.courseNo)
        fetchListing(enrolled.courseNo, handAdded.courseNo)

        // Home's fetch uploads nothing, and the class table's upload can fail: the next snapshot
        // does not list it yet, which is no delete elsewhere.
        syncListing(enrolled.courseNo)
        assertEquals(emptySet<String>(), hidden())
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), cache.loadCourses(TERM).map { it.courseNo }.sorted())

        // Once a snapshot lists it, the upload has landed, and its absence is a delete elsewhere again.
        syncListing(enrolled.courseNo, handAdded.courseNo)
        syncListing(enrolled.courseNo)
        assertEquals(setOf(handAdded.courseNo), hidden())
    }

    @Test
    fun `a course deleted and added back while a sync that prunes it for another device's delete waits stays in the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)

        // Another device deleted the course, and the sync has decided to prune it, when the student
        // deletes it here and adds it back by hand, whose upload forces its key past that tombstone.
        tombstonedElsewhere(handAdded, beforeCommit = {
            delete(handAdded)
            addBack(handAdded)
        })

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `a course brought back while a sync that prunes it for the server's silence waits stays in the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)

        // The snapshot no longer lists the course, and the sync has decided to prune it, when the
        // student deletes it here and a course selection answer that lists it brings it back: until
        // a snapshot fetched since lists it, its absence is no delete elsewhere.
        syncListing(enrolled.courseNo, beforeCommit = {
            delete(handAdded)
            fetchListing(enrolled.courseNo, handAdded.courseNo)
        })

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `a hold a snapshot has listed goes once no sync that could still need it runs`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        fetchListing(enrolled.courseNo, handAdded.courseNo)

        // A snapshot fetched since lists the course, and no sync that began before it is left.
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())

        assertEquals(false, holds(handAdded))
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
    }

    @Test
    fun `a hold a snapshot has listed stays until a sync that began before it ends`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        // Home's sync begins, fetches its snapshot and reads the hides, then waits while the rest happens.
        val older = cache.beginCourseSync()
        val olderFetchedAt = System.currentTimeMillis()
        val read = cache.loadDeletedCourseNos()
        fetchListing(enrolled.courseNo, handAdded.courseNo)
        // Background syncs fetched since list the course: its upload landed.
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())
        assertEquals(true, holds(handAdded))

        // Home's older snapshot, from before the upload, does not list it.
        syncListing(enrolled.courseNo, hides = read, fetchedAtMs = olderFetchedAt, sync = older)
        cache.endCourseSync(older)
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
        assertEquals(emptySet<String>(), hidden())

        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())
        assertEquals(false, holds(handAdded))
    }

    @Test
    fun `a restart lets go of the holds a sync of the last process might have needed`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        fetchListing(enrolled.courseNo)
        delete(handAdded)
        syncListing(enrolled.courseNo)
        // A sync that the process ends under, before it ends itself.
        cache.beginCourseSync()
        fetchListing(enrolled.courseNo, handAdded.courseNo)
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())
        assertEquals(true, holds(handAdded))

        // The next process, whose first sync's snapshot lists the course too.
        setUp()
        syncListing(enrolled.courseNo, handAdded.courseNo, fetchedAtMs = nextMillis())

        assertEquals(false, holds(handAdded))
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
    }

    @Test
    fun `a course whose add back is under way when a sync begins stays in the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        delete(handAdded)

        // The add has lifted the hide and saved the term, and has yet to keep its upload, when a
        // sync begins whose snapshot carries another device's tombstone for the course.
        cache.addCourseBack(handAdded.courseNo, TERM)
        cache.saveCourses(cache.loadCourses(TERM) + handAdded, TERM)
        tombstonedElsewhere(handAdded)
        cache.courseAddSent(handAdded.courseNo, TERM)

        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
        assertEquals(emptySet<String>(), hidden())

        // Once its upload is sent, a sync that begins reads the server's answer to it.
        tombstonedElsewhere(handAdded)
        assertEquals(listOf(enrolled.courseNo), courseNos())
    }

    @Test
    fun `a course added back while a sync pins a legacy hide of it stays shown`() = runTest {
        // From before hides were kept per term: a bare entry, for a course the term's cache still holds.
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        cache.saveDeletedCourseNos(setOf(handAdded.courseNo))

        pinLegacy(whileRead = { addBack(handAdded) })

        assertEquals(emptySet<String>(), hidden())
    }

    @Test
    fun `a course added back while a sync that prunes it waits keeps its waiting colour and name`() = runTest {
        cache.saveCourses(listOf(enrolled, portal), TERM)
        syncListing(enrolled.courseNo, portal.courseNo)

        tombstonedElsewhere(portal, beforeCommit = {
            delete(portal)
            overrides.forget(listOfNotNull(portal.moodleIdNumber))
            addBack(portal)
            overrides.send(portal.moodleIdNumber!!, colorHex = "#FF9500")
        })

        assertEquals(mapOf<String?, String?>(portal.moodleIdNumber to "#FF9500"), waitingColors())
        assertEquals(listOf(enrolled.courseNo, portal.courseNo), courseNos())
    }

    @Test
    fun `a course a sync prunes takes its waiting colour and name with it`() = runTest {
        cache.saveCourses(listOf(enrolled, portal), TERM)
        syncListing(enrolled.courseNo, portal.courseNo)
        overrides.send(portal.moodleIdNumber!!, colorHex = "#FF9500")
        overrides.send(enrolled.moodleIdNumber!!, colorHex = "#34C759")

        tombstonedElsewhere(portal)

        assertEquals(mapOf<String?, String?>(enrolled.moodleIdNumber to "#34C759"), waitingColors())
        assertEquals(listOf(enrolled.courseNo), courseNos())
    }

    @Test
    fun `a course deleted while a sync pins legacy hides stays hidden from that sync's older snapshot`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        // A bare hide from before hides were kept per term, of a course no roster here carries.
        cache.saveDeletedCourseNos(setOf(LEGACY))

        // While Home's sync reads the rosters, the student deletes a course, and its DELETE lands.
        val read = pinLegacy(whileRead = { delete(handAdded) })
        // The sync's snapshot, fetched before, still lists the course.
        syncListing(enrolled.courseNo, handAdded.courseNo, hides = read)

        assertEquals(setOf(LEGACY, handAdded.courseNo), hidden())
        assertEquals(listOf(enrolled.courseNo), courseNos())
    }

    @Test
    fun `a course added back once a sync has applied a reset elsewhere stays in the term`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)

        // The sync has read the reset and decided to prune the course when the student deletes it
        // and adds it back, newer than the reset.
        tombstonedElsewhere(handAdded, resetElsewhere = true, beforeCommit = {
            delete(handAdded)
            addBack(handAdded)
        })

        assertEquals(emptySet<String>(), hidden())
        assertEquals(listOf(enrolled.courseNo, handAdded.courseNo), courseNos())
    }

    @Test
    fun `a sync that pins a legacy hide another sync pinned meanwhile still reads it as hidden`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        cache.saveDeletedCourseNos(setOf(handAdded.courseNo))

        // Another sync pins it while this one reads the rosters.
        val read = pinLegacy(whileRead = { pinLegacy(whileRead = {}) })

        assertEquals(setOf(CourseTombstoneKeys.key(TERM, handAdded.courseNo)), read)
        assertEquals(setOf(handAdded.courseNo), hidden())
    }

    @Test
    fun `a course reset elsewhere takes a course whose add back is under way`() = runTest {
        cache.saveCourses(listOf(enrolled, handAdded), TERM)
        syncListing(enrolled.courseNo, handAdded.courseNo)
        delete(handAdded)
        cache.addCourseBack(handAdded.courseNo, TERM)
        cache.saveCourses(cache.loadCourses(TERM) + handAdded, TERM)

        // The sync's snapshot reads a course reset made on another device, which takes the
        // hand-added courses and their adds; the reset's tombstone names this one.
        tombstonedElsewhere(handAdded, resetElsewhere = true)
        cache.courseAddSent(handAdded.courseNo, TERM)

        assertEquals(setOf(handAdded.courseNo), hidden())
        assertEquals(listOf(enrolled.courseNo), courseNos())
    }

    private companion object {
        const val TERM = "1151"

        /** A course another device added, which a sync merges down. */
        const val MERGED = "EE1010007"

        /** A legacy bare hide of a course no term here carries. */
        const val LEGACY = "ZZ1010004"
    }
}
