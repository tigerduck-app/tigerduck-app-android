package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Pins [UpdateDevicePreferencesRequest]'s wire shape — most importantly the
 * `locale` field fix-1 added.
 *
 * `push` carries no R8 keep rule (see CLAUDE.md / the upgrade-safe-persistence
 * skill's underlying concern): a field missing `@SerializedName` compiles and
 * passes a debug build fine, then serializes under an obfuscated key in a
 * release build and the backend silently never sees it. This class is never
 * Gson-deserialized from a persisted cache — it is only ever constructed in
 * Kotlin and serialized outbound on every PATCH — so the risk here is
 * specifically R8 renaming the *outbound* JSON key, not the Unsafe-allocate
 * cache-deserialization path that rule is otherwise about.
 *
 * Uses Gson's own `JsonObject` tree, not `org.json` — the latter resolves to
 * the Android SDK's stub jar under this module's plain JVM unit tests
 * (`isReturnDefaultValues = true`, no Robolectric), which does not behave
 * like the real parser.
 */
class PushApiModelsTest {

    private val gson = Gson()

    private fun jsonOf(payload: Any): JsonObject = gson.toJsonTree(payload).asJsonObject

    /**
     * The property the whole fix leans on: a PATCH that only carries
     * `locale` must not also emit any of the other fields, or it would
     * accidentally overwrite a preference (`server_push_enabled`, the sync
     * toggles, `cloud_sync_enabled`) it never meant to touch. Gson's default
     * "omit nulls" behavior is what makes every existing call site safe to
     * add a new optional field to; this pins that assumption for this exact
     * class, not just as documentation.
     */
    @Test
    fun `a locale-only request serializes locale and nothing else`() {
        val json = jsonOf(UpdateDevicePreferencesRequest(locale = "zh-TW"))
        assertEquals("zh-TW", json.get("locale").asString)
        assertEquals(setOf("locale"), json.keySet())
    }

    /**
     * Mirror check: a request that does not mention locale at all — every
     * PATCH call site fix-1 did not touch — must not emit the key. This is
     * what lets the brief claim those PATCHes "can't wipe" a locale the new
     * call site set: there is no `"locale": null` on the wire for the
     * backend's non-null guard to even need to catch.
     */
    @Test
    fun `a request with no locale omits the key entirely`() {
        val json = jsonOf(UpdateDevicePreferencesRequest(serverPushEnabled = true))
        assertTrue(json.has("server_push_enabled"))
        assertFalse(json.has("locale"))
    }

    /** Round trip: decoding what we just encoded gets the same value back. */
    @Test
    fun `locale round trips through decode, encode, decode`() {
        val decoded = gson.fromJson(
            """{"locale":"en-GB"}""",
            UpdateDevicePreferencesRequest::class.java,
        )
        assertEquals("en-GB", decoded.locale)
        val redecoded = gson.fromJson(gson.toJson(decoded), UpdateDevicePreferencesRequest::class.java)
        assertEquals(decoded, redecoded)
    }

    /**
     * Every field of [UpdateDevicePreferencesRequest] and
     * [DevicePreferencesResponse] carries `@SerializedName` with the exact
     * snake_case key the backend's `DevicePreferencesV3Request` /
     * `DevicePreferencesV3Response` expect — asserted by reflection over the
     * compiled fields, not by eyeballing the source. Catches exactly the
     * failure mode this task's binding constraint warns about: an
     * unannotated field is invisible here in debug but silently renamed in
     * release. Covers the task-4 `sync_assignment_reminders` /
     * `sync_live_activity` fields on both DTOs.
     */
    @Test
    fun `every field carries the exact SerializedName from the backend schema`() {
        val expected: Map<KClass<*>, Map<String, String>> = mapOf(
            UpdateDevicePreferencesRequest::class to mapOf(
                "serverPushEnabled" to "server_push_enabled",
                "syncCourses" to "sync_courses",
                "syncCourseColors" to "sync_course_colors",
                "syncCourseNames" to "sync_course_names",
                "syncAssignments" to "sync_assignments",
                "syncAssignmentReminders" to "sync_assignment_reminders",
                "syncLiveActivity" to "sync_live_activity",
                "cloudSyncEnabled" to "cloud_sync_enabled",
                "locale" to "locale",
            ),
            DevicePreferencesResponse::class to mapOf(
                "deviceId" to "device_id",
                "serverPushEnabled" to "server_push_enabled",
                "syncCourses" to "sync_courses",
                "syncCourseColors" to "sync_course_colors",
                "syncCourseNames" to "sync_course_names",
                "syncAssignments" to "sync_assignments",
                "syncAssignmentReminders" to "sync_assignment_reminders",
                "syncLiveActivity" to "sync_live_activity",
                "cloudSyncEnabled" to "cloud_sync_enabled",
            ),
        )

        for ((klass, expectedFields) in expected) {
            val declaredFields = klass.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            assertEquals(
                "field set mismatch for ${klass.simpleName}",
                expectedFields.keys,
                declaredFields.map { it.name }.toSet(),
            )
            for (field in declaredFields) {
                val annotation = field.getAnnotation(SerializedName::class.java)
                assertNotNull(
                    "${klass.simpleName}.${field.name} is missing @SerializedName",
                    annotation,
                )
                assertEquals(
                    "${klass.simpleName}.${field.name} @SerializedName value",
                    expectedFields.getValue(field.name),
                    annotation!!.value,
                )
            }
        }
    }

    /**
     * Task-4 addition: a request that sets the two new "同步內容" fields
     * serializes them under their snake_case wire names, alongside the
     * existing sync toggles — and omits them (Gson's default "omit nulls")
     * when left at their `null` default, same as every other optional field
     * on this class.
     */
    @Test
    fun `syncAssignmentReminders and syncLiveActivity serialize under their backend keys`() {
        val json = jsonOf(
            UpdateDevicePreferencesRequest(
                syncAssignmentReminders = false,
                syncLiveActivity = true,
            )
        )
        assertEquals(false, json.get("sync_assignment_reminders").asBoolean)
        assertEquals(true, json.get("sync_live_activity").asBoolean)
        assertEquals(setOf("sync_assignment_reminders", "sync_live_activity"), json.keySet())
    }

    @Test
    fun `a request that omits syncAssignmentReminders and syncLiveActivity emits neither key`() {
        val json = jsonOf(UpdateDevicePreferencesRequest(syncCourses = true))
        assertTrue(json.has("sync_courses"))
        assertFalse(json.has("sync_assignment_reminders"))
        assertFalse(json.has("sync_live_activity"))
    }

    /**
     * [DevicePreferencesResponse]'s new fields follow the same
     * "`Boolean = true`, matching the five pre-existing sync fields'
     * convention" the task-4 brief calls for. A backend response that omits
     * them entirely (an older backend, or a device that predates the
     * columns) must still read back `true` on decode — not the JVM
     * zero-value `false` a naive reading of Gson's Kotlin-construction
     * behavior might suggest. This is the concrete claim the
     * upgrade-safe-persistence checklist's "primitive types default safely"
     * bullet rests on for this class.
     */
    @Test
    fun `a response missing the new keys still defaults both to true`() {
        val decoded = gson.fromJson(
            """{"device_id":"abc"}""",
            DevicePreferencesResponse::class.java,
        )
        assertEquals(true, decoded.syncAssignmentReminders)
        assertEquals(true, decoded.syncLiveActivity)
    }

    /**
     * Mirror check: when the backend *does* send the keys, the decoded
     * value must actually come from the wire, not just fall back to the
     * `true` default. This is what would catch a missing or mistyped
     * `@SerializedName` that the reflection test above didn't (a response
     * decode doesn't fail loudly on an unmatched JSON key — the field
     * would just silently stay at its default).
     */
    @Test
    fun `a response with the new keys present overrides the default`() {
        val decoded = gson.fromJson(
            """{"sync_assignment_reminders":false,"sync_live_activity":false}""",
            DevicePreferencesResponse::class.java,
        )
        assertEquals(false, decoded.syncAssignmentReminders)
        assertEquals(false, decoded.syncLiveActivity)
    }
}
