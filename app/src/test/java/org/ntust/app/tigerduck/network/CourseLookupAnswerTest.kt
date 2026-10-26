package org.ntust.app.tigerduck.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What QueryCourse's answer to one course code says: the course, a code it does not know (an
 * empty list), or no answer at all, which must not pass for an unknown code. An imported class
 * table would otherwise lose the courses of a failed request, and an update would save that.
 */
class CourseLookupAnswerTest {

    private fun response(code: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://querycourse.ntust.edu.tw/QueryCourse/api/courses").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    @Test
    fun `a course the query knows is read from its rows`() {
        val rows = CourseService.lookupAnswer(
            response(200, """[{"CourseNo":"CS1010001","CourseName":"Calculus","Semester":"1151"}]"""),
        )

        assertEquals(listOf("CS1010001"), rows.map { it.courseNo })
    }

    @Test
    fun `a code the query does not know is an empty answer`() {
        assertTrue(CourseService.lookupAnswer(response(200, "[]")).isEmpty())
        assertTrue(CourseService.lookupAnswer(response(200, "")).isEmpty())
    }

    @Test
    fun `a failed request is no answer, whatever its body`() {
        assertThrows(IOException::class.java) { CourseService.lookupAnswer(response(500, "")) }
        assertThrows(IOException::class.java) { CourseService.lookupAnswer(response(503, "[]")) }
        assertThrows(IOException::class.java) { CourseService.lookupAnswer(response(429, "Too many requests")) }
    }
}
