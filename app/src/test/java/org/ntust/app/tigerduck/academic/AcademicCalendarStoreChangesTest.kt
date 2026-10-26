package org.ntust.app.tigerduck.academic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.util.concurrent.atomic.AtomicInteger
import org.ntust.app.tigerduck.util.JvmContext
import android.content.Context

/**
 * [AcademicCalendarStore.changes] is what sends the paired watch a new list
 * of quiet days, so it has to fire for each input of that list and stay
 * quiet when nothing changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcademicCalendarStoreChangesTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = JvmContext()
        prefs = AppPreferences(context)
    }

    private fun store(client: OkHttpClient = OkHttpClient()) =
        AcademicCalendarStore(context, client, prefs)

    /** Answers every request with [body] as a fresh 200, never a 304. */
    private fun serving(body: () -> String) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body().toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun calendarJson(holidayEnd: String) = """
        {"revision":1,"semesters":[],"holidays":[
          {"id":7,"name_zh":"National Day","name_en":"National Day",
           "start":"2026-10-09","end":"$holidayEnd"}]}
    """.trimIndent()

    private fun TestScope.countChanges(store: AcademicCalendarStore): AtomicInteger {
        val count = AtomicInteger()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.changes.collect { count.incrementAndGet() }
        }
        return count
    }

    @Test
    fun `flipping a holiday's switch is a change, setting it again is not`() = runTest {
        val store = store()
        val changes = countChanges(store)
        store.setNotifyOnHoliday(7, notify = true)
        store.setNotifyOnHoliday(7, notify = true)
        assertEquals(1, changes.get())
        store.setNotifyOnHoliday(7, notify = false)
        assertEquals(2, changes.get())
    }

    @Test
    fun `synced opt-ins are a change only when they differ`() = runTest {
        val store = store()
        val changes = countChanges(store)
        store.applySyncedOverrides(setOf(7))
        store.applySyncedOverrides(setOf(7))
        assertEquals(1, changes.get())
    }

    @Test
    fun `a refresh is a change only when the calendar differs`() = runTest {
        var body = calendarJson(holidayEnd = "2026-10-10")
        val store = store(serving { body })
        val changes = countChanges(store)
        store.refresh()
        store.refresh()
        assertEquals(1, changes.get())
        body = calendarJson(holidayEnd = "2026-10-11")
        store.refresh()
        assertEquals(2, changes.get())
    }
}
