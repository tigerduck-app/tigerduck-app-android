package org.ntust.app.tigerduck.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.reconcile.Reconciler
import java.io.IOException
import java.util.Collections

/**
 * The full sync's steps in the order they always ran in Home's pull, recorded by fake steps: the
 * retries, the fetch inside the course window, the adds the snapshot lists, the one reading of
 * the policy, each reconciler, then what the pass took, the watermark, the first-pull gate, the
 * status and the steps after it. Swapping any two fails.
 */
class SynchronizerOrderTest {

    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val school = SignInSeries()
    private val policy = SyncPolicy(
        cloudSync = true, flavorSyncs = true, switchedOn = SyncCategory.entries.toSet(), held = emptySet(), lastWriterWins = false,
    )
    private var result = BackendSyncResult(
        ignoredIds = emptySet(), completedIds = emptySet(), courseOverrides = emptyList(), serverCourseNos = emptySet(), currentRevision = 1,
    )

    /** What the fetch does instead of answering, when set. */
    private var fetchThrows: Exception? = null
    private var mayRun = true
    private var recorded: Set<String>? = null

    private inner class RecordingSteps : PassSteps {
        override suspend fun mayRun() = mayRun.also { log += "may run" }
        override fun beginCourseSync() = DataCache.CourseSync(0L) {
            log += "fetching"
            0L
        }.also { log += "course window opens" }
        override fun endCourseSync(courseSync: DataCache.CourseSync) {
            log += "course window closes"
        }
        override suspend fun retryUnsent(signIn: SignIn) {
            log += "retries"
        }
        override suspend fun stampDeletes() = 7L.also { log += "deletes stamped" }
        override suspend fun fetch(signIn: SignIn): BackendSyncResult {
            log += "fetch"
            fetchThrows?.let { throw it }
            return result
        }
        override suspend fun listed(result: BackendSyncResult, signIn: SignIn) {
            log += "adds listed"
        }
        override fun policy() = policy.also { log += "policy read" }
        override fun recordTaken(pass: SyncPass, took: Set<String>) {
            log += "categories taken"
            recorded = took
        }
        override fun noteRevision(pass: SyncPass) {
            log += "watermark"
        }
        override fun notePull(pass: SyncPass) {
            log += "gate opens"
        }
        override fun synced() {
            log += "status"
        }
        override fun afterSync(pass: SyncPass) {
            log += "after-steps"
        }
        override fun failed() {
            log += "failed"
        }
    }

    private fun step(name: String, took: Boolean = true) = object : Reconciler {
        override suspend fun reconcile(pass: SyncPass): Boolean {
            log += name
            assertEquals("every step reads the one policy", policy, pass.policy)
            assertEquals(7L, pass.beforeFetch)
            return took
        }
    }

    private fun synchronizer(
        marks: Boolean = true,
        overrides: Boolean = true,
        roster: Boolean = true,
    ) = Synchronizer(
        RecordingSteps(),
        marks = step("marks", marks),
        ignores = step("ignores"),
        overrides = step("overrides", overrides),
        reset = step("course reset"),
        holidays = step("holidays"),
        roster = step("course list and roster requests", roster),
    )

    @Test
    fun `the steps run in the pinned order`() = runBlocking {
        val outcome = synchronizer().sync(SyncMode.FOREGROUND, school.current)

        assertEquals(SyncOutcome.SYNCED, outcome)
        assertEquals(
            listOf(
                "may run", "course window opens", "retries", "deletes stamped", "fetching", "fetch", "adds listed", "policy read",
                "marks", "ignores", "overrides", "course reset", "holidays", "course list and roster requests",
                "categories taken", "watermark", "gate opens", "status", "after-steps", "course window closes",
            ),
            log.toList(),
        )
    }

    @Test
    fun `a pass that may not run does nothing else`() = runBlocking {
        mayRun = false

        assertEquals(SyncOutcome.SKIPPED, synchronizer().sync(SyncMode.FOREGROUND, school.current))
        assertEquals(listOf("may run"), log.toList())
    }

    @Test
    fun `a pass that fails says so, then runs its caller's failure step, inside its course window`() = runBlocking {
        fetchThrows = IOException("made-up failure")
        var failure: Exception? = null

        val outcome = synchronizer().sync(SyncMode.FOREGROUND, school.current) { e ->
            failure = e
            log += "caller's failure step"
        }

        assertEquals(SyncOutcome.FAILED, outcome)
        assertEquals(fetchThrows, failure)
        assertEquals(
            listOf(
                "may run", "course window opens", "retries", "deletes stamped", "fetching", "fetch",
                "failed", "caller's failure step", "course window closes",
            ),
            log.toList(),
        )
    }

    @Test
    fun `a pass that fails past its sign-in says nothing of the backend`() = runBlocking {
        val signIn = school.current
        fetchThrows = IOException("made-up failure")
        school.end()

        val outcome = synchronizer().sync(SyncMode.FOREGROUND, signIn) { log += "caller's failure step" }

        assertEquals(SyncOutcome.SKIPPED, outcome)
        assertEquals(
            listOf("may run", "course window opens", "retries", "deletes stamped", "fetching", "fetch", "course window closes"),
            log.toList(),
        )
    }

    @Test
    fun `a cancelled pass is no failure, and still closes its course window`() = runBlocking {
        fetchThrows = CancellationException("left Home")

        try {
            synchronizer().sync(SyncMode.FOREGROUND, school.current) { log += "caller's failure step" }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("left Home", e.message)
        }
        assertEquals("course window closes", log.last())
        assertTrue("reported as a failure", "failed" !in log && "caller's failure step" !in log)
    }

    @Test
    fun `the pass records the categories its steps took`() = runBlocking {
        synchronizer(marks = true, roster = false, overrides = false).sync(SyncMode.FOREGROUND, school.current)
        assertEquals("no colors to take, so none waited for a course list", setOf("assignments", "course_colors", "course_names"), recorded)

        result = result.copy(courseOverrides = listOf(CourseOverrideResult(moodleCourseId = "1141CS1001", courseNo = "CS1001", colorHex = "#123456")))
        synchronizer(marks = false, roster = true, overrides = false).sync(SyncMode.FOREGROUND, school.current)
        assertEquals("colors with no course list to land on are not taken", setOf("courses"), recorded)

        synchronizer(marks = false, roster = false, overrides = true).sync(SyncMode.FOREGROUND, school.current)
        assertEquals(setOf("course_colors", "course_names"), recorded)
    }
}
