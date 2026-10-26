package org.ntust.app.tigerduck.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * What [MoodleService.requestAssignments] makes of each thing the NTUST
 * Moodle can send back. Only an answer may come back as a list, since the
 * callers store any list, an empty one included; everything else has to
 * throw, so they keep the assignments they have.
 */
class MoodleAssignmentsAnswerTest {

    // Answers in place of the network: the interceptor never calls proceed().
    private fun answering(body: String, code: Int = 200, type: String = "application/json") =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody(type.toMediaType()))
                .build()
        }.build()

    private fun fetch(client: OkHttpClient) =
        MoodleService.requestAssignments(client, "t1", listOf(7, 9))

    private fun fetch(body: String) = fetch(answering(body))

    // --- answers ----------------------------------------------------------

    @Test
    fun `no course at all is an answer`() {
        // What a TA account gets when every course asked about is one it TAs.
        assertTrue(fetch("""{"courses":[],"warnings":[]}""").isEmpty())
    }

    @Test
    fun `courses without assignments are an answer`() {
        val courses = fetch(
            """
            {"courses":[{"id":7,"assignments":[]}],
             "warnings":[{"item":"module","itemid":31,"warningcode":"1",
                          "message":"No access rights in module context"}]}
            """.trimIndent()
        )
        assertEquals(listOf(7), courses.map { it.id })
        assertTrue(courses.single().assignments.isEmpty())
    }

    @Test
    fun `a warning about one course leaves the answer for the others`() {
        val courses = fetch(
            """
            {"courses":[{"id":7,"assignments":[{"id":5,"cmid":6,"name":"HW","duedate":1}]}],
             "warnings":[{"item":"course","itemid":9,"warningcode":"2",
                          "message":"User is not enrolled or does not have requested capability"}]}
            """.trimIndent()
        )
        assertEquals(listOf(5), courses.flatMap { c -> c.assignments.map { it.id } })
    }

    // --- not answers ------------------------------------------------------

    @Test
    fun `a challenge page from the NTUST edge throws`() {
        val page = """
            <!DOCTYPE html>
            <html><head><title>Checking your browser</title></head>
            <body><script>document.cookie = "NSC_challenge=1";</script></body></html>
        """.trimIndent()
        assertThrows(MoodleWebserviceError.MalformedResponse::class.java) {
            fetch(answering(page, type = "text/html"))
        }
    }

    @Test
    fun `a Moodle exception throws`() {
        val error = assertThrows(MoodleWebserviceError.MalformedResponse::class.java) {
            fetch(
                """{"exception":"dml_read_exception","errorcode":"dmlreadexception",""" +
                    """"message":"Error reading from database"}"""
            )
        }
        assertEquals("dmlreadexception", error.detail)
    }

    @Test
    fun `a rejected token throws for the retry with a new one`() {
        assertThrows(MoodleWebserviceError.InvalidToken::class.java) {
            fetch(
                """{"exception":"moodle_exception","errorcode":"invalidtoken",""" +
                    """"message":"Invalid token - token not found"}"""
            )
        }
    }

    @Test
    fun `no course with a warning about one throws`() {
        // These course ids under a token of an account that is not enrolled
        // in them: the token race after a sign-out.
        assertThrows(MoodleWebserviceError.MalformedResponse::class.java) {
            fetch(
                """
                {"courses":[],
                 "warnings":[{"item":"course","itemid":7,"warningcode":"2",
                              "message":"User is not enrolled or does not have requested capability"},
                             {"item":"course","itemid":9,"warningcode":"2",
                              "message":"User is not enrolled or does not have requested capability"}]}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a body without courses throws`() {
        listOf("""{}""", """{"warnings":[]}""", """{"courses":null}""", "[]", "null", "").forEach { body ->
            assertThrows(body, MoodleWebserviceError.MalformedResponse::class.java) { fetch(body) }
        }
    }

    @Test
    fun `an error status throws`() {
        val error = assertThrows(MoodleWebserviceError.HttpStatus::class.java) {
            fetch(answering("<html>Service Unavailable</html>", code = 503, type = "text/html"))
        }
        assertEquals(503, error.code)
    }

    @Test
    fun `a network error throws`() {
        val offline = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("timeout") }
            .build()
        assertThrows(IOException::class.java) { fetch(offline) }
    }
}
