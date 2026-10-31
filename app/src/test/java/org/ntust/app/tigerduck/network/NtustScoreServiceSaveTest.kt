package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The transcript is kept on disk only for the account it was fetched for: the score system
 * takes seconds to answer, and a sign-out meanwhile wipes the departing student's files, which a
 * late save would put back. As iOS `NTUSTScoreService.fetchScoreReport`'s `persistGuard`.
 */
class NtustScoreServiceSaveTest {

    private val context = JvmContext()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    private var signedIn: String? = STUDENT
    private val answer = CompletableDeferred<ScoreReport>()

    private val service = NtustScoreService(
        dataCache = cache,
        isSignedInAs = { it == signedIn },
        download = { _, _ -> answer.await() },
    )

    private val report = ScoreReport.EMPTY.copy(
        student = STUDENT,
        courses = listOf(CourseGrade(term = "1141", code = "XA0000001", name = "Made up")),
    )

    @Test
    fun `a report fetched for the account still signed in is kept`() = runTest {
        answer.complete(report)

        assertEquals(report, service.fetchScoreReport(STUDENT, "not-a-password"))
        assertEquals(report, cache.loadScoreReport(STUDENT)?.report)
    }

    @Test
    fun `a report fetched for an account that signed out meanwhile is not kept`() = runTest {
        val fetch = async { service.fetchScoreReport(STUDENT, "not-a-password") }
        testScheduler.advanceUntilIdle()
        signedIn = null
        answer.complete(report)

        // The caller decides what to do with it; it is not on disk for the next account to find.
        assertEquals(report, fetch.await())
        assertNull(cache.loadScoreReport(STUDENT))
    }

    @Test
    fun `a report fetched while another account signed in is not kept under the first one`() = runTest {
        val fetch = async { service.fetchScoreReport(STUDENT, "not-a-password") }
        testScheduler.advanceUntilIdle()
        signedIn = "TEST00002"
        answer.complete(report)
        fetch.await()

        assertNull(cache.loadScoreReport(STUDENT))
        assertNull(cache.loadScoreReport("TEST00002"))
    }

    @Test
    fun `an empty page is no report, and nothing is kept`() = runTest {
        answer.complete(ScoreReport.EMPTY)

        val failure = runCatching { service.fetchScoreReport(STUDENT, "not-a-password") }.exceptionOrNull()
        assertTrue(failure is NtustScoreError.ParseFailed)
        assertNull(cache.loadScoreReport(STUDENT))
    }

    private companion object {
        const val STUDENT = "TEST00001"
    }
}
