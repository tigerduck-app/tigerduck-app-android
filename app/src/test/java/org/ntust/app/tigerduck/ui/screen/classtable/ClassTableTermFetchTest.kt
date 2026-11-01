package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * One term's fetch as far as its list goes. The lookups take seconds, and the user keeps
 * editing meanwhile: what they did while the requests ran has to be in what the fetch saves,
 * so the answers are folded onto the list, and the tombstones, as they stand once they land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassTableTermFetchTest {

    private val gate = CompletableDeferred<Unit>()
    private val asked = mutableListOf<String>()
    private val unanswered = mutableSetOf<String>()
    private val failing = mutableSetOf<String>()
    private val timeless = mutableSetOf<String>()

    /**
     * Every code answered with a fresh row in IB-202, without times for the timeless ones, but
     * the unanswered (no rows) and the failing ones.
     */
    private val fetch = ClassTableTermFetch { _, courseNo, moodle, kept, onFailed ->
        asked += courseNo
        gate.await()
        when (courseNo) {
            // As CourseService.lookupOrFallback comes to them.
            in failing -> { onFailed(); LookedUpCourse(courseNo, CourseService.afterFailedLookup(courseNo, moodle, kept), answered = false) }
            in unanswered -> CourseService.lookedUp(courseNo, emptyList(), moodle, kept) { _, _ -> error("no rows to build from") }
            else -> LookedUpCourse(
                courseNo,
                Course.fromSchedule(
                    courseNo, "$courseNo now", classroom = "IB-202",
                    schedule = if (courseNo in timeless) emptyMap() else mapOf(2 to listOf("5")),
                ),
                answered = true,
            )
        }
    }

    private fun course(no: String, color: String? = null) = Course(courseNo = no, courseName = no, customColorHex = color)
    private fun manual(no: String, room: String = "") =
        Course.fromSchedule(no, no, classroom = room, schedule = mapOf(1 to listOf("1")), isManual = true)

    private suspend fun lookUp(rosterNos: List<String>, before: List<Course>) =
        fetch.lookUp("1151", rosterNos, emptyMap(), before)

    private fun fold(answers: ClassTableTermFetch.Answers, latest: List<Course>, deleted: Set<String> = emptySet()) =
        fetch.fold(answers, latest, names = emptyMap(), locale = "zh", deletedNos = deleted)

    @Test
    fun `an edit and a delete made while the lookups run survive the fetch`() = runTest {
        val before = listOf(course("A"), manual("MINE", room = "T4-101"), manual("GONE"))
        var answers: ClassTableTermFetch.Answers? = null
        launch { answers = lookUp(listOf("A"), before) }
        advanceUntilIdle()
        // While the lookups run: A recoloured, GONE deleted, NEW added by hand. Each writes the
        // term's list, which the fetch reads again once the answers are in.
        val latest = listOf(course("A", color = "#FF0000"), manual("MINE", room = "T4-101"), manual("NEW"))
        gate.complete(Unit)
        advanceUntilIdle()

        val out = fold(answers!!, latest, deleted = setOf("GONE"))
        assertEquals(listOf("A", "MINE", "NEW"), out.map { it.courseNo })
        assertEquals("#FF0000", out[0].customColorHex)
        // MINE was looked up again and refreshed; NEW came too late for this round and is kept as typed.
        assertEquals("IB-202", out[1].classroom)
        assertTrue(out[1].isManual)
        assertEquals(manual("NEW"), out[2])
        assertEquals(setOf("A", "MINE", "GONE"), asked.toSet())
        assertTrue(answers.answered)
    }

    @Test
    fun `a roster course deleted while the lookups run stays deleted`() = runTest {
        // deleteCourse takes the row off the list at once and writes its tombstone after. The
        // answer for it is in, so only the tombstone keeps it out: the fold has to see the
        // delete's tombstone ahead of that write, together with the list the delete left.
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        val latest = listOf(course("A"))
        assertEquals(listOf("A"), fold(answers, latest, deleted = setOf("GONE")).map { it.courseNo })
        assertEquals(listOf("A", "GONE"), fold(answers, latest).map { it.courseNo })
    }

    @Test
    fun `with no roster, the hand-added rows are refreshed and the tombstones still applied`() = runTest {
        gate.complete(Unit)
        val latest = listOf(manual("MINE"), manual("GONE"))
        val answers = lookUp(emptyList(), before = latest)
        // GONE's delete is in the tombstones before its write of the list lands.
        val out = fold(answers, latest, deleted = setOf("GONE"))
        assertEquals(listOf("MINE"), out.map { it.courseNo })
        assertEquals("IB-202", out.single().classroom)
        assertTrue(out.single().isManual)
    }

    @Test
    fun `a failed query is reported, and a hand-added course the query does not answer keeps its snapshot`() = runTest {
        gate.complete(Unit)
        failing += "B"
        unanswered += "MINE"
        val latest = listOf(course("A"), manual("MINE", room = "T4-101"))
        val answers = lookUp(listOf("A", "B"), before = latest)
        assertFalse(answers.answered)
        val out = fold(answers, latest)
        assertEquals(listOf("A", "MINE"), out.map { it.courseNo })
        assertEquals("T4-101", out[1].classroom)
    }

    /** tigerduck-app-android#417, as iOS tigerduck-app#243: a failure says nothing of the course. */
    @Test
    fun `a roster course whose query fails keeps the times and room the term has for it`() = runTest {
        gate.complete(Unit)
        failing += "B"
        val scheduled = Course.fromSchedule("B", "B", classroom = "TR-313", schedule = mapOf(3 to listOf("6", "7")))
        val latest = listOf(course("A"), scheduled)
        val out = fold(lookUp(listOf("A", "B"), before = latest), latest)
        assertEquals(listOf("A", "B"), out.map { it.courseNo })
        assertEquals("TR-313", out[1].classroom)
        assertEquals(mapOf(3 to listOf("6", "7")), out[1].schedule)
    }

    @Test
    fun `a term with nothing to look up, or nothing answered, is left as it is`() = runTest {
        gate.complete(Unit)
        assertTrue(lookUp(emptyList(), before = listOf(course("A"))).isEmpty)
        unanswered += listOf("A", "MINE")
        // Nothing answered and nothing kept: nothing comes back.
        assertTrue(lookUp(listOf("A"), before = listOf(manual("MINE"))).isEmpty)
        // Nothing answered, and the rows the term has come back as they are (iOS tigerduck-app#890).
        val before = listOf(course("A"), manual("MINE"))
        val answers = lookUp(listOf("A"), before = before)
        assertTrue(answers.answered)
        assertEquals(before, fold(answers, before))
    }

    @Test
    fun `each roster course says whether the query answered it`() = runTest {
        gate.complete(Unit)
        unanswered += "B"
        failing += "C"
        val before = listOf(course("B"), course("C"))
        val answers = lookUp(listOf("A", "B", "C"), before = before)
        assertEquals(listOf("A" to true, "B" to false, "C" to false), answers.looked.map { it.courseNo to it.answered })
        // Neither the empty answer nor the failure takes the row the term has.
        assertEquals(before, answers.looked.drop(1).map { it.course })
        assertFalse(answers.answered)
    }

    /**
     * On the term course selection serves too, QueryCourse may have no rows for a course it lists
     * (not indexed yet, or being re-indexed). That is no answer: the course keeps the times and
     * room the term has for it, where Moodle's stand-in, which has neither, took them before.
     */
    @Test
    fun `on the course selection term, a course the query has no rows for keeps its row, not Moodle's stand-in`() = runTest {
        gate.complete(Unit)
        unanswered += "B"
        val scheduled = Course.fromSchedule("B", "B", classroom = "TR-313", schedule = mapOf(3 to listOf("6", "7")), moodleIdNumber = "1151B")
        val latest = listOf(course("A"), scheduled)
        val moodle = mapOf("B" to MoodleEnrolledCourse(7, "B on Moodle", "B", "1151B", 0, 0))

        val answers = fetch.lookUp("1151", listOf("A", "B"), moodle, before = latest)
        val out = fold(answers, latest)

        assertEquals(listOf("A", "B"), out.map { it.courseNo })
        assertEquals("TR-313", out[1].classroom)
        assertEquals(mapOf(3 to listOf("6", "7")), out[1].schedule)
        assertFalse(answers.looked[1].answered)
        assertTrue(answers.answered)
    }

    // --- A past term rebuilt from its transcript (iOS tigerduck-app#890) ---

    private fun merged(no: String) =
        Course.fromSchedule(no, no, schedule = mapOf(1 to listOf("1")), moodleIdNumber = "1151$no", isManual = true)

    private fun transcript(vararg codes: String) = ScoreReport(
        student = "", currentTerm = "", rankings = emptyList(),
        courses = codes.map { CourseGrade(term = "1151", code = it, name = it, status = GradeStatus.GRADED) } +
            CourseGrade(term = "1152", code = "XZ0000099", name = "Later", status = GradeStatus.PENDING),
        creditSummary = CreditSummary.EMPTY,
    )

    /** [before]'s rebuild from [report], its class rows looked up, as the class table makes one. */
    private suspend fun rebuild(report: ScoreReport, before: List<Course>, moodleNos: List<String> = emptyList()): ClassTableTermFetch.Answers {
        val answers = lookUp(TranscriptTerms.classRows(report, "1151").map { it.code }, before)
        val rebuilt = TranscriptTerms.rebuiltPastTerm(answers.looked, report, "1151", before, moodleNos, waitingAdds = emptySet())
        return answers.rebuilding(rebuilt)
    }

    @Test
    fun `a rebuilt past term drops the junk merged from the server and keeps what was added here`() = runTest {
        gate.complete(Unit)
        // Merged from the server and on the transcript, its lookup unanswered.
        unanswered += "XG0000007"
        val before = listOf(
            course("XA0000001"),
            course("XC0000003"),
            merged("XD0000004"),
            manual("XF0000006"),
            merged("XG0000007"),
        )

        val out = fold(rebuild(transcript("XA0000001", "XG0000007"), before, moodleNos = listOf("XD0000004")), before)

        assertEquals(listOf("XA0000001", "XG0000007", "XF0000006"), out.map { it.courseNo })
        assertEquals("IB-202", out[0].classroom)
        assertTrue(out[1].isManual)
        assertEquals("1151XG0000007", out[1].moodleIdNumber)
        assertTrue(out[2].isManual)
    }

    @Test
    fun `a past term the transcript leaves no class saves empty but for what was added here`() = runTest {
        gate.complete(Unit)
        timeless += "XH0000008"
        val before = listOf(course("XJ0000009"), merged("XD0000004"), manual("XF0000006"))

        val answers = rebuild(transcript("XH0000008"), before)

        assertFalse(answers.isEmpty)
        assertEquals(listOf("XF0000006"), fold(answers, before).map { it.courseNo })
        val nothing = ClassTableTermFetch.Answers(emptyList(), emptyMap(), answered = true)
        assertTrue(nothing.isEmpty)
        assertFalse(nothing.rebuilding(TranscriptTerms.RebuiltTerm(emptyList(), emptyList(), listOf("XJ0000009"))).isEmpty)
    }

    @Test
    fun `a rebuild keeps a delete and a hand-add made while its lookups ran`() = runTest {
        gate.complete(Unit)
        val before = listOf(course("XA0000001"), course("XB0000002"), merged("XD0000004"))
        // XQ is a Moodle site of the term, so the rebuild drops its number.
        val answers = rebuild(transcript("XA0000001", "XB0000002"), before, moodleNos = listOf("XQ0000017"))
        val snapshot = Snapshot().apply {
            cached = before
            shown = before
            cacheGate.complete(Unit)
            fileGate.complete(Unit)
        }
        snapshot.delete("XB0000002", written = true)
        snapshot.add(manual("XQ0000017"))

        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()

        assertEquals(listOf("XA0000001", "XQ0000017"), out()!!.map { it.courseNo })
    }

    /** The snapshot the fold is taken over, with the term's cache and the tombstone file each behind a gate. */
    private class Snapshot {
        val edits = ClassTableTombstoneEdits()
        val cacheGate = CompletableDeferred<Unit>()
        val fileGate = CompletableDeferred<Unit>()
        var cached = listOf<Course>()
        var file = emptySet<String>()
        /** The list on screen while it is this term's, else null. */
        var shown: List<Course>? = null
        /** Bumped at every edit of the term's list, as the view model's per-term count is. */
        var generation = 0
        var cacheReads = 0

        /**
         * A delete as the view model makes one: off the list at once, the edit counted, the
         * tombstone pending; its write lands with [written], at once when [written] is set.
         */
        fun delete(courseNo: String, written: Boolean = false): Long {
            shown = shown?.filter { it.courseNo != courseNo }
            generation++
            val id = edits.edit { CourseTombstoneKeys.hide(courseNo, "1151", it) }
            if (written) written(courseNo, id)
            return id
        }

        /** The delete's tombstone write landed: the file carries it, and the pending edit is retired. */
        fun written(courseNo: String, id: Long) {
            file = CourseTombstoneKeys.hide(courseNo, "1151", file)
            edits.saved(id)
        }

        /** An add as the view model makes one: on the list at once, and its save of the list landed. */
        fun add(course: Course) {
            shown = shown?.plus(course)
            generation++
            cached = cached + course
        }

        /** The courses an edit on screen added, took off or changed, whose save of the list has not landed. */
        val unsaved = mutableSetOf<String>()

        /** An add whose save of the list is still to land, with [saved]. */
        fun addUnsaved(course: Course) {
            shown = shown?.plus(course)
            generation++
            unsaved += course.courseNo
        }

        fun saved(course: Course) {
            cached = cached + course
            unsaved -= course.courseNo
        }

        /** A take-back, off the list at once, whose save of the list is still to land. */
        fun removeUnsaved(courseNo: String) {
            shown = shown?.filter { it.courseNo != courseNo }
            generation++
            unsaved += courseNo
        }

        /** The user picks another term: the list on screen is that term's now. */
        fun switchAway() {
            shown = null
        }
    }

    private fun TestScope.foldLatest(answers: ClassTableTermFetch.Answers, snapshot: Snapshot): () -> List<Course>? {
        var out: List<Course>? = null
        var done = false
        launch {
            out = fetch.foldLatest(
                answers, "1151",
                editGeneration = { snapshot.generation },
                // The file is read as the read begins, as DataCache reads it, and the answer comes back after the gate.
                cachedCourses = { snapshot.cacheReads++; val read = snapshot.cached; snapshot.cacheGate.await(); read },
                storedTombstones = { snapshot.fileGate.await(); snapshot.file },
                shownCourses = { snapshot.shown },
                unsavedEdits = { snapshot.unsaved.toSet() },
                withPendingEdits = snapshot.edits::over,
                names = { emptyMap() },
                locale = "zh",
            )
            done = true
        }
        return { check(done) { "foldLatest has not returned" }; out }
    }

    @Test
    fun `a course added while the term's cache is read survives a switch to another term`() = runTest {
        // The cache was read before X was added and saved, and by the time the tombstones are in
        // the list on screen is another term's, so the cache read is all the fold has: it must
        // be read again, or the commit writes the term back without X and X's own upload, queued
        // behind the commit, finds it gone.
        gate.complete(Unit)
        val answers = lookUp(listOf("A"), before = listOf(course("A")))
        val snapshot = Snapshot().apply {
            cached = listOf(course("A"))
            shown = listOf(course("A"))
            fileGate.complete(Unit)
        }
        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        snapshot.add(manual("X"))
        snapshot.switchAway()
        snapshot.cacheGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("A", "X"), out()!!.map { it.courseNo })
    }

    @Test
    fun `a term edited throughout its reads is left to the next fetch, after three attempts`() = runTest {
        gate.complete(Unit)
        val answers = lookUp(listOf("A"), before = listOf(course("A")))
        val snapshot = Snapshot().apply {
            cached = listOf(course("A"))
            cacheGate.complete(Unit)
            fileGate.complete(Unit)
        }
        var out: List<Course>? = null
        var reads = 0
        launch {
            out = fetch.foldLatest(
                answers, "1151",
                editGeneration = { snapshot.generation },
                // An edit lands during every read.
                cachedCourses = { reads++; snapshot.generation++; snapshot.cached },
                storedTombstones = { snapshot.file },
                shownCourses = { null },
                withPendingEdits = snapshot.edits::over,
                names = { emptyMap() },
                locale = "zh",
            )
        }
        advanceUntilIdle()
        assertEquals(3, reads)
        assertEquals(null, out)
    }

    @Test
    fun `a delete written while the term's cache is read stays deleted`() = runTest {
        // The term was not on screen when the commit began, so its list comes from the cache.
        // While that read runs, the term comes on screen, GONE is deleted, and the tombstone's
        // write lands, retiring the pending edit: only the file knows the delete now, so the
        // file has to be read after the cache, and the list chosen once both are in.
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        // The file answers at once; only the cache read is slow.
        val snapshot = Snapshot().apply {
            cached = listOf(course("A"), course("GONE"))
            fileGate.complete(Unit)
        }
        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        snapshot.shown = listOf(course("A"), course("GONE"))
        snapshot.delete("GONE", written = true)
        snapshot.cacheGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("A"), out()!!.map { it.courseNo })
    }

    @Test
    fun `a delete tapped before the commit, whose tombstone is written during the cache read, stays deleted`() = runTest {
        // No edit falls inside the reads, so the snapshot is not taken again: only the order of
        // the reads keeps GONE out. The tap left the pending edit as the delete's only record;
        // its write lands, and retires that edit, while the cache is read — so the file has to
        // be read after the cache. Read the other way round, the file predates the write and
        // the edit is gone by the fold, and GONE's roster answer brings it back.
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        val snapshot = Snapshot().apply {
            cached = listOf(course("A"), course("GONE"))
            shown = listOf(course("A"), course("GONE"))
            fileGate.complete(Unit)
        }
        val tombstone = snapshot.delete("GONE")
        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        snapshot.written("GONE", tombstone)
        snapshot.cacheGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, snapshot.cacheReads)
        assertEquals(listOf("A"), out()!!.map { it.courseNo })
    }

    /**
     * The background sync took a dropped merged copy off the term's cache, and the list on screen
     * holds it until the sync's notice comes. A term only gaining keeps a hand-added leftover, so
     * the fold onto that list saved the course back. The screen's edits still count: XA's colour,
     * whose save has not landed.
     */
    @Test
    fun `a course the cache no longer holds is not brought back by the list on screen`() = runTest {
        val answers = ClassTableTermFetch.Answers(listOf(course("XA0000001")), emptyMap(), answered = true, onlyGains = true)
        val snapshot = Snapshot().apply {
            cached = listOf(course("XA0000001"), manual("XF0000006"))
            shown = listOf(course("XA0000001", color = "#FF0000"), merged("XD0000004"), manual("XF0000006"))
            unsaved += "XA0000001"
            cacheGate.complete(Unit)
            fileGate.complete(Unit)
        }

        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()

        assertEquals(listOf("XA0000001", "XF0000006"), out()!!.map { it.courseNo })
        assertEquals("#FF0000", out()!![0].customColorHex)
    }

    /**
     * An add is on screen at the tap and in the cache once its save lands, here while the cache
     * is read: the adds still on their way are read first, so the course is in one or the other.
     */
    @Test
    fun `a course added on screen whose save lands while the cache is read is kept`() = runTest {
        val answers = ClassTableTermFetch.Answers(listOf(course("XA0000001")), emptyMap(), answered = true, onlyGains = true)
        val snapshot = Snapshot().apply {
            cached = listOf(course("XA0000001"))
            shown = listOf(course("XA0000001"))
            fileGate.complete(Unit)
        }
        val added = manual("XQ0000017")
        snapshot.addUnsaved(added)

        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        snapshot.saved(added)
        snapshot.cacheGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, snapshot.cacheReads)
        assertEquals(listOf("XA0000001", "XQ0000017"), out()!!.map { it.courseNo })
    }

    /**
     * While the lookups ran a sync merged XD, added on another device, into the term's cache, and
     * the colour picked there for XA; its notice has not reached the screen, which holds neither.
     * Course selection answers XA alone, so the fold onto the list on screen saved, and uploaded,
     * the term without XD and XA's colour. The cache is the base; the screen gave no edit.
     */
    @Test
    fun `a course and a colour a sync saved while the lookups ran survive the fold`() = runTest {
        val answers = ClassTableTermFetch.Answers(listOf(course("XA0000001")), emptyMap(), answered = true)
        val snapshot = Snapshot().apply {
            cached = listOf(course("XA0000001", color = "#00FF00"), merged("XD0000004"))
            shown = listOf(course("XA0000001"))
            cacheGate.complete(Unit)
            fileGate.complete(Unit)
        }

        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()

        assertEquals(listOf("XA0000001", "XD0000004"), out()!!.map { it.courseNo })
        assertEquals("#00FF00", out()!![0].customColorHex)
    }

    /** A take-back removes a course added here with no tombstone: only its save, still to land, says so. */
    @Test
    fun `a course taken back on screen whose save has not landed stays out`() = runTest {
        val answers = ClassTableTermFetch.Answers(listOf(course("XA0000001")), emptyMap(), answered = true)
        val snapshot = Snapshot().apply {
            cached = listOf(course("XA0000001"), manual("XQ0000017"))
            shown = cached
            cacheGate.complete(Unit)
            fileGate.complete(Unit)
        }
        snapshot.removeUnsaved("XQ0000017")

        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()

        assertEquals(listOf("XA0000001"), out()!!.map { it.courseNo })
    }

    @Test
    fun `a delete not yet written while the tombstones are read stays deleted`() = runTest {
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        val snapshot = Snapshot().apply {
            shown = listOf(course("A"), course("GONE"))
            cacheGate.complete(Unit)
        }
        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        // Tapped while the file is read: the pending edit is what carries it.
        snapshot.delete("GONE", written = false)
        snapshot.fileGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("A"), out()!!.map { it.courseNo })
    }

    // --- The commit: the term drawn from its source (iOS fetchCourses, tigerduck-app#890) ---

    /** A transcript whose newest term is 115-1, so the term only gains. */
    private fun newest(vararg codes: String) = ScoreReport(
        student = "", currentTerm = "", rankings = emptyList(),
        courses = codes.map { CourseGrade(term = "1151", code = it, name = it, status = GradeStatus.PENDING) },
        creditSummary = CreditSummary.EMPTY,
    )

    private fun moodle(no: String) = no to MoodleEnrolledCourse(7, "$no on Moodle", no, "1151$no", 0, 0)

    private fun cache(): DataCache {
        val context = JvmContext()
        return DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    /** [committed] with nothing recorded and [fold] over [latest], for the sources that rebuild nothing. */
    private suspend fun committed(
        answers: ClassTableTermFetch.Answers,
        source: ClassTableTermSource,
        latest: List<Course>,
        moodleNos: Collection<String> = emptyList(),
    ) = fetch.committed(
        answers, source, "1151", moodleNos,
        latest = { latest },
        waitingAdds = { emptySet() },
        droppedNos = { emptySet() },
        recordFinalRoster = { error("only a rebuild records drops") },
    ) { fold(it, latest) }

    /**
     * iOS PastTermRebuildTests, through the commit: the term's drops are on disk before the term
     * is saved, so a sync landing between the two cannot merge the junk back, and the save takes
     * the junk off this phone without a hide. A course added here stays, and so does one whose
     * lookup failed, with the times and room it had.
     */
    @Test
    fun `a past term is rebuilt with its drops recorded before it is saved`() = runTest {
        gate.complete(Unit)
        failing += "XG0000007"
        val store = cache()
        val keptCopy = Course.fromSchedule("XG0000007", "Kept", classroom = "TR-313", schedule = mapOf(3 to listOf("6", "7")))
        val before = listOf(course("XA0000001"), course("XC0000003"), keptCopy, merged("XD0000004"), manual("XF0000006"))
        store.saveCourses(before, "1151")
        val source = ClassTableTermSource.of("1151", emptyList(), transcript("XA0000001", "XG0000007"), servedBySelection = false, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before, atMost = source.lookupsAtOnce)

        val steps = mutableListOf<String>()
        var droppedAtTheFold = emptySet<String>()
        val saved = fetch.committed(
            answers, source, "1151",
            // XW is a Moodle site of the term whose add here has not gone up yet.
            moodleNos = listOf("XD0000004", "XW0000023"),
            latest = { store.loadCourses("1151") },
            waitingAdds = { setOf("XW0000023") },
            droppedNos = { emptySet() },
            recordFinalRoster = {
                steps += "recorded"
                store.recordFinalRoster("1151", it.roster, alsoListed = it.dropping)
            },
        ) { drawn ->
            steps += "folded"
            droppedAtTheFold = store.loadSelectionDroppedNos()["1151"].orEmpty().toSet()
            fetch.foldLatest(
                drawn, "1151",
                editGeneration = { 0 },
                cachedCourses = { store.loadCourses("1151") },
                storedTombstones = { store.loadDeletedCourseNos() },
                shownCourses = { null },
                withPendingEdits = { it },
                names = { emptyMap() },
                locale = "zh",
            )
        }!!
        store.saveCourses(saved, "1151")

        assertEquals(listOf("recorded", "folded"), steps)
        // The junk, cached and merged, is dropped; the waiting add and the course added here are not.
        assertEquals(setOf("XC0000003", "XD0000004"), droppedAtTheFold)
        val term = store.loadCourses("1151")
        assertEquals(listOf("XA0000001", "XG0000007", "XF0000006"), term.map { it.courseNo })
        assertEquals("IB-202", term[0].classroom)
        assertEquals(keptCopy.schedule, term[1].schedule)
        assertEquals("TR-313", term[1].classroom)
        // Taken off by the save, not hidden: listed again, the course comes back.
        assertEquals(emptySet<String>(), CourseTombstoneKeys.hiddenIn("1151", store.loadDeletedCourseNos()))
    }

    @Test
    fun `a past term the transcript leaves no class is still committed, and saved empty but for what was added here`() = runTest {
        gate.complete(Unit)
        val report = ScoreReport(
            student = "", currentTerm = "", rankings = emptyList(),
            courses = listOf(
                CourseGrade(term = "1151", code = "XW0000023", name = "Withdrawn", status = GradeStatus.WITHDREW),
                CourseGrade(term = "1152", code = "XZ0000099", name = "Later", status = GradeStatus.PENDING),
            ),
            creditSummary = CreditSummary.EMPTY,
        )
        val before = listOf(course("XW0000023"), manual("XF0000006"))
        val source = ClassTableTermSource.of("1151", emptyList(), report, servedBySelection = false, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before = emptyList())
        var recorded: TranscriptTerms.RebuiltTerm? = null

        val saved = fetch.committed(
            answers, source, "1151", emptyList(),
            latest = { before },
            waitingAdds = { emptySet() },
            droppedNos = { emptySet() },
            recordFinalRoster = { recorded = it },
        ) { fold(it, before) }

        assertEquals(listOf("XF0000006"), saved!!.map { it.courseNo })
        assertEquals(emptyList<String>(), recorded!!.roster)
        assertEquals(setOf("XW0000023"), recorded!!.dropped)
    }

    @Test
    fun `a rebuild looks its classes up at most four at a time`() = runTest {
        var inFlight = 0
        var most = 0
        val counting = ClassTableTermFetch { _, courseNo, _, _, _ ->
            inFlight++
            most = maxOf(most, inFlight)
            gate.await()
            inFlight--
            LookedUpCourse(courseNo, null, answered = false)
        }
        val codes = (1..9).map { "XA00000%02d".format(it) }

        launch { counting.lookUp("1151", codes, emptyMap(), before = emptyList(), atMost = 4) }
        advanceUntilIdle()
        assertEquals(4, inFlight)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, most)
        assertEquals(0, inFlight)
    }

    /**
     * A term course selection gave nothing for only gains the transcript's classes: a new one
     * joins with class times, a cached one stays, and a Moodle site of the term is not even
     * looked up.
     */
    @Test
    fun `a term only gaining from the transcript adds a class with times and keeps every cached course`() = runTest {
        gate.complete(Unit)
        timeless += "XH0000008"
        val moodle = mapOf(moodle("XM0000013"), moodle("XA0000001"))
        val before = listOf(course("XC0000003"), manual("XF0000006"))
        val source = ClassTableTermSource.of("1151", emptyList(), newest("XA0000001", "XH0000008"), servedBySelection = true, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, moodle, before)

        val saved = committed(answers, source, before, moodle.keys)

        assertEquals(listOf("XA0000001", "XC0000003", "XF0000006"), saved!!.map { it.courseNo })
        assertFalse("XM0000013" in asked)
    }

    /**
     * The term's list is read for its source as the lookups begin, and Home or the background
     * sync can save the term before they land. A term that only gains removes none of what that
     * saved, as its answers do not list it, but a course deleted meanwhile stays deleted.
     */
    @Test
    fun `a term only gaining keeps a course saved while its lookups ran, but not one deleted then`() = runTest {
        gate.complete(Unit)
        val before = listOf(course("XA0000001"))
        val source = ClassTableTermSource.of("1151", emptyList(), newest("XA0000001"), servedBySelection = true, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before)
        // Home's fetch saved XB and XC while these lookups ran, and the user deleted XC since.
        val latest = before + course("XB0000002") + course("XC0000003")

        val saved = fetch.committed(
            answers, source, "1151", emptyList(),
            latest = { latest },
            waitingAdds = { emptySet() },
            droppedNos = { emptySet() },
            recordFinalRoster = { error("only a rebuild records drops") },
        ) { fold(it, latest, deleted = setOf("XC0000003")) }

        assertEquals(listOf("XA0000001", "XB0000002"), saved!!.map { it.courseNo })
        assertEquals("IB-202", saved[0].classroom)
    }

    /**
     * A term only gaining looks up every course it held as its fetch began. A rebuild of the term
     * committed while the lookups ran took its junk off and put it on the drop list, and the
     * junk's answer, with class times, would join the term again. A course added back by hand
     * since, here or with its add still waiting, takes its answer as before.
     */
    @Test
    fun `a term only gaining does not bring back a course a rebuild dropped while its lookups ran, but one added back by hand takes its answer`() = runTest {
        gate.complete(Unit)
        val before = listOf(course("XA0000001"), course("XJ0000009"), course("XB0000002"), course("XW0000023"))
        val source = ClassTableTermSource.of("1151", emptyList(), newest("XA0000001"), servedBySelection = true, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before)
        // The rebuild dropped the three and saved the term; XB and XW were then added back by hand.
        val latest = listOf(course("XA0000001"), manual("XB0000002", room = "TR-313"), merged("XW0000023"))

        val saved = fetch.committed(
            answers, source, "1151", emptyList(),
            latest = { latest },
            waitingAdds = { setOf("XW0000023") },
            droppedNos = { setOf("XJ0000009", "XB0000002", "XW0000023") },
            recordFinalRoster = { error("only a rebuild records drops") },
        ) { fold(it, latest) }!!

        assertEquals(listOf("XA0000001", "XB0000002", "XW0000023"), saved.map { it.courseNo })
        assertEquals(listOf("IB-202", "IB-202"), saved.drop(1).map { it.classroom })
        assertTrue(saved.drop(1).all { it.isManual })
    }

    /**
     * The commit read the drop list, empty, and then the waiting adds; while that read suspended,
     * Home's course selection answer dropped XB and saved the term without it. The answers still
     * list XB, and a term only gaining keeps every answer: the drop list is read again under the
     * courses' lock, where the term cannot be saved meanwhile.
     */
    @Test
    fun `a term only gaining does not bring back a course course selection dropped while the waiting adds were read`() = runTest {
        gate.complete(Unit)
        val store = cache()
        val before = listOf(course("XA0000001"), course("XB0000002"))
        store.saveCourses(before, "1151")
        val source = ClassTableTermSource.of("1151", emptyList(), newest("XA0000001", "XB0000002"), servedBySelection = true, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before)
        val dropped = suspend { store.loadSelectionDroppedNos()["1151"].orEmpty().toSet() }
        var answered = false

        val saved = fetch.committed(
            answers, source, "1151", emptyList(),
            latest = { store.loadCourses("1151") },
            waitingAdds = {
                if (!answered) {
                    answered = true
                    store.recordSelectionRoster("1151", listOf("XA0000001"))
                    store.saveCourses(listOf(course("XA0000001")), "1151")
                }
                emptySet()
            },
            droppedNos = dropped,
            recordFinalRoster = { error("only a rebuild records drops") },
        ) { drawn ->
            var folded: List<Course>? = null
            store.updateCourses("1151") { current, _ ->
                folded = fetch.foldLatest(
                    drawn, "1151",
                    editGeneration = { 0 },
                    cachedCourses = { current },
                    storedTombstones = { store.loadDeletedCourseNos() },
                    shownCourses = { null },
                    droppedNos = dropped,
                    waitingAdds = { emptySet() },
                    withPendingEdits = { it },
                    names = { emptyMap() },
                    locale = "zh",
                )
                folded ?: current
            }
            folded
        }

        assertTrue(answered)
        assertEquals(listOf("XA0000001"), saved!!.map { it.courseNo })
        assertEquals(listOf("XA0000001"), store.loadCourses("1151").map { it.courseNo })
    }

    /**
     * [answers] committed onto [store]'s term as fetchTermCourses commits them: drawn with the
     * drop list as it stands, then folded under the courses' lock through [ClassTableTermFetch.foldLatest],
     * which reads the drops again, and saved.
     */
    private suspend fun committedOnto(
        store: DataCache,
        answers: ClassTableTermFetch.Answers,
        source: ClassTableTermSource,
        waitingAdds: Set<String> = emptySet(),
    ) = fetch.committed(
        answers, source, "1151", emptyList(),
        latest = { store.loadCourses("1151") },
        waitingAdds = { waitingAdds },
        droppedNos = { store.fetchDropsIn("1151") },
        recordFinalRoster = { store.recordFinalRoster("1151", it.roster, alsoListed = it.dropping) },
    ) { drawn ->
        var folded: List<Course>? = null
        store.updateCourses("1151") { current, _ ->
            folded = fetch.foldLatest(
                drawn, "1151",
                editGeneration = { 0 },
                cachedCourses = { current },
                storedTombstones = { store.loadDeletedCourseNos() },
                shownCourses = { null },
                droppedNos = { store.fetchDropsIn("1151") },
                waitingAdds = { waitingAdds },
                withPendingEdits = { it },
                names = { emptyMap() },
                locale = "zh",
            )
            folded ?: current
        }
        folded
    }

    /**
     * A term only gaining draws every course it held as its fetch began. While the lookups ran, a
     * full sync found one dropped on the server, and the drop list here names the other: the
     * drops take every course drawn. The term is saved without them, empty here, rather than
     * left as it is, still holding them. As the iPhone's `savedCoursesSkipServerDrops`
     * (tigerduck-app#918): only a fetch that drew nothing keeps the cache.
     */
    @Test
    fun `a term only gaining whose every drawn course was dropped as it saves is saved without them, not left as it is`() = runTest {
        gate.complete(Unit)
        val store = cache()
        val before = listOf(course("XA0000001"), course("XB0000002"))
        store.saveCourses(before, "1151")
        val source = ClassTableTermSource.of("1151", emptyList(), newest("XA0000001"), servedBySelection = true, cached = before)
        assertTrue(source is ClassTableTermSource.Gaining)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before)
        store.recordServerDrops("1151", mapOf("XA0000001" to null))
        store.saveSelectionDroppedNos(mapOf("1151" to listOf("XB0000002")))

        val saved = committedOnto(store, answers, source)

        assertEquals(emptyList<String>(), saved?.map { it.courseNo })
        assertEquals(emptyList<String>(), store.loadCourses("1151").map { it.courseNo })
        assertEquals(emptySet<String>(), store.loadDeletedCourseNos())
    }

    /**
     * As above, with courses added by hand, here and with the add still waiting, which the drops
     * name too, and a course Home saved while the lookups ran, which the fetch did not draw. The
     * dropped courses leave; the hand-added ones stay, refreshed, and so does the one saved
     * meanwhile, as the term only gains.
     */
    @Test
    fun `a term only gaining whose every drawn course was dropped keeps what was added by hand and what was saved meanwhile`() = runTest {
        gate.complete(Unit)
        val store = cache()
        val before = listOf(course("XA0000001"), course("XB0000002"), manual("XF0000006"), merged("XW0000023"))
        store.saveCourses(before, "1151")
        val source = ClassTableTermSource.of("1151", emptyList(), newest("XA0000001"), servedBySelection = true, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before)
        store.saveCourses(store.loadCourses("1151") + course("XK0000011"), "1151")
        store.recordServerDrops("1151", mapOf("XA0000001" to null, "XF0000006" to null))
        store.saveSelectionDroppedNos(mapOf("1151" to listOf("XB0000002", "XW0000023")))

        val saved = committedOnto(store, answers, source, waitingAdds = setOf("XW0000023"))!!

        assertEquals(listOf("XK0000011", "XF0000006", "XW0000023"), saved.map { it.courseNo })
        assertEquals(listOf("XK0000011", "XF0000006", "XW0000023"), store.loadCourses("1151").map { it.courseNo })
        assertEquals(listOf("IB-202", "IB-202"), saved.drop(1).map { it.classroom })
        assertEquals(emptySet<String>(), store.loadDeletedCourseNos())
    }

    /**
     * Course selection's answer is the term's whole list, and a past term's rebuild is its final
     * transcript's: each saves every course it draws, though the drops name every one.
     */
    @Test
    fun `course selection's answer and a past term's rebuild save every course they draw though the drops name each`() = runTest {
        gate.complete(Unit)
        for (rebuilds in listOf(false, true)) {
            val store = cache()
            val before = listOf(course("XA0000001"), course("XB0000002"))
            store.saveCourses(before, "1151")
            val source = if (rebuilds) {
                ClassTableTermSource.of("1151", emptyList(), transcript("XA0000001", "XB0000002"), servedBySelection = false, cached = before)
            } else {
                ClassTableTermSource.of("1151", listOf("XA0000001", "XB0000002"), null, servedBySelection = true, cached = before)
            }
            assertEquals(rebuilds, source is ClassTableTermSource.Rebuild)
            val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before, atMost = source.lookupsAtOnce)
            store.recordServerDrops("1151", mapOf("XA0000001" to null))
            store.saveSelectionDroppedNos(mapOf("1151" to listOf("XB0000002")))

            val saved = committedOnto(store, answers, source)

            assertEquals(listOf("XA0000001", "XB0000002"), saved!!.map { it.courseNo })
            assertEquals(listOf("XA0000001", "XB0000002"), store.loadCourses("1151").map { it.courseNo })
        }
    }

    /** Before #890 Moodle's sites were the roster of a term course selection gave nothing for. */
    @Test
    fun `with neither course selection nor the transcript, a Moodle site adds no course and the term is left as it is`() = runTest {
        gate.complete(Unit)
        val moodle = mapOf(moodle("XM0000013"))
        val before = listOf(course("XC0000003"))
        for (servedBySelection in listOf(true, false)) {
            val source = ClassTableTermSource.of("1151", emptyList(), null, servedBySelection, cached = before)
            val answers = fetch.lookUp("1151", source.rosterNos, moodle, before)

            assertNull(committed(answers, source, before, moodle.keys))
        }
        assertEquals(emptyList<String>(), asked)
    }

    /** The term course selection serves keeps its rules: its answer is the term, timeless courses too. */
    @Test
    fun `course selection's answer is committed as before`() = runTest {
        gate.complete(Unit)
        timeless += "XB0000002"
        val before = listOf(course("XC0000003"))
        val source = ClassTableTermSource.of("1151", listOf("XA0000001", "XB0000002"), null, servedBySelection = true, cached = before)
        val answers = fetch.lookUp("1151", source.rosterNos, emptyMap(), before)

        assertEquals(listOf("XA0000001", "XB0000002"), committed(answers, source, before)!!.map { it.courseNo })
    }
}
