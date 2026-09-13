package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Pins the `notification` settings document's wire shape (design spec §4.6).
 *
 * The `push` package carries no R8 keep rule (see CLAUDE.md /
 * upgrade-safe-persistence): a field missing `@SerializedName`, or one whose
 * value doesn't match the spec's JSON key byte-for-byte, deserializes fine
 * in a debug build and silently comes back null in release. Test 3 below
 * checks this by reflection rather than by eyeballing the class.
 */
class NotificationSettingsDocumentTest {

    private val gson = Gson()

    private val fullJson = """
        {
          "assignments": {
            "enabled": true,
            "reminder_offsets_hours": [24, 2],
            "reminder_offsets_minutes": [1440, 120, 30]
          },
          "courses":     { "enabled": true, "reminder_offsets_minutes": [10] },
          "live_activity": {
            "show_class_preparing": true,
            "show_in_class": true,
            "show_assignment": true,
            "class_preparing_lead_seconds": 3600,
            "assignment_lead_seconds": 28800
          }
        }
    """.trimIndent()

    /** 1. The full §4.6 document round trips: decode -> encode -> decode are equal. */
    @Test
    fun `full document round trips through decode, encode, decode`() {
        val decoded = gson.fromJson(fullJson, NotificationSettingsDocument::class.java)
        val redecoded = gson.fromJson(gson.toJson(decoded), NotificationSettingsDocument::class.java)
        assertEquals(decoded, redecoded)

        // Pin the actual values too, not just self-consistency — a field
        // silently mapped to the wrong JSON key would still round-trip
        // (encode/decode use the same wrong key both times) but would be
        // reading/writing nothing the server understands.
        assertEquals(true, decoded.assignments?.enabled)
        assertEquals(listOf(24, 2), decoded.assignments?.reminderOffsetsHours)
        assertEquals(listOf(1440, 120, 30), decoded.assignments?.reminderOffsetsMinutes)
        assertEquals(true, decoded.courses?.enabled)
        assertEquals(listOf(10), decoded.courses?.reminderOffsetsMinutes)
        assertEquals(true, decoded.liveActivity?.showClassPreparing)
        assertEquals(true, decoded.liveActivity?.showInClass)
        assertEquals(true, decoded.liveActivity?.showAssignment)
        assertEquals(3600, decoded.liveActivity?.classPreparingLeadSeconds)
        assertEquals(28800, decoded.liveActivity?.assignmentLeadSeconds)
    }

    /**
     * 2. A document written before v2.1.0 has no `live_activity` key at all
     * — every existing user's document is in this shape. It must still
     * decode instead of throwing, with `liveActivity == null`.
     */
    @Test
    fun `documents written before live_activity existed still decode`() {
        val legacyJson = """
            {
              "assignments": { "enabled": true, "reminder_offsets_hours": [24, 2] },
              "courses": { "enabled": true, "reminder_offsets_minutes": [10] }
            }
        """.trimIndent()
        val decoded = gson.fromJson(legacyJson, NotificationSettingsDocument::class.java)
        assertNull(decoded.liveActivity)
        assertNotNull(decoded.assignments)
        assertNull(
            "a client older than reminder_offsets_minutes never wrote it -- must decode as absent, not crash",
            decoded.assignments?.reminderOffsetsMinutes,
        )
        assertNotNull(decoded.courses)
    }

    /**
     * 3. Every field of every section carries `@SerializedName` with the
     * exact key from §4.6's JSON — asserted by reflection over the compiled
     * fields, not by reading the source with our eyes.
     */
    @Test
    fun `every field carries the exact SerializedName from the spec`() {
        val expected: Map<KClass<*>, Map<String, String>> = mapOf(
            NotificationSettingsDocument::class to mapOf(
                "assignments" to "assignments",
                "courses" to "courses",
                "liveActivity" to "live_activity",
            ),
            AssignmentsSection::class to mapOf(
                "enabled" to "enabled",
                "reminderOffsetsHours" to "reminder_offsets_hours",
                "reminderOffsetsMinutes" to "reminder_offsets_minutes",
            ),
            CoursesSection::class to mapOf(
                "enabled" to "enabled",
                "reminderOffsetsMinutes" to "reminder_offsets_minutes",
            ),
            LiveActivitySection::class to mapOf(
                "showClassPreparing" to "show_class_preparing",
                "showInClass" to "show_in_class",
                "showAssignment" to "show_assignment",
                "classPreparingLeadSeconds" to "class_preparing_lead_seconds",
                "assignmentLeadSeconds" to "assignment_lead_seconds",
            ),
        )

        for ((klass, expectedFields) in expected) {
            // Instance fields only: the Compose compiler plugin stamps a
            // synthetic-looking but non-`isSynthetic` `public static final
            // int $stable` onto every class in this module, Composable or
            // not, which would otherwise show up as an unexpected field.
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
     * 4. An unknown top-level key (a section from a future app version, or a
     * namespace some other client wrote to) must not fail decode.
     */
    @Test
    fun `unknown top-level keys do not fail decode`() {
        val jsonWithUnknownKey = """
            {
              "assignments": { "enabled": true, "reminder_offsets_hours": [24, 2] },
              "some_future_section": { "whatever": true }
            }
        """.trimIndent()
        val decoded = gson.fromJson(jsonWithUnknownKey, NotificationSettingsDocument::class.java)
        assertNotNull(decoded.assignments)
        assertNull(decoded.liveActivity)
    }
}
