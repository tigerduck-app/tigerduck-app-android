package org.ntust.app.tigerduck.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.push.PushApiException
import java.io.IOException

class LogSummaryTest {

    @Test
    fun `an exception is named by its class, never by its message`() {
        val e = IOException("""HTTP 400 {"detail":"text from the server"}""")
        assertEquals("IOException", e.logSummary())
    }

    @Test
    fun `a backend refusal adds the HTTP status it carries`() {
        val e = PushApiException("patchCourseIgnore failed: HTTP 404 reply body", 404)
        assertEquals("PushApiException (HTTP 404)", e.logSummary())
    }

    @Test
    fun `a backend failure without a status is named by its class alone`() {
        val e = PushApiException("register failed: HTTP 500 reply body")
        assertEquals("PushApiException", e.logSummary())
    }

    @Test
    fun `a Moodle HTTP status is kept`() {
        assertEquals("HttpStatus (HTTP 503)", MoodleWebserviceError.HttpStatus(503).logSummary())
    }
}
