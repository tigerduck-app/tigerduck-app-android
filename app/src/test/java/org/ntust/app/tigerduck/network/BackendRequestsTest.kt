package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarApi
import org.ntust.app.tigerduck.auth.AuthApi
import org.ntust.app.tigerduck.network.model.SubscriptionRuleRequest
import org.ntust.app.tigerduck.push.AnonymousDeviceRequest
import org.ntust.app.tigerduck.push.DeviceRegisterRequest
import org.ntust.app.tigerduck.push.HolidayOverrideRequest
import org.ntust.app.tigerduck.push.PushApi
import org.ntust.app.tigerduck.push.SettingsDocumentApi
import org.ntust.app.tigerduck.push.SyncApi

/**
 * The backend services make the requests the clients wrote by hand before
 * Retrofit, byte for byte: method, URL, headers, the body's type, length and
 * bytes. Each expectation below is built as the client built it then.
 * Nothing is sent: the services hand their calls back unsent.
 */
class BackendRequestsTest {

    private val base = "https://api.tigerduck.test/v3"
    private val auth = "Bearer access-1"
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()
    private val calls = OkHttpClient()

    private fun <T : Any> service(type: Class<T>) = BackendServices(type, calls).at(base)

    private fun json(value: Any) = gson.toJson(value).toRequestBody(jsonType)

    private fun assertSameRequest(expected: Request, call: okhttp3.Call) {
        val actual = call.request()
        assertEquals(expected.method, actual.method)
        assertEquals(expected.url.toString(), actual.url.toString())
        assertEquals(expected.headers.toList(), actual.headers.toList())
        assertEquals(expected.body?.contentType()?.toString(), actual.body?.contentType()?.toString())
        assertEquals(expected.body?.contentLength(), actual.body?.contentLength())
        assertEquals(expected.body?.let(::bytes), actual.body?.let(::bytes))
    }

    private fun bytes(body: okhttp3.RequestBody) = Buffer().also(body::writeTo).readUtf8()

    @Test
    fun `a device registers, signed in or not`() {
        val api = service(PushApi::class.java)
        val anonymous = AnonymousDeviceRequest(deviceId = "device-1", deviceClass = "phone")
        assertSameRequest(
            Request.Builder().url("$base/devices/anonymous").post(json(anonymous)).build(),
            api.anonymousDevice(anonymous),
        )
        val registered = DeviceRegisterRequest(clientDeviceId = "device-1", appVersion = null)
        assertSameRequest(
            Request.Builder().url("$base/devices/register").post(json(registered)).header("Authorization", auth).build(),
            api.registeredDevice(auth, registered),
        )
    }

    @Test
    fun `a DELETE carries the empty body OkHttp's delete() sent`() {
        val api = service(PushApi::class.java)
        assertSameRequest(
            Request.Builder().url("$base/devices/device-1").delete().header("Authorization", auth).build(),
            api.removeDevice(auth, "device-1", EmptyBody),
        )
        assertSameRequest(
            Request.Builder().url("$base/sync/courses/client:114-1:CS1001").delete().header("Authorization", auth).build(),
            api.removeCourse(auth, "client:114-1:CS1001", EmptyBody, null),
        )
    }

    @Test
    fun `a term's reset sends its semester form-encoded, and every term's none`() {
        val api = service(PushApi::class.java)
        val semester = "114-1 summer"
        assertSameRequest(
            Request.Builder()
                .url("$base/sync/courses?semester=${java.net.URLEncoder.encode(semester, "UTF-8")}")
                .delete().header("Authorization", auth).build(),
            api.removeCourses(auth, java.net.URLEncoder.encode(semester, "UTF-8"), EmptyBody),
        )
        assertSameRequest(
            Request.Builder().url("$base/sync/courses").delete().header("Authorization", auth).build(),
            api.removeCourses(auth, null, EmptyBody),
        )
    }

    @Test
    fun `overrides and uploads send the JSON the client wrote`() {
        val api = service(PushApi::class.java)
        assertSameRequest(
            Request.Builder().url("$base/sync/holiday-overrides/7").put(json(HolidayOverrideRequest(true))).header("Authorization", auth).build(),
            api.holidayOverride(auth, 7, HolidayOverrideRequest(true)),
        )
        val override = mutableMapOf<String, Any?>("color_hex" to "#FF8800", "locale" to "zh-Hant")
        assertSameRequest(
            Request.Builder().url("$base/sync/courses/CS1001/override").patch(json(override)).header("Authorization", auth).build(),
            api.courseOverride(auth, "CS1001", override),
        )
        // Plain Gson leaves a null out, as it did.
        val credentials = mapOf("moodle_token" to "token", "moodle_private_token" to null)
        assertSameRequest(
            Request.Builder().url("$base/auth/credentials").patch(json(credentials)).header("Authorization", auth).build(),
            api.credentials(auth, credentials),
        )
        val written = """{"assignments":[]}"""
        assertSameRequest(
            Request.Builder().url("$base/sync/assignments/upload").post(written.toRequestBody(jsonType)).header("Authorization", auth).build(),
            api.assignmentsUpload(auth, written.toRequestBody(jsonType)),
        )
    }

    @Test
    fun `the change feed asks from a revision`() {
        val api = service(SyncApi::class.java)
        assertSameRequest(
            Request.Builder().url("$base/sync?since_revision=5").get().header("Authorization", auth).build(),
            api.changes(auth, 5),
        )
        assertSameRequest(
            Request.Builder().url("$base/sync/full").get().header("Authorization", auth).build(),
            api.full(auth),
        )
    }

    @Test
    fun `a settings document's write leaves a null base revision out`() {
        val api = service(SettingsDocumentApi::class.java)
        val payload = mapOf("schema_version" to 1, "document" to mapOf("enabled" to true), "base_revision" to null)
        assertSameRequest(
            Request.Builder().url("$base/settings/notification").put(json(payload)).header("Authorization", auth).build(),
            api.putDocument(auth, "notification", payload),
        )
    }

    @Test
    fun `bulletins list their query as the URL builder wrote it`() {
        val api = service(BulletinApi::class.java)
        assertSameRequest(
            Request.Builder().url(
                "$base/bulletins".toHttpUrl().newBuilder()
                    .addQueryParameter("limit", "30")
                    .addQueryParameter("include_deleted", "true")
                    .build(),
            ).get().build(),
            api.bulletins(30, null, "true"),
        )
        val rule = SubscriptionRuleRequest(name = "Scholarships", orgs = listOf("Office"))
        assertSameRequest(
            Request.Builder().url("$base/bulletin-subscriptions/3").patch(json(rule)).header("Authorization", auth).build(),
            api.subscription(auth, 3, rule),
        )
    }

    @Test
    fun `the login sends the JSON the manager wrote, to the issuer`() {
        val body = """{"student_id":"B00015999","moodle_token":"token"}"""
        assertSameRequest(
            Request.Builder().url("$base/auth/login").post(body.toRequestBody(jsonType)).build(),
            service(AuthApi::class.java).login(body.toRequestBody(jsonType)),
        )
    }

    @Test
    fun `the calendar asks with its ETag, or without one`() {
        val api = service(AcademicCalendarApi::class.java)
        assertSameRequest(
            Request.Builder().url("$base/calendar/semesters").get()
                .header("Accept", "application/json").header("If-None-Match", "\"v1\"").build(),
            api.semesters("application/json", "\"v1\""),
        )
        assertSameRequest(
            Request.Builder().url("$base/calendar/semesters").get().header("Accept", "application/json").build(),
            api.semesters("application/json", null),
        )
    }
}
