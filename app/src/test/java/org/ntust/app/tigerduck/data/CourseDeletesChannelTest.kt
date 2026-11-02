package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The course deletes as an outbox channel ([PendingCourseDeletes]), over real files and the real
 * backend client, against a backend that records what reaches it.
 *
 * On `/v3` every request is pinned byte for byte as the queue sent it before it was a channel: its
 * method, address, headers and body, and what each answer leaves queued.
 */
class CourseDeletesChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val typed = "MA1010002"
    private val other = "PE1010003"
    private val bearer = "Authorization" to "Bearer access"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    private fun v3Deletes(): PendingCourseDeletes {
        val client = pushApiClientAt(v3, backend.client, prefs)
        return PendingCourseDeletes(cache, PendingCourseAdds(cache, client), client)
    }

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private suspend fun queued() = cache.loadPendingCourseDeletes()

    /**
     * Each delete goes with the moment it was asked, and a retry goes on past one that fails: the
     * failed one waits for the next sync, the rest go.
     */
    @Test
    fun `on v3 a delete goes with its moment, and a retry goes on past one that fails`() = runTest {
        val deletes = v3Deletes()
        backend.answer("/v3/sync/courses/client:$term:$typed", 500, "{}")
        deletes.send(term, typed, 1_790_000_000_123L, signIn)
        deletes.keep(term, other, 1_790_000_000_456L, signIn)
        backend.answer("/v3/sync/courses/client:$term:$typed", 500, "{}")

        deletes.retry(signIn)

        assertEquals(
            listOf(
                listOf("DELETE", "$v3/sync/courses/client:$term:$typed?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses/client:$term:$typed?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses/client:$term:$other?hlc=1790000000456000", listOf(bearer), null, ""),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(mapOf(term to mapOf(typed to 1_790_000_000_123L)), queued())
    }

    /** Refused as ahead of the backend's clock, a delete goes once more without its moment. */
    @Test
    fun `on v3 a delete refused as ahead goes again unstamped`() = runTest {
        backend.answer("/v3/sync/courses/client:$term:$typed", 409, """{"detail":"clock_ahead"}""")

        v3Deletes().send(term, typed, 1_790_000_000_123L, signIn)

        assertEquals(
            listOf(
                listOf("DELETE", "$v3/sync/courses/client:$term:$typed?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses/client:$term:$typed", listOf(bearer), null, ""),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /** Nothing goes while course sync is off, and the delete is let go: the re-enable check answers for it. */
    @Test
    fun `on v3 a delete made with course sync off sends nothing and is let go`() = runTest {
        prefs.syncCourses = false

        v3Deletes().send(term, typed, 1_790_000_000_123L, signIn)

        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /** A refusal the backend gives, as one that names no course, waits for the next sync, as a failure does. */
    @Test
    fun `on v3 a refused delete is kept and sent again at the next retry`() = runTest {
        val deletes = v3Deletes()
        backend.answer("/v3/sync/courses/client:$term:$typed", 404, """{"detail":"Course not found"}""")
        deletes.send(term, typed, 1_790_000_000_123L, signIn)
        assertEquals(mapOf(term to mapOf(typed to 1_790_000_000_123L)), queued())

        deletes.retry(signIn)

        assertEquals(2, backend.sent.size)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * Runs the test dispatcher until [done]. A launched coroutine's file reads and writes run on
     * the IO dispatcher and resume into the test dispatcher on their own time, which
     * `runCurrent()` alone does not wait for; each [done] is itself a real read.
     */
    private suspend fun TestScope.settle(done: suspend () -> Boolean) {
        repeat(500) {
            if (done()) return
            runCurrent()
            yield()
        }
        throw AssertionError("the queue did not settle")
    }

    /** A delete is never given up on for its refusals: every sync sends it again, as before it was a channel. */
    @Test
    fun `on v3 a delete refused time after time still goes at every sync`() = runTest {
        var refusals = 0
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(cache, adds, { _, _, _ ->
            if (refusals++ < 6) throw PushApiException("deleteCourse failed: HTTP 422", 422)
        })
        deletes.send(term, typed, 5_000L, signIn)

        repeat(6) { deletes.retry(signIn) }

        assertEquals(7, refusals)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * The course removed again while its first DELETE is out: the second goes at its own turn of
     * the send lock, after an upload that was waiting for the lock before it, as it always went.
     */
    @Test
    fun `on v3 a removal made while its first DELETE is out goes at its own turn of the send lock`() = runTest {
        val events = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate: suspend () -> Unit = {}
        gate = {
            gate = {}
            out.complete(Unit)
            release.await()
        }
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(cache, adds, { _, _, hiddenAt ->
            gate()
            events += "DELETE $hiddenAt"
        })

        val first = launch { deletes.send(term, typed, 10L, signIn) }
        out.await()
        val upload = launch { adds.uploading { events += "upload" } }
        runCurrent()
        val again = launch { deletes.send(term, typed, 20L, signIn) }
        settle { queued()[term]?.get(typed) == 20L }
        release.complete(Unit)
        first.join()
        upload.join()
        again.join()

        assertEquals(listOf("DELETE 10", "upload", "DELETE 20"), events)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }
}
