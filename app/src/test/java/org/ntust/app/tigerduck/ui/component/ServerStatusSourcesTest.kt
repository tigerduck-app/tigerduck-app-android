package org.ntust.app.tigerduck.ui.component

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.network.NtustScoreError
import org.ntust.app.tigerduck.ui.screen.score.ScoreViewModel

/**
 * The sources each page's sync dot lists, and which one the transcript's fetch reports to. As
 * iOS ServerStatusSourcesTests (tigerduck-app#890): the class table builds past terms from the
 * transcript, which comes from the score system, not course selection.
 */
class ServerStatusSourcesTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `the class table lists course selection, the score system, Moodle and TigerSync in that order`() {
        assertEquals(
            listOf(ServerKind.COURSE_SELECTION, ServerKind.SCORE_SYSTEM, ServerKind.MOODLE, ServerKind.BACKEND),
            ServerKind.classTableSources,
        )
    }

    @Test
    fun `the transcript page names the score system, not course selection`() {
        assertEquals(listOf(ServerKind.SCORE_SYSTEM), ServerKind.transcriptSources)
    }

    @Test
    fun `a transcript fetch records the score system's answer and leaves course selection alone`() {
        val answered = mutableMapOf<ServerKind, ServerStatus>()
        refresh(answered) { ScoreReport.EMPTY.copy(student = STUDENT) }
        assertEquals(ServerStatus.OK, answered[ServerKind.SCORE_SYSTEM])
        assertNull(answered[ServerKind.COURSE_SELECTION])

        val failed = mutableMapOf<ServerKind, ServerStatus>()
        refresh(failed) { throw NtustScoreError.InvalidResponse() }
        assertEquals(ServerStatus.FAILED, failed[ServerKind.SCORE_SYSTEM])
        assertNull(failed[ServerKind.COURSE_SELECTION])

        val unreachable = mutableMapOf<ServerKind, ServerStatus>()
        refresh(unreachable) { throw java.io.IOException("unreachable") }
        assertEquals(ServerStatus.FAILED, unreachable[ServerKind.SCORE_SYSTEM])
    }

    /** The score page's load, which refreshes once for the signed-in student, recording into [into]. */
    private fun refresh(into: MutableMap<ServerKind, ServerStatus>, fetch: () -> ScoreReport) {
        ScoreViewModel(
            authState = MutableStateFlow(true),
            storedStudentId = { STUDENT },
            storedPassword = { "not-a-password" },
            cachedSnapshot = { null },
            fetchReport = { _, _, _ -> fetch() },
            isOnline = { true },
            isDemo = { false },
            strings = { "string $it" },
            rosterCourses = { emptyList() },
            recordStatus = { server, status -> into[server] = status },
        )
    }

    private companion object {
        const val STUDENT = "TEST00001"
    }
}
