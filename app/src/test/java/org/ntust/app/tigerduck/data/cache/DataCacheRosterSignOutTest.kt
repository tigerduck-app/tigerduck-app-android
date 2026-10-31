package org.ntust.app.tigerduck.data.cache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * A roster recorded for the account signing out, read before the sign-out's wipe and written
 * after it, would hand the next account the last one's drops, which keep its own courses off the
 * timetable. Made-up course numbers.
 */
class DataCacheRosterSignOutTest {

    private val context = JvmContext()
    private lateinit var cache: DataCache

    private val timed = Course.fromSchedule("XA0000001", "Timed", schedule = mapOf(2 to listOf("3")))
    private val junk = Course.fromSchedule("XC0000003", "Junk", moodleIdNumber = "${TERM}XC0000003")

    @Before
    fun setUp() {
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    @Test
    fun `a course selection answer recorded across a sign-out leaves the next account no drops or rosters`() {
        runBlocking {
            cache.saveCourses(listOf(timed, junk), TERM)
            cache.recordSelectionRoster(TERM, listOf("XA0000001"))
        }

        recordAcross(clear = { cache.clearAllUserData() }) {
            cache.recordSelectionRoster(TERM, listOf("XA0000001", "XD0000004"))
        }

        assertNothingLeft()
    }

    @Test
    fun `a rebuild's drops recorded across a sign-out are not the next account's`() {
        runBlocking {
            cache.saveCourses(listOf(timed, junk), TERM)
            cache.saveSelectionDroppedNos(mapOf(TERM to listOf("XD0000004")))
        }

        recordAcross(clear = { cache.clearAllUserData() }) {
            cache.recordFinalRoster(TERM, roster = listOf("XA0000001"), alsoListed = listOf("XN0000015"))
        }

        assertNothingLeft()
    }

    @Test
    fun `a rebuild's drops recorded across a full reset are not kept`() {
        runBlocking {
            cache.saveCourses(listOf(timed, junk), TERM)
            cache.saveSelectionDroppedNos(mapOf(TERM to listOf("XD0000004")))
        }

        recordAcross(clear = { cache.clearEverything() }) {
            cache.recordFinalRoster(TERM, roster = listOf("XA0000001"), alsoListed = listOf("XN0000015"))
        }

        assertNothingLeft()
    }

    private fun assertNothingLeft() {
        assertEquals(emptyMap<String, List<String>>(), runBlocking { cache.loadSelectionDroppedNos() })
        assertFalse(File(context.filesDir, "TigerDuckData/selection_rosters.json").exists())
    }

    /**
     * [record] up to its first read, which it holds; then [clear], as far as it gets meanwhile;
     * then the rest of [record], and of [clear] after it.
     */
    private fun recordAcross(clear: suspend () -> Unit, record: suspend () -> Unit) {
        val held = Held()
        val recording = CoroutineScope(held).async { record() }
        held.next()
        val clearing = CoroutineScope(Dispatchers.Default).async { clear() }
        runBlocking { withTimeoutOrNull(200) { clearing.join() } }
        while (recording.isActive) held.next()
        runBlocking {
            recording.await()
            clearing.await()
        }
    }

    /** Runs what is sent to it only when [next] is called, on the caller's thread. */
    private class Held : CoroutineDispatcher() {
        private val sent = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) = sent.put(block)

        fun next() = checkNotNull(sent.poll(10, TimeUnit.SECONDS)) { "nothing came to run" }.run()
    }

    private companion object {
        const val TERM = "1141"
    }
}
