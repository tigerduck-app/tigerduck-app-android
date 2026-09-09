package org.ntust.app.tigerduck.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

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
        credits = 3,
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

    @Suppress("UNCHECKED_CAST")
    private fun overrides(payload: Map<String, Any>) =
        payload["course_overrides"] as List<Map<String, Any?>>

    /**
     * The one that matters. `course_name` is the shared default the whole
     * account renders, so a local rename must not travel in it — that channel
     * is the per-locale override, and it is gated by a pref this call does not
     * consult. Sending displayName here published one device's private name to
     * every other device, whatever the user had chosen.
     */
    @Test
    fun `course_name carries the derived name, never the user's rename`() {
        val payload = CourseUploadPayload.build(
            listOf(course(courseName = "微積分", customCourseName = "早八地獄")),
            semester = "1141",
        )
        assertEquals("微積分", items(payload).single()["course_name"])
    }

    /**
     * The colour half of the same contract: the upload route's upsert is
     * create-only, so a generated colour sent once is pinned server-side for
     * good. Only a course the user actually picked a colour for belongs here.
     */
    @Test
    fun `only courses with a picked colour produce an override`() {
        val payload = CourseUploadPayload.build(
            listOf(
                course(courseNo = "CS101", customColorHex = null),
                course(courseNo = "CS202", customColorHex = "#112233"),
            ),
            semester = "1141",
        )
        val single = overrides(payload).single()
        assertEquals("client:1141:CS202", single["course_key"])
        assertEquals("#112233", single["color_hex"])
    }

    /** An override keyed anything but `client:{semester}:{no}` is dropped server-side. */
    @Test
    fun `override keys match the keys the upload route derives`() {
        val payload = CourseUploadPayload.build(
            listOf(course(courseNo = "CS101", customColorHex = "#ABCDEF")),
            semester = "1141",
        )
        val uploadedKeys = items(payload).map { "client:1141:${it["course_no"]}" }
        assertTrue(overrides(payload).all { it["course_key"] in uploadedKeys })
    }

    /**
     * `force_keys` clears a server-side tombstone. A routine upload that sent
     * it would undo a delete the user made on another device, so the key is
     * absent — not empty — unless a caller passes one.
     */
    @Test
    fun `force_keys is omitted unless the caller asks for it`() {
        val plain = CourseUploadPayload.build(listOf(course()), semester = "1141")
        assertFalse(plain.containsKey("force_keys"))

        val forced = CourseUploadPayload.build(
            listOf(course()), semester = "1141", forceKeys = listOf("client:1141:CS101"),
        )
        assertEquals(listOf("client:1141:CS101"), forced["force_keys"])
    }

    /** Instructors are split on all three separators the portal mixes. */
    @Test
    fun `instructors split on ascii, fullwidth and ideographic commas`() {
        val payload = CourseUploadPayload.build(listOf(course()), semester = "1141")
        assertEquals(listOf("Ada", "Grace", "Alan"), items(payload).single()["instructors"])
    }

    /** Schedule keys go out as strings; the server's schema reads a JSON object. */
    @Test
    fun `schedule weekday keys are stringified`() {
        val payload = CourseUploadPayload.build(listOf(course()), semester = "1141")
        assertEquals(
            mapOf("1" to listOf("3", "4")),
            items(payload).single()["schedule_json"],
        )
    }
}
