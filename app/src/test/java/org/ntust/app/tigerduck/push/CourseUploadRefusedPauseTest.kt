package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * A term's list the backend turned away with an HTTP error waits [REFUSED_UPLOAD_PAUSE_MS] before
 * it goes again ([UploadLedger]): the refusal, a [org.ntust.app.tigerduck.network.backend.BackendException]
 * that keeps the backend's code, counts as one. Otherwise every fetch in the next minute sends the
 * same roster.
 */
class CourseUploadRefusedPauseTest {

    private val signIn = testSignIn()
    private val school = Course(courseNo = "CS1010001", courseName = "Portal", moodleIdNumber = "1151CS1010001")

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where no upload goes out at all.
        assumeTrue(AppPreferences(JvmContext()).cloudSyncEnabled)
    }

    @Test
    fun `a roster upload the backend turned away waits its minute`() = runTest {
        val backend = RecordingBackend()
        val client = pushApiClientAt("http://127.0.0.1:9/v4", backend.client, AppPreferences(JvmContext()))
        backend.answer("/v4/sync/courses/upload", 500, "{}")

        val refused = runCatching { client.uploadCoursesAnswered(listOf(school), "1151", signIn, rosterTerms = listOf("1151")) }
        assertTrue("${refused.exceptionOrNull()}", refused.isFailure)
        // Another fetch within the minute, with the same roster.
        client.uploadCoursesAnswered(listOf(school), "1151", signIn, rosterTerms = listOf("1151"))

        assertEquals(1, backend.sent.size)
    }
}
