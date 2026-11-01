package org.ntust.app.tigerduck.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `courses` row of `GET /v3/sync/full`, from a backend that sends where the row came from and
 * whether a roster dropped it (3.10.1), and from one that does not. Course numbers are made up.
 */
class ServerCourseParseTest {

    @Test
    fun `a row carries its source and enrollment status`() {
        val row = JSONObject(
            """{"course_no":"XA0000001","semester":"1151","moodle_id":"1151XA0000001",
               "source":"user_added","enrollment_status":"dropped"}"""
        )

        val course = parseServerCourse(row)!!

        assertEquals("XA0000001", course.courseNo)
        assertEquals("1151XA0000001", course.moodleId)
        assertEquals("user_added", course.source)
        assertEquals("dropped", course.enrollmentStatus)
    }

    @Test
    fun `a row from an older backend, or with the fields null, reads both as unknown`() {
        val older = parseServerCourse(JSONObject("""{"course_no":"XA0000001","semester":"1151"}"""))!!
        val nulls = parseServerCourse(
            JSONObject("""{"course_no":"XA0000001","semester":"1151","source":null,"enrollment_status":null}""")
        )!!

        assertNull(older.source)
        assertNull(older.enrollmentStatus)
        assertNull(nulls.source)
        assertNull(nulls.enrollmentStatus)
    }

    @Test
    fun `a row without a course number is skipped`() {
        assertNull(parseServerCourse(JSONObject("""{"semester":"1151","moodle_id":"1151XA0000001"}""")))
    }
}
