package org.ntust.app.tigerduck.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime

class CourseUploadPayloadTest {

    private fun course(
        courseNo: String = "CS101",
        courseName: String = "微積分",
        customCourseName: String? = null,
        customColorHex: String? = null,
    ) = Course(
        courseNo = courseNo,
        courseName = courseName,
        instructor = "Ada, Grace、Alan",
        credits = 3f,
        classroom = "T3-101",
        scheduleJson = """{"1":["3","4"]}""",
        classroomMapJson = """{"1-3":"T3-101"}""",
        moodleIdNumber = "1141$courseNo",
        customColorHex = customColorHex,
        customCourseName = customCourseName,
    )

    @Suppress("UNCHECKED_CAST")
    private fun items(payload: Map<String, Any>) =
        payload["courses"] as List<Map<String, Any?>>

    private fun build(courses: List<Course>, rosterTerms: List<String> = emptyList(), adds: Map<String, EditTime> = emptyMap()) =
        CourseUploadPayload.build(courses, semester = "1141", rosterTerms = rosterTerms, adds = adds.mapValues { (_, at) -> DeviceClocks.wallOnly { NOW_MS }.stampOf(at) })

    /**
     * The one that matters. `course_name` is the shared default the whole
     * account renders, so a local rename must not travel in it — that channel
     * is the per-locale override, and it is gated by a pref this call does not
     * consult. Sending displayName here published one device's private name to
     * every other device, whatever the user had chosen.
     */
    @Test
    fun `course_name carries the derived name, never the user's rename`() {
        val payload = build(listOf(course(courseName = "微積分", customCourseName = "早八地獄")))
        assertEquals("微積分", items(payload).single()["course_name"])
    }

    /**
     * A color goes only as its course's override PATCH, which its switch gates: the backend
     * refuses an upload that carries `course_overrides` (422 `invalid_request`), and it takes an
     * add's stamp, not `force_keys`, past a tombstone.
     */
    @Test
    fun `no color and no force key rides an upload, a picked color or a forced add's alike`() {
        val payload = build(
            listOf(course(courseNo = "CS101", customColorHex = "#112233")),
            adds = mapOf(CourseUploadPayload.key("1141", "CS101") to EditTime(1_790_000_000_000L)),
        )
        assertFalse(payload.containsKey("course_overrides"))
        assertFalse(payload.containsKey("force_keys"))
        assertEquals(listOf(mapOf("course_key" to "client:1141:CS101", "age_ms" to NOW_MS - 1_790_000_000_000L)), payload["adds"])
    }

    /**
     * A term in `roster_terms` is read as the school's whole roster: the backend drops its school
     * courses the upload leaves out. An upload that is no roster must not carry the key at all,
     * as a backend reads an empty list the same, and older ones never see it.
     */
    @Test
    fun `roster_terms goes only with an upload that is a roster`() {
        val plain = build(listOf(course()))
        assertFalse(plain.containsKey("roster_terms"))

        val roster = build(listOf(course()), rosterTerms = listOf("1141"))
        assertEquals(listOf("1141"), roster["roster_terms"])
    }

    /** Instructors are split on all three separators the portal mixes. */
    @Test
    fun `instructors split on ascii, fullwidth and ideographic commas`() {
        val payload = build(listOf(course()))
        assertEquals(listOf("Ada", "Grace", "Alan"), items(payload).single()["instructors"])
    }

    /** Schedule keys go out as strings; the server's schema reads a JSON object. */
    @Test
    fun `schedule weekday keys are stringified`() {
        val payload = build(listOf(course()))
        assertEquals(
            mapOf("1" to listOf("3", "4")),
            items(payload).single()["schedule_json"],
        )
    }

    private companion object {
        // The wall clock as the upload is built.
        const val NOW_MS = 1_790_000_004_000L
    }
}
