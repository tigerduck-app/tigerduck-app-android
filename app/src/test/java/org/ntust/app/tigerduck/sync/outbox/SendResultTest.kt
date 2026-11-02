package org.ntust.app.tigerduck.sync.outbox

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.push.PushApiException
import java.io.IOException

/**
 * Which failures of a queued write count as the backend's refusal, which counts an attempt
 * towards parking, and which keep the edit as it is: today's answer, `CourseIgnoreSync.refuses`,
 * the one the marks and the course overrides count by, for every failure a `/v3` write throws.
 */
class SendResultTest {

    /** Each failure a `/v3` write throws, and whether it counts as a refusal today. */
    private val today: List<Pair<Exception, Boolean>> = listOf(
        IOException("offline") to false,
        NotSignedInException() to false,
        PushApiException("unauthorized", 401) to false,
        PushApiException("timeout", 408) to false,
        PushApiException("busy", 429) to false,
        PushApiException("internal", 500) to false,
        PushApiException("unavailable", 503) to false,
        PushApiException("last of its kind", 599) to false,
        PushApiException("bad request", 400) to true,
        PushApiException("not found", 404) to true,
        PushApiException("conflict", 409) to true,
        PushApiException("gone", 410) to true,
        PushApiException("unprocessable", 422) to true,
        PushApiException("no status") to true,
        IllegalStateException("bad body") to true,
    )

    @Test
    fun `today's refusals are the backend's answers but a session, a timeout, a busy or a failing server`() {
        for ((failure, refused) in today) assertEquals("$failure", refused, CourseIgnoreSync.refuses(failure))
    }
}
