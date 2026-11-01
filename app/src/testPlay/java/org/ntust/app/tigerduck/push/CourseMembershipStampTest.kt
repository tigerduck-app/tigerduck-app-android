package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A course hide or add that waited on this phone goes with the time it was made (#591), which
 * the backend applies only when later than the course's last add, hide and reset, as on the
 * iPhone since tigerduck-app#910. One the backend refuses as ahead of its clock goes again
 * without it. Play only: an F-Droid build has no TigerSync, and sends none of these.
 */
class CourseMembershipStampTest {

    private val backend = ScriptedBackend()
    private lateinit var api: PushApiClient

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply {
            announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
            cloudSyncEnabled = true
            syncCourses = true
        }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-stamp-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
    }

    // Hides

    @Test
    fun `a queued hide goes with its time, in microseconds, and no protocol header`() = runTest {
        api.deleteCourse(KEY, hiddenAt = AT)

        val sent = backend.received(DELETE).single()
        assertEquals("hlc=$HLC", sent.query)
        assertNull(sent.protocol)
    }

    @Test
    fun `a hide refused as ahead of the clock goes again without its time`() = runTest {
        backend.answer(DELETE, 409, """{"detail":"clock_ahead"}""")

        api.deleteCourse(KEY, hiddenAt = AT)

        assertEquals(listOf("hlc=$HLC", ""), backend.received(DELETE).map { it.query })
    }

    @Test
    fun `a hide refused with another 409 fails and is not sent again`() = runTest {
        backend.answer(DELETE, 409, """{"detail":"conflict"}""")

        assertTrue(runCatching { api.deleteCourse(KEY, hiddenAt = AT) }.isFailure)

        assertEquals(1, backend.received(DELETE).size)
    }

    // Adds

    @Test
    fun `a queued add goes alone under protocol 2 with its time`() = runTest {
        val accepted = api.uploadCourses(listOf(course()), TERM, forceKeys = listOf(KEY), addedAt = mapOf(NO to AT))

        val sent = backend.received(UPLOAD).single()
        assertEquals("2", sent.protocol)
        val body = JSONObject(sent.body)
        assertEquals(1, body.getJSONArray("courses").length())
        val add = body.getJSONArray("adds").getJSONObject(0)
        assertEquals(KEY, add.getString("course_key"))
        assertEquals(HLC, add.getLong("hlc"))
        assertEquals(KEY, body.getJSONArray("force_keys").getString(0))
        assertEquals(emptyList<String>(), accepted)
    }

    @Test
    fun `an add refused as ahead of the clock goes again unstamped, its key still forced`() = runTest {
        backend.answer(UPLOAD, 409, """{"detail":"clock_ahead"}""")

        api.uploadCourses(listOf(course()), TERM, forceKeys = listOf(KEY), addedAt = mapOf(NO to AT))

        val retried = backend.received(UPLOAD).last()
        assertEquals(2, backend.received(UPLOAD).size)
        assertNull(retried.protocol)
        val body = JSONObject(retried.body)
        assertFalse(body.has("adds"))
        assertEquals(KEY, body.getJSONArray("force_keys").getString(0))
    }

    /** A hide or reset made after the add won: the backend has settled it, and it waits no more. */
    @Test
    fun `an add the backend answered but did not apply counts as settled`() = runTest {
        backend.answer(UPLOAD, 200, """{"accepted_keys":[],"adds":[{"course_key":"$KEY","applied":false,"hlc":1}]}""")

        val answer = api.uploadCoursesAnswered(listOf(course()), TERM, forceKeys = listOf(KEY), addedAt = mapOf(NO to AT))

        assertEquals(emptyList<String>(), answer!!.accepted)
        assertEquals(listOf(NO), answer.refusedAdds)
        assertEquals(listOf(NO), answer.settled)
    }

    @Test
    fun `an upload with no add times goes as before, without the header`() = runTest {
        api.uploadCourses(listOf(course()), TERM, forceKeys = listOf(KEY))

        val sent = backend.received(UPLOAD).single()
        assertNull(sent.protocol)
        assertFalse(JSONObject(sent.body).has("adds"))
    }

    private fun course() = Course(courseNo = NO, courseName = NO, isManual = true)

    private companion object {
        const val TERM = "1151"
        const val NO = "CS1001301"
        const val KEY = "client:$TERM:$NO"
        const val DELETE = "DELETE /sync/courses/$KEY"
        const val UPLOAD = "POST /sync/courses/upload"
        const val AT = 1_790_000_000_500L
        const val HLC = 1_790_000_000_500_000L
    }
}
