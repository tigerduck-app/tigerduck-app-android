package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * What Use Local for courses sends on `/v3`, request by request, through [PushCourseSyncBackend]
 * as Settings builds it: a term's reset, its upload with the custom colors riding it, the deletes
 * of its hidden courses, and its names through the course outbox. Phase 3 gives Use Local a `/v4`
 * form beside this one; on `/v3` none of it may change.
 */
class UseLocalRequestsTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(context)
    private val api = pushApiClientAt(v3, backend.client, prefs)
    private val signIn = testSignIn()
    private val overrides = PendingCourseOverrides(DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs)), api, prefs)

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where Use Local is never asked.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    /** Each request as its method, address and body, a stamp taken from the wall clock read as `<now>`. */
    private fun sent(): List<Triple<String, String, Map<String, Any?>?>> = backend.sent.map { request ->
        val body = request.body?.takeIf { it.isNotEmpty() }?.let { text ->
            val json = JSONObject(text)
            if (json.has("hlc") && request.url.endsWith("/override")) json.put("hlc", "<now>")
            json.asMap()
        }
        Triple(request.method, request.url, body)
    }

    @Suppress("UNCHECKED_CAST")
    private fun JSONObject.asMap(): Map<String, Any?> = plain(this) as Map<String, Any?>

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }

    @Test
    fun `a term is reset, uploaded with its colors, its hidden courses deleted and its names queued, in that order`() = runTest {
        val colored = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1", customColorHex = "#FF0000")
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true, customColorHex = "#00FF00")
        val hidden = Course(courseNo = "CS2", courseName = "CS2")

        keepLocalCourses(
            terms = listOf("1151" to listOf(colored, manual, hidden)),
            hiding = setOf("1151:CS2"),
            backend = PushCourseSyncBackend(api, prefs, overrides, signIn),
            customNames = mapOf("CS1" to mapOf("zh" to "微積分", "en" to "Calc"), "CS2" to mapOf("en" to "Gone")),
        )

        val upload = JSONObject(backend.sent[1].body!!)
        assertEquals(
            listOf(
                Triple("DELETE", "$v3/sync/courses?semester=1151", null),
                // Its body field by field below.
                Triple("POST", "$v3/sync/courses/upload", upload.asMap()),
                Triple("DELETE", "$v3/sync/courses/client:1151:CS2", null),
                Triple("PATCH", "$v3/sync/courses/1151CS1/override", mapOf("custom_name" to "Calc", "locale" to "en", "hlc" to "<now>")),
                Triple("PATCH", "$v3/sync/courses/1151CS1/override", mapOf("custom_name" to "微積分", "locale" to "zh", "hlc" to "<now>")),
            ),
            sent(),
        )
        assertEquals(listOf("CS1", "MY1", "CS2"), (0 until upload.getJSONArray("courses").length()).map { upload.getJSONArray("courses").getJSONObject(it).getString("course_no") })
        assertEquals(
            """[{"course_key":"client:1151:CS1","color_hex":"#FF0000"},{"course_key":"client:1151:MY1","color_hex":"#00FF00"}]""",
            upload.getJSONArray("course_overrides").toString(),
        )
        assertTrue("no adds, no protocol", !upload.has("adds") && backend.sent[1].headers.none { it.first == "X-TigerSync-Protocol" })
    }

    @Test
    fun `a term whose portal courses are not cached is not reset, and its names are not sent`() = runTest {
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)

        keepLocalCourses(
            terms = listOf("1151" to listOf(manual)),
            hiding = setOf("1151:CS2"),
            backend = PushCourseSyncBackend(api, prefs, overrides, signIn),
            customNames = mapOf("MY1" to mapOf("en" to "Mine")),
            portalCached = { false },
        )

        assertEquals(
            listOf("POST", "DELETE"),
            backend.sent.map { it.method },
        )
        assertEquals("$v3/sync/courses/client:1151:CS2", backend.sent[1].url)
    }
}
