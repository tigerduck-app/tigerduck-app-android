@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/** Course ignores over real files, with the backend faked. */
class CourseIgnoreSyncTest {

    private val context = JvmContext()

    private data class Sent(val moodleId: String, val ignore: Boolean?, val moodleCourseId: Int?)

    private lateinit var cache: DataCache
    private lateinit var sync: CourseIgnoreSync

    private val sent = mutableListOf<Sent>()

    // The edit time each PATCH carried as `hlc` (before the clock's microsecond scale).
    private val stamps = mutableListOf<Long?>()
    private var answer: suspend (Sent) -> Boolean? = { true }
    private var sends = true
    private var now = 1_000L
    private var effects = 0

    @Before
    fun setUp() {
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        sync = newProcess()
    }

    /** What a new process builds over the same files. */
    private fun newProcess() = CourseIgnoreSync(
        dataCache = cache,
        sender = CourseIgnoreSender { moodleId, ignore, moodleCourseId, madeAtMs ->
            val request = Sent(moodleId, ignore, moodleCourseId)
            sent += request
            stamps += madeAtMs
            answer(request)
        },
        effects = CourseIgnoreEffects { effects++ },
        sends = { sends },
        clock = { now },
    )

    private suspend fun setIgnored(courseNo: String, ignore: Boolean = true) =
        sync.setIgnored("1141", courseNo, "1141$courseNo", null, ignore)

    private suspend fun waiting() = cache.loadPendingCourseIgnores().map { it.key to it.ignore }

    /** A snapshot whose [ignores] a device set; the courses are AAA and BBB. */
    private fun snapshot(fetchedAtMs: Long, vararg ignores: Pair<String, Boolean>) = BackendSyncResult(
        ignoredIds = emptySet(),
        completedIds = emptySet(),
        courseOverrides = ignores.map { (courseNo, ignore) ->
            CourseOverrideResult(
                moodleCourseId = "1141$courseNo",
                courseNo = courseNo,
                colorHex = null,
                semester = "1141",
                ignoreAssignments = ignore,
                ignoreAssignmentsUpdatedAt = "2026-10-13T00:00:00Z",
            )
        },
        serverCourseNos = setOf("AAA", "BBB"),
        serverCourses = listOf(
            ServerCourse(courseNo = "AAA", courseName = "A", semester = "1141"),
            ServerCourse(courseNo = "BBB", courseName = "B", semester = "1141"),
        ),
        currentRevision = 0,
        fetchedAtMs = fetchedAtMs,
    )

    @Test
    fun `a change the backend confirms is not kept waiting`() = runTest {
        setIgnored("AAA")

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
        assertEquals("reminders and the Live Update are refreshed", 1, effects)
    }

    @Test
    fun `a change sent after a newer one carries the time it was made`() = runTest {
        sends = false
        now = 2_000L
        setIgnored("AAA")
        now = 3_000L
        setIgnored("BBB")
        assertEquals(emptyList<Long?>(), stamps)

        sends = true
        sync.retryPending()

        // The older sends first here, but each carries its own time, not the time of the retry.
        assertEquals(listOf<Long?>(2_000L, 3_000L), stamps)
        now = 9_000L
        sends = false
        setIgnored("AAA", ignore = false)
        now = 9_500L
        sends = true
        stamps.clear()
        sync.retryPending()
        assertEquals(listOf<Long?>(9_000L), stamps)
    }

    @Test
    fun `a change stored by the build before has no time, and goes out without one`() = runTest {
        File(context.filesDir, "TigerDuckData/pending_course_ignores.json").writeText(
            """[{"semester":"1141","courseNo":"AAA","moodleId":"1141AAA","ignore":true,"changedAtMs":1000,"attempts":0}]""",
        )
        assertNull(cache.loadPendingCourseIgnores().single().madeAtMs)

        sync.retryPending()

        assertEquals(listOf<Long?>(null), stamps)
        assertEquals(1, sent.size)
    }

    @Test
    fun `the write is reported before the backend is asked`() = runTest {
        val onTheWire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = {
            onTheWire.complete(Unit)
            release.await()
            true
        }
        var written = false

        val tap = launch { sync.setIgnored("1141", "AAA", "1141AAA", null, true) { written = true } }
        onTheWire.await()

        assertTrue("reported while the PATCH is still out", written)
        release.complete(Unit)
        tap.join()
    }

    @Test
    fun `a change that fails waits and goes out first on the next sync`() = runTest {
        answer = { throw java.io.IOException("offline") }
        setIgnored("AAA")
        assertEquals(listOf("1141:AAA" to true), waiting())

        answer = { true }
        sync.retryPending()

        assertEquals(2, sent.size)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `an answer without the field, from an older backend, keeps the change waiting`() = runTest {
        answer = { false }

        setIgnored("AAA")

        assertEquals(listOf("1141:AAA" to true), waiting())
    }

    @Test
    fun `a change made while the last one is on the wire outlives its confirmation`() = runTest {
        val onTheWire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { request ->
            if (request.ignore == true) {
                onTheWire.complete(Unit)
                release.await()
                true
            } else {
                false
            }
        }
        val first = launch { setIgnored("AAA", ignore = true) }
        onTheWire.await()

        now = 2_000L
        val second = launch { setIgnored("AAA", ignore = false) }
        testScheduler.advanceUntilIdle()
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(listOf("1141:AAA" to false), waiting())
    }

    @Test
    fun `with assignment sync off a change waits unsent until it is on`() = runTest {
        sends = false
        setIgnored("AAA")
        sync.retryPending()
        assertEquals(emptyList<Sent>(), sent)
        assertEquals(listOf("1141:AAA" to true), waiting())

        sends = true
        sync.retryPending()

        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a later change replaces an earlier one still waiting`() = runTest {
        sends = false
        setIgnored("AAA", ignore = true)
        now = 2_000L
        setIgnored("AAA", ignore = false)

        sends = true
        sync.retryPending()

        assertEquals(listOf(Sent("1141AAA", false, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a change made on the wire waits for the one before it`() = runTest {
        val onTheWire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { request ->
            if (request.ignore == true) {
                onTheWire.complete(Unit)
                release.await()
            }
            true
        }
        val first = launch { setIgnored("AAA", ignore = true) }
        onTheWire.await()

        now = 2_000L
        val second = launch { setIgnored("AAA", ignore = false) }
        testScheduler.advanceUntilIdle()
        assertEquals("the second PATCH went out before the first came back", 1, sent.size)

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(true, false), sent.map { it.ignore })
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a waiting change replaced during a retry is not sent`() = runTest {
        answer = { false }
        setIgnored("AAA", ignore = true)
        setIgnored("BBB", ignore = true)
        sent.clear()
        val onTheWire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { request ->
            if (request.moodleId == "1141AAA" && request.ignore == true) {
                onTheWire.complete(Unit)
                release.await()
            }
            true
        }
        val retry = launch { sync.retryPending() }
        onTheWire.await()

        // BBB's old change is still in the retry's list when this replaces it.
        now = 2_000L
        val toggle = launch { setIgnored("BBB", ignore = false) }
        release.complete(Unit)
        retry.join()
        toggle.join()

        assertEquals(
            listOf(Sent("1141AAA", true, null), Sent("1141BBB", false, null)),
            sent,
        )
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `with cloud sync off a change is kept for when it is back on`() = runTest {
        sends = false

        setIgnored("AAA")

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf("1141:AAA" to true), waiting())
    }

    @Test
    fun `a sync begun before sign-out writes nothing over the next account`() = runTest {
        val generation = sync.currentGeneration()
        cache.clearAllUserData()

        val applied = sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "BBB" to true), generation)

        assertNull(applied)
        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
    }

    @Test
    fun `a retry stops sending once its account has signed out`() = runTest {
        sends = false
        setIgnored("AAA")
        now = 2_000L
        setIgnored("BBB")
        val onTheWire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = {
            onTheWire.complete(Unit)
            release.await()
            true
        }
        sends = true
        val retry = launch { sync.retryPending() }
        onTheWire.await()

        cache.clearAllUserData()
        release.complete(Unit)
        retry.join()

        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a snapshot fetched before a change does not undo it`() = runTest {
        setIgnored("AAA")

        val applied = sync.applySnapshot(snapshot(fetchedAtMs = 500, "AAA" to false), sync.currentGeneration())

        assertNull(applied)
        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
    }

    @Test
    fun `a later snapshot is taken as the backend has it`() = runTest {
        setIgnored("AAA")
        val effectsBefore = effects

        val applied = sync.applySnapshot(
            snapshot(fetchedAtMs = 2_000, "AAA" to false, "BBB" to true),
            sync.currentGeneration(),
        )

        assertEquals(setOf("1141:BBB"), applied)
        assertEquals(setOf("1141:BBB"), cache.loadIgnoredCourses())
        assertEquals(effectsBefore + 1, effects)
    }

    @Test
    fun `a waiting change keeps its value through any snapshot`() = runTest {
        answer = { false }
        setIgnored("AAA")

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "AAA" to false), sync.currentGeneration())

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
    }

    @Test
    fun `deleting a course forgets its setting and its waiting change`() = runTest {
        answer = { false }
        setIgnored("AAA")

        sync.forget { it == "1141:AAA" }

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `taking the backend's set drops what was waiting`() = runTest {
        answer = { false }
        setIgnored("AAA")

        sync.adoptServer(snapshot(fetchedAtMs = 5_000, "BBB" to true), sync.currentGeneration())

        assertEquals(setOf("1141:BBB"), cache.loadIgnoredCourses())
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `taking the backend's set without a snapshot drops this device's, so none of it goes up again`() = runTest {
        answer = { false }
        setIgnored("AAA")
        sent.clear()

        sync.adoptServer(null, sync.currentGeneration())
        sync.applySnapshot(snapshot(fetchedAtMs = 5_000), sync.currentGeneration())

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(emptyList<Sent>(), sent)
    }

    @Test
    fun `a snapshot fetched before the user took the backend's set does not undo it`() = runTest {
        // Fetched while the re-enable choice was open, and answered after it.
        val older = snapshot(fetchedAtMs = 1_500, "AAA" to true)
        now = 2_000L
        sync.adoptServer(snapshot(fetchedAtMs = 1_800, "AAA" to false), sync.currentGeneration())

        sync.applySnapshot(older, sync.currentGeneration())
        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())

        sync.applySnapshot(snapshot(fetchedAtMs = 2_500, "AAA" to true), sync.currentGeneration())
        assertEquals("a later one is taken", setOf("1141:AAA"), cache.loadIgnoredCourses())
    }

    @Test
    fun `a snapshot fetched before the user kept this device's set does not undo it`() = runTest {
        val older = snapshot(fetchedAtMs = 1_500, "BBB" to true)
        now = 2_000L
        sync.pushLocal(snapshot(fetchedAtMs = 1_800, "BBB" to false), sync.currentGeneration())

        sync.applySnapshot(older, sync.currentGeneration())

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
    }

    @Test
    fun `a snapshot fetched before a course was deleted does not bring its setting back`() = runTest {
        setIgnored("AAA")
        now = 2_000L
        sync.forget { it == "1141:AAA" }

        sync.applySnapshot(snapshot(fetchedAtMs = 1_500, "AAA" to true), sync.currentGeneration())

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
    }

    @Test
    fun `a course another device added back and ignores takes its setting in that same sync`() = runTest {
        cache.saveDeletedCourseNos(setOf(CourseTombstoneKeys.key("1141", "BBB")))

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "BBB" to true), sync.currentGeneration())

        assertEquals(setOf("1141:BBB"), cache.loadIgnoredCourses())
    }

    @Test
    fun `a term reset after the snapshot was fetched is left out of it`() = runTest {
        cache.saveSemesterResetAt("1141", 6_000)

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "AAA" to true), sync.currentGeneration())

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
    }

    @Test
    fun `keeping this device's set sends it over the backend's`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:AAA"))

        sync.pushLocal(snapshot(fetchedAtMs = 5_000, "BBB" to true), sync.currentGeneration())

        assertEquals(
            setOf(Sent("1141AAA", true, null), Sent("1141BBB", false, null)),
            sent.toSet(),
        )
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a setting the backend lost with its course rows stays and is sent again`() = runTest {
        setIgnored("AAA")
        now = 2_000L

        // Course sync was off on every device: the rows came back without their overrides.
        val applied = sync.applySnapshot(snapshot(fetchedAtMs = 3_000), sync.currentGeneration())

        assertNull(applied)
        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf(Sent("1141AAA", true, null), Sent("1141AAA", true, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `changes go out and a sync takes the backend's set only with course sync on too`() = runTest {
        val prefs = AppPreferences(context)
        prefs.syncAssignments = true
        prefs.syncCourses = true
        // Off on fdroid whatever is stored, which leaves nothing to sync either.
        val synced = prefs.cloudSyncEnabled

        assertEquals(synced, CourseIgnoreSync.syncsCourseIgnores(prefs))
        prefs.syncCourses = false
        assertFalse(CourseIgnoreSync.syncsCourseIgnores(prefs))
    }

    @Test
    fun `a change the backend keeps refusing is parked out of the retries, and keeps its value`() = runTest {
        answer = { throw PushApiException("not found", 404) }
        setIgnored("AAA")
        repeat(CourseIgnoreSync.MAX_ATTEMPTS - 1) { sync.retryPending() }
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS, sent.size)

        sync.retryPending()
        assertEquals("a parked change went out again", CourseIgnoreSync.MAX_ATTEMPTS, sent.size)
        assertEquals(listOf("1141:AAA" to true), waiting())

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "AAA" to false), sync.currentGeneration())
        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
    }

    @Test
    fun `the next process tries a parked change once more`() = runTest {
        answer = { false }
        setIgnored("AAA")
        repeat(CourseIgnoreSync.MAX_ATTEMPTS - 1) { sync.retryPending() }

        val next = newProcess()
        next.retryPending()
        next.retryPending()
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS + 1, sent.size)

        answer = { true }
        newProcess().retryPending()
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS + 2, sent.size)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `no network, no session or one to renew, or a server that is down is not a refusal`() = runTest {
        answer = { throw java.io.IOException("offline") }
        setIgnored("AAA")
        repeat(CourseIgnoreSync.MAX_ATTEMPTS + 1) { sync.retryPending() }

        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS + 2, sent.size)
        assertFalse(CourseIgnoreSync.refuses(PushApiException("unauthorized", 401)))
        assertFalse(CourseIgnoreSync.refuses(NotSignedInException()))
        assertFalse(CourseIgnoreSync.refuses(PushApiException("unavailable", 503)))
        assertTrue(CourseIgnoreSync.refuses(PushApiException("not found", 404)))
        assertTrue(CourseIgnoreSync.refuses(IllegalStateException("bad body")))
    }

    @Test
    fun `a backend row without the numeric Moodle id gets it from this device`() = runTest {
        cache.saveCourses(
            listOf(
                Course(
                    courseNo = "AAA",
                    courseName = "A",
                    moodleIdNumber = "1141AAA",
                    moodleNumericCourseId = 77,
                )
            ),
            "1141",
        )

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "AAA" to true), sync.currentGeneration())

        assertEquals(listOf(Sent("1141AAA", null, 77)), sent)
    }

    @Test
    fun `Use Local sends the ignores of a term it reset after the check's snapshot`() = runTest {
        // Use Local reset and re-uploaded 1141, then could not read the
        // server again and fell back to the check's older snapshot, which
        // would leave 1141 out as a term reset after it.
        cache.saveIgnoredCourses(setOf("1141:AAA"))
        cache.saveSemesterResetAt("1141", 2_000L)

        sync.pushLocal(snapshot(fetchedAtMs = 1_000L), sync.currentGeneration())

        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
    }

    // ---- Use Local's clears in the terms it reset (tigerduck-app-android#307) ----

    private suspend fun cacheTerm(vararg courseNos: String) =
        cache.saveCourses(courseNos.map { Course(courseNo = it, courseName = it, moodleIdNumber = "1141$it") }, "1141")

    @Test
    fun `Use Local clears a course only the check's snapshot ignored in a term it reset`() = runTest {
        // The check saw BBB ignored on the server. Use Local reset 1141, which
        // took that override with it, and the server read after it has none:
        // without a clear, another device's resend would bring it back.
        cacheTerm("AAA", "BBB")
        cache.saveSemesterResetAt("1141", 2_000L)

        sync.pushLocal(
            snapshot(fetchedAtMs = 3_000L),
            sync.currentGeneration(),
            resetTerms = setOf("1141"),
            checked = snapshot(fetchedAtMs = 1_000L, "BBB" to true),
        )

        assertEquals(listOf(Sent("1141BBB", false, null)), sent)
    }

    @Test
    fun `the clear goes out too when Use Local falls back to the check's snapshot`() = runTest {
        cacheTerm("AAA", "BBB")
        cache.saveSemesterResetAt("1141", 2_000L)
        val checked = snapshot(fetchedAtMs = 1_000L, "BBB" to true)

        sync.pushLocal(checked, sync.currentGeneration(), resetTerms = setOf("1141"), checked = checked)

        assertEquals(listOf(Sent("1141BBB", false, null)), sent)
    }

    @Test
    fun `no clear for a course the reset term no longer has on the server, or that this phone ignores`() = runTest {
        // AAA is ignored here and goes up as ignored; BBB was not put back by
        // the upload, so there is no row to clear.
        cacheTerm("AAA")
        cache.saveIgnoredCourses(setOf("1141:AAA"))
        cache.saveSemesterResetAt("1141", 2_000L)

        sync.pushLocal(
            snapshot(fetchedAtMs = 3_000L),
            sync.currentGeneration(),
            resetTerms = setOf("1141"),
            checked = snapshot(fetchedAtMs = 1_000L, "AAA" to true, "BBB" to true),
        )

        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
    }

    // ---- a course another device deleted and added back (tigerduck-app-android#182) ----

    /** A snapshot listing AAA and BBB under the row ids given, with no settings. */
    private fun rows(fetchedAtMs: Long, aaa: Long, bbb: Long) = snapshot(fetchedAtMs).copy(
        serverCourses = listOf(
            ServerCourse(courseNo = "AAA", courseName = "A", semester = "1141", id = aaa),
            ServerCourse(courseNo = "BBB", courseName = "B", semester = "1141", id = bbb),
        ),
    )

    @Test
    fun `a course re-added elsewhere takes the server's setting, not this device's old one`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:AAA"))
        sync.applySnapshot(snapshot(fetchedAtMs = 1_000L, "AAA" to true).copy(serverCourses = rows(0, 1, 2).serverCourses), sync.currentGeneration())
        sent.clear()

        // Another device deleted AAA and added it back: a new row, no setting.
        val applied = sync.applySnapshot(rows(fetchedAtMs = 2_000L, aaa = 9, bbb = 2), sync.currentGeneration())

        assertEquals(emptySet<String>(), applied)
        assertEquals(emptyList<Sent>(), sent)
    }

    @Test
    fun `settings the backend dropped with every row still go up again`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:AAA"))
        sync.applySnapshot(snapshot(fetchedAtMs = 1_000L, "AAA" to true).copy(serverCourses = rows(0, 1, 2).serverCourses), sync.currentGeneration())
        sent.clear()

        // No row kept its id: the backend dropped them all while no device synced courses.
        sync.applySnapshot(rows(fetchedAtMs = 2_000L, aaa = 9, bbb = 10), sync.currentGeneration())

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
    }

    @Test
    fun `a term this device reset is not read as re-added elsewhere`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:AAA"))
        val other = ServerCourse(courseNo = "CCC", courseName = "C", semester = "1142", id = 3)
        sync.applySnapshot(
            snapshot(fetchedAtMs = 1_000L, "AAA" to true).copy(serverCourses = rows(0, 1, 2).serverCourses + other),
            sync.currentGeneration(),
        )
        sent.clear()

        // Use Local reset 1141 here; 1142 kept its row.
        sync.forgetRowsSeen(setOf("1141"))
        sync.applySnapshot(
            rows(fetchedAtMs = 2_000L, aaa = 9, bbb = 10).let { it.copy(serverCourses = it.serverCourses + other) },
            sync.currentGeneration(),
        )

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
    }

    @Test
    fun `a term another device reset with Use Local keeps this device's ignores there and sends them again`() = runTest {
        // An iPhone with assignment sync off, which lacks AAA's ignore, used
        // Use Local: it reset 1141, so every 1141 row is new and has no
        // setting, while 1142 kept its row. Its upload put nothing back for
        // AAA's setting, so dropping it here would lose it for good.
        cache.saveIgnoredCourses(setOf("1141:AAA"))
        val other = ServerCourse(courseNo = "CCC", courseName = "C", semester = "1142", id = 3)
        sync.applySnapshot(
            snapshot(fetchedAtMs = 1_000L, "AAA" to true).copy(serverCourses = rows(0, 1, 2).serverCourses + other),
            sync.currentGeneration(),
        )
        sent.clear()

        sync.applySnapshot(
            rows(fetchedAtMs = 2_000L, aaa = 9, bbb = 10).let { it.copy(serverCourses = it.serverCourses + other) },
            sync.currentGeneration(),
        )

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
    }
}
