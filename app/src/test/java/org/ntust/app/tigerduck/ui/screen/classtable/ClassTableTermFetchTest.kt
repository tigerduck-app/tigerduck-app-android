package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.Course

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
}
