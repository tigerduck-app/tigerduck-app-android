package org.ntust.app.tigerduck.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler

/** The `course_overrides` of `GET /sync/full`, from an older backend and a current one. */
class CourseOverrideParseTest {

    @Test
    fun `an override from a backend without the field reads as unknown`() {
        val json = JSONObject(
            """{"course_overrides":[{"moodle_id":"1141CS1001301","course_no":"CS1001301","color_hex":"#FF0000"}]}"""
        )

        val override = parseCourseOverrides(json, emptyMap()).single()

        assertNull(override.ignoreAssignments)
        assertNull(override.semester)
        assertNull(override.moodleNumericCourseId)
        assertEquals("#FF0000", override.colorHex)
    }

    @Test
    fun `the ignore setting, its term and the numeric Moodle id are read`() {
        val json = JSONObject(
            """
            {"course_overrides":[
              {"moodle_id":"1141CS1001301","course_no":"CS1001301","semester":"1141",
               "ignore_assignments":true,"ignore_assignments_updated_at":"2026-10-13T00:00:00Z",
               "moodle_course_id":4321},
              {"moodle_id":"1141CS1001302","course_no":"CS1001302","semester":"1141",
               "ignore_assignments":false,"ignore_assignments_updated_at":null,
               "moodle_course_id":null}
            ]}
            """.trimIndent()
        )

        val (ignored, kept) = parseCourseOverrides(json, emptyMap())

        assertEquals(true, ignored.ignoreAssignments)
        assertEquals("2026-10-13T00:00:00Z", ignored.ignoreAssignmentsUpdatedAt)
        assertEquals("1141", ignored.semester)
        assertEquals(4321, ignored.moodleNumericCourseId)
        assertEquals(false, kept.ignoreAssignments)
        assertNull(kept.ignoreAssignmentsUpdatedAt)
        assertNull(kept.moodleNumericCourseId)
    }

    @Test
    fun `the time of the colour pick is read, and is null when the backend leaves it out`() {
        val json = JSONObject(
            """
            {"course_overrides":[
              {"moodle_id":"1141CS1001301","course_no":"CS1001301","color_hex":"#FF0000",
               "color_hex_updated_at":"2026-10-10T16:42:49.666640+00:00"},
              {"moodle_id":"1141CS1001302","course_no":"CS1001302","color_hex":"#00FF00",
               "color_hex_updated_at":null},
              {"moodle_id":"1141CS1001303","course_no":"CS1001303","color_hex":"#0000FF"}
            ]}
            """.trimIndent()
        )

        val (picked, uploaded, older) = parseCourseOverrides(json, emptyMap())

        assertEquals("2026-10-10T16:42:49.666640+00:00", picked.colorHexUpdatedAt)
        assertNull(uploaded.colorHexUpdatedAt)
        assertNull(older.colorHexUpdatedAt)
    }

    /**
     * tigerduck-backend#122: the backend keeps `""` for a name the user cleared, so a device
     * that pulls it takes that language off, and the last one off drops the course's entry.
     */
    @Test
    fun `a cleared name is read as empty, and the merge takes it off the stored names`() {
        val json = JSONObject(
            """
            {"course_overrides":[
              {"moodle_id":"1141CS1001301","course_no":"CS1001301","custom_names":{"zh-TW":"","en":"Calc"}},
              {"moodle_id":"1141CS1001302","course_no":"CS1001302","custom_names":{"zh-TW":""}}
            ]}
            """.trimIndent()
        )

        val overrides = parseCourseOverrides(json, emptyMap())
        val merged = CourseSyncReconciler.mergeCustomNames(
            existing = mapOf(
                "CS1001301" to mapOf("zh-TW" to "微積分", "en" to "Calculus"),
                "CS1001302" to mapOf("zh-TW" to "物理"),
            ),
            overrides = overrides,
            syncNames = true,
        )

        assertEquals(mapOf("zh-TW" to "", "en" to "Calc"), overrides[0].customNames)
        assertEquals(mapOf("CS1001301" to mapOf("en" to "Calc")), merged)
    }

    @Test
    fun `a course row's id is read, and is null when it has none`() {
        assertEquals(42L, serverCourseRowId(JSONObject("""{"id":42,"course_no":"CS1"}""")))
        assertNull(serverCourseRowId(JSONObject("""{"course_no":"CS1"}""")))
        assertNull(serverCourseRowId(JSONObject("""{"id":null,"course_no":"CS1"}""")))
    }
}
