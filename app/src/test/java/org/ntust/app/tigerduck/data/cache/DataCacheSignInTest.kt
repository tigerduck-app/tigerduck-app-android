package org.ntust.app.tigerduck.data.cache

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.RecordedSelection
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Every write of the school account's data takes the sign-in it was read for, and the store asks
 * it under the folder's lock. A sign-out ends the sign-in before the wipe takes that lock, so a
 * write either finishes before the wipe starts, which then takes it, or finds its sign-in ended
 * and leaves the file as it was. Made-up course numbers and student IDs.
 */
class DataCacheSignInTest {

    private lateinit var context: Context
    private lateinit var cache: DataCache
    private val series = SignInSeries()

    private val cacheFolder get() = File(context.cacheDir, "TigerDuckCache")
    private val dataFolder get() = File(context.filesDir, "TigerDuckData")

    @Before
    fun setUp() {
        context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    private fun assignment(id: String) = Assignment(
        assignmentId = id, courseNo = "XA0000001", courseName = "Made up", title = "Task $id", dueDate = Date(1_700_000_000_000),
    )

    private fun course(no: String, manual: Boolean = false) = Course(courseNo = no, courseName = no, isManual = manual)

    private fun record(id: Int) = MoodleAssignmentRecord(
        assignId = id, cmid = id, courseId = 1, name = "A$id", dueDate = 0, cutoffDate = 0, allowSubmissionsFrom = 0, intro = "",
        introAttachments = emptyList(), noSubmissions = false, teamSubmission = false,
    )

    /** One write of each file a row of `docs/account-state.md` gives the school, and what it leaves. */
    private inner class Write(val file: File, val write: suspend (mark: String, signIn: SignIn) -> Unit)

    private fun writes(): List<Write> {
        val moodleFile = File(cacheFolder, "moodle/$STUDENT/forums.json")
        return listOf(
            Write(File(cacheFolder, "courses_$TERM.json")) { mark, s -> cache.saveCourses(listOf(course(mark)), TERM, s) },
            Write(File(dataFolder, "manual_courses_$TERM.json")) { mark, s -> cache.saveCourses(listOf(course(mark, manual = true)), TERM, s) },
            Write(File(cacheFolder, "assignments.json")) { mark, s -> cache.saveAssignments(listOf(assignment(mark)), s) },
            Write(File(cacheFolder, "moodle_assignments.json")) { mark, s -> cache.saveAssignmentRecords(listOf(record(mark.hashCode())), s) },
            Write(moodleFile) { mark, s -> cache.saveMoodleData(listOf(mark), 1L, "forums", STUDENT, s) },
            Write(File(cacheFolder, "calendar_events.json")) { mark, s ->
                cache.saveCalendarEvents(listOf(CalendarEvent(eventId = mark, title = mark, date = Date(0), sourceRaw = "moodle")), s)
            },
            Write(File(cacheFolder, "course_lookups.json")) { mark, s -> cache.saveCourseLookups(mapOf(mark to DataCache.CourseLookupEntry(emptyList(), 1L)), s) },
            Write(File(cacheFolder, "moodle_course_ids.json")) { mark, s -> cache.saveMoodleCourseIds(mapOf(mark to 1), s) },
            Write(File(cacheFolder, "closed_assignments.json")) { mark, s -> cache.saveClosedAssignments(mapOf(mark.hashCode() to 1L), s) },
            Write(File(cacheFolder, "score_$STUDENT.json")) { mark, s ->
                cache.saveScoreReport(ScoreReport.EMPTY.copy(student = mark), STUDENT, s)
            },
            Write(File(dataFolder, "skipped_dates.json")) { mark, s -> cache.saveSkippedDates(mapOf(mark to listOf("2026-01-01")), s) },
            Write(File(dataFolder, "ignored_assignments.json")) { mark, s -> cache.saveIgnoredAssignments(setOf(mark), s) },
            Write(File(dataFolder, "marked_completed_assignments.json")) { mark, s -> cache.saveMarkedCompletedAssignments(setOf(mark), s) },
            Write(File(dataFolder, "deleted_courses.json")) { mark, s -> cache.saveDeletedCourseNos(setOf("$TERM:$mark"), s) },
            Write(File(dataFolder, "course_custom_names.json")) { mark, s -> cache.saveCourseCustomNames(mapOf(mark to mapOf("zh" to mark)), s) },
            Write(File(dataFolder, "selection_dropped.json")) { mark, s -> cache.saveSelectionDroppedNos(mapOf(TERM to listOf(mark)), s) },
            Write(File(dataFolder, "server_known_courses.json")) { mark, s -> cache.saveServerKnownNos(mapOf(TERM to listOf(mark)), s) },
            Write(File(dataFolder, "semester_reset_at.json")) { mark, s -> cache.saveSemesterResetAt(mark, 1L, s) },
            Write(File(dataFolder, "server_dropped.json")) { mark, s -> cache.recordServerDrops(TERM, mapOf(mark to null), s) },
            Write(File(dataFolder, "selection_rosters.json")) { mark, s -> cache.recordSelectionRoster(TERM, listOf(mark), s) },
        )
    }

    @Test
    fun `a write under an ended sign-in leaves the file as it was`() = runTest {
        val signIn = series.current
        val writes = writes()
        writes.forEach { it.write("before", signIn) }
        val held = writes.associateWith { w ->
            assertTrue("${w.file.name} was not written while its sign-in stood", w.file.exists())
            w.file.readBytes()
        }

        series.end()

        writes.forEach { w ->
            w.write("after", signIn)
            assertArrayEquals("${w.file.name} changed under an ended sign-in", held.getValue(w), w.file.readBytes())
        }
    }

    @Test
    fun `a write under a sign-in ended and followed by the next leaves the next one's, the same student or not`() = runTest {
        val first = series.current
        series.end()
        series.open()
        val writes = writes()
        writes.forEach { it.write("next", series.current) }
        val held = writes.associateWith { it.file.readBytes() }

        writes.forEach { w ->
            w.write("old run", first)
            assertArrayEquals("${w.file.name} took the old run's write", held.getValue(w), w.file.readBytes())
        }
    }

    @Test
    fun `an update under an ended sign-in hands nothing back, and writes nothing`() = runTest {
        val signIn = series.current
        cache.saveAssignments(listOf(assignment("kept")), signIn)
        cache.saveCourses(listOf(course("XA0000001")), TERM, signIn)
        series.end()

        var changed = false
        assertNull(cache.updateAssignments(signIn) { changed = true; it + assignment("late") })
        assertFalse("the assignments' change does not even run", changed)
        assertNull(
            "nothing was written, so nothing is handed back as written",
            cache.updateCoursesIfChanged(TERM, signIn) { it + course("XB0000002") },
        )
        assertEquals(RecordedSelection.Refused, cache.recordSelectionRoster(TERM, listOf("XB0000002"), signIn))
        assertFalse(cache.saveAssignments(listOf(assignment("late")), signIn))
        assertFalse(cache.saveIgnoredAssignments(setOf("late"), signIn))
        assertFalse(cache.saveMarkedCompletedAssignments(setOf("late"), signIn))

        assertEquals(listOf("kept"), cache.loadAssignments().map { it.assignmentId })
        assertEquals(listOf("XA0000001"), cache.loadCourses(TERM).map { it.courseNo })
    }

    @Test
    fun `a write that is dropped tells no reader to look again`() = runTest {
        val signIn = series.current
        series.end()
        val versions = { listOf(cache.coursesVersion.value, cache.assignmentsVersion.value, cache.assignmentMarksVersion.value) }
        val before = versions()

        cache.saveCourses(listOf(course("XA0000001")), TERM, signIn)
        cache.saveAssignments(listOf(assignment("late")), signIn)
        cache.saveIgnoredAssignments(setOf("late"), signIn)

        assertEquals(before, versions())
    }

    @Test
    fun `a course write that passed the check is gone after the wipe`() =
        assertGoneAfterTheWipe(File(cacheFolder, "courses_$TERM.json")) { cache.saveCourses(listOf(course("XA0000001")), TERM, it) }

    @Test
    fun `an assignment write that passed the check is gone after the wipe`() =
        assertGoneAfterTheWipe(File(cacheFolder, "assignments.json")) { cache.saveAssignments(listOf(assignment("late")), it) }

    @Test
    fun `a Moodle record that passed the check is gone after the wipe`() =
        assertGoneAfterTheWipe(File(cacheFolder, "moodle/$STUDENT/forums.json")) { cache.saveMoodleData(listOf("forum"), 1L, "forums", STUDENT, it) }

    @Test
    fun `a user data write that passed the check is gone after the wipe`() =
        assertGoneAfterTheWipe(File(dataFolder, "ignored_assignments.json")) { cache.saveIgnoredAssignments(setOf("late"), it) }

    /**
     * [write] past its check, its file on disk, and the coroutine parked before it lets a folder's
     * lock go; then a sign-out. The wipe waits for the lock, so the write cannot land after it:
     * whichever way the two fall, the file is gone at the end. A course write has two folders, and
     * may be parked in the second with the first's file already wiped.
     */
    private fun assertGoneAfterTheWipe(file: File, write: suspend (SignIn) -> Unit) {
        val signIn = series.current
        // The legacy file's one-shot check is out of the way, so the write's first hop is its own.
        runBlocking { cache.loadCourses(TERM) }
        val held = Held()
        val writing = CoroutineScope(held).async { write(signIn) }
        held.next()
        held.awaitNext { !writing.isActive }
        assertTrue("the write has not reached the disk", file.exists())

        series.end()
        val wiping = CoroutineScope(Dispatchers.Default).async { cache.clearAllUserData() }
        runBlocking {
            // A folder's thread can finish before the write gets to suspend, and then it is done.
            if (writing.isActive) {
                assertNull("the wipe waits for the write", withTimeoutOrNull(200) { wiping.join() })
            }
            while (writing.isActive) held.next()
            wiping.await()
        }

        assertFalse("${file.name} outlived the wipe", file.exists())
    }

    /**
     * A course an old install keeps in the evictable cache is copied to the durable folder by a
     * read, which holds no sign-in. It does so under the cache's lock, which the wipe takes first,
     * so at no step of the read can a copy be made of what the wipe has since deleted. Parked at
     * each step in turn, with the wipe let run there.
     */
    @Test
    fun `a read copying an old install's hand-added course leaves nothing after a wipe at any step`() {
        var steps = 0
        var finished = false
        while (!finished) {
            val context = JvmContext()
            val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
            val cached = File(context.cacheDir, "TigerDuckCache/courses_$TERM.json")
            cached.writeText("""[{"courseNo":"XM0000013","courseName":"By hand","isManual":true}]""")
            val copy = File(context.filesDir, "TigerDuckData/manual_courses_$TERM.json")

            val held = Held()
            val reading = CoroutineScope(held).async { cache.loadCourses(TERM) }
            repeat(steps) { if (reading.isActive) held.next() }
            finished = !reading.isActive
            val wiping = CoroutineScope(Dispatchers.Default).async { cache.clearAllUserData() }
            runBlocking {
                withTimeoutOrNull(50) { wiping.join() }
                while (reading.isActive) held.next()
                wiping.await()
                reading.join()
            }

            assertFalse("step $steps: the copy outlived the wipe", copy.exists())
            steps++
            assertTrue("the read should end within a few steps", steps < 40)
        }
    }

    @Test
    fun `an old install's hand-added course is still copied to the durable folder`() = runTest {
        File(cacheFolder, "courses_$TERM.json").writeText("""[{"courseNo":"XM0000013","courseName":"By hand","isManual":true}]""")

        assertEquals(listOf("XM0000013"), cache.loadCourses(TERM).map { it.courseNo })

        assertTrue(File(dataFolder, "manual_courses_$TERM.json").readText().contains("XM0000013"))
    }

    /** Runs what is sent to it only when [next] is called, on the caller's thread. */
    private class Held : CoroutineDispatcher() {
        private val sent = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) = sent.put(block)

        fun next() = checkNotNull(sent.poll(10, TimeUnit.SECONDS)) { "nothing came to run" }.run()

        /** Returns once something waits to run, which it leaves waiting, or [done] says nothing will. */
        fun awaitNext(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (sent.isEmpty() && !done()) {
                check(System.nanoTime() < deadline) { "nothing came to run" }
                Thread.sleep(1)
            }
        }
    }

    private companion object {
        const val TERM = "1141"
        const val STUDENT = "B00015999"
    }
}
