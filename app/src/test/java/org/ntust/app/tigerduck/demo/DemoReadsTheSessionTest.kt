package org.ntust.app.tigerduck.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reads a JVM test cannot run: these classes take Hilt's whole graph, or an Activity. What
 * they pin is that each still asks the session, where the code it guards is; read from the source,
 * as ClassTableWiringTest does.
 */
class DemoReadsTheSessionTest {

    private fun source(path: String) = File("src/main/java/org/ntust/app/tigerduck/$path").readText()

    private fun assertAsksBefore(path: String, read: String, guarded: String) {
        val text = source(path)
        val asked = text.indexOf(read)
        assertTrue("$path no longer asks $read", asked >= 0)
        assertTrue("$path asks only after $guarded", asked < text.indexOf(guarded, asked))
    }

    @Test
    fun `a library sign-in in the demo fails before it reaches the library`() {
        assertAsksBefore("ui/screen/library/LibraryViewModel.kt", "if (session.isDemo) {", "libraryService.login(")
        assertAsksBefore("ui/screen/settings/SettingsViewModel.kt", "if (sessions.school.isDemo) {", "libraryService.login(")
    }

    @Test
    fun `the review prompt never asks in the demo`() {
        assertTrue("!session.isDemo &&" in source("review/ReviewPrompt.kt"))
    }

    @Test
    fun `the class table warms no term in the demo`() {
        assertTrue(
            "mayFetch = { authService.canAskSchool && networkChecker.isAvailable() }" in
                source("ui/screen/classtable/ClassTableViewModel.kt"),
        )
    }

    @Test
    fun `the browser's Moodle key and private token are not asked for in the demo`() {
        val autologin = source("network/moodle/MoodleAutologin.kt")
        assertTrue("if (session.isDemo) null else requestKey(" in autologin)
        assertTrue("canFetchPrivateToken = { !session.isDemo &&" in autologin)
    }

    @Test
    fun `the finder shows the demo's rooms on every weekday`() {
        // The store's calendar reads empty in a test too, so only its wiring can be pinned.
        assertTrue(
            "finderCalendar(sessions.school.isDemo, academicCalendar::current)" in
                source("ui/screen/emptyclassroom/FinderEnvironment.kt"),
        )
    }

    @Test
    fun `what is decided once per process is sampled once, from the session`() {
        assertEquals(1, Regex("""private val demoMode = sessions\.school\.isDemo""").findAll(source("auth/AuthService.kt")).count())
        assertEquals(1, Regex("""private val demoMode = session\.isDemo""").findAll(source("ui/AppState.kt")).count())
    }
}
