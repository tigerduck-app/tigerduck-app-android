package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the user's own sign-out leaves for the next student. AuthService needs the Android
 * Keystore, so this reads [AuthService.logout]'s source, as ImportedClassTablesClearingTest does.
 */
class SignOutClearingTest {

    private val source: String =
        File(appDir(), "src/main/java/org/ntust/app/tigerduck/auth/AuthService.kt").readText()

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = dir
            if (File(here, "app/src/main/java").isDirectory) return File(here, "app")
            if (File(here, "src/main/java").isDirectory) return here
            dir = here.parentFile
        }
        throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
    }

    /** The body of the function [declaration] starts, up to its matching brace. */
    private fun body(declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) throw AssertionError("AuthService has no `$declaration`")
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return source.substring(open + 1, index).also {
                        assertFalse("`$declaration` ran into the next function", Regex("""\n {4}\S""").containsMatchIn(it))
                    }
                }
            }
        }
        throw AssertionError("`$declaration` has no closing brace")
    }

    @Test
    fun `the sign-out runs in this order`() {
        // The order phase 2 keeps while it moves the pieces: the account goes first, and the wipe
        // follows the demo's exit and the bulletins, on the app scope.
        val logout = body("fun logout()")
        val steps = listOf(
            "credentials.signOutSchoolAccount()",
            "authTokenManager.logout()",
            "notificationSettingsSync.cancelPendingPushes()",
            "courseService.cancelSharedFetches()",
            "moodleService.cancelSharedFetches()",
            "sessionManager.signOut()",
            "prefs.clearSyncStamps()",
            "setSignedIn(false)",
            "pushRegistration.unregister(departing)",
            "demoAccount.exit()",
            "bulletinCache.clearIn(appScope)",
            "importedClassTables.clearForSignOut()",
            "dataCache.clearAllUserData()",
            "widgetUpdater.updateAll()",
        )
        val at = steps.map { step ->
            logout.indexOf(step).also { assertTrue("logout() calls $step", it >= 0) }
        }
        assertEquals("logout() calls its steps in this order", at.sorted(), at)
        assertEquals(at.size, at.toSet().size)
        // Between those steps: the read state goes after the session, and the empty-classroom
        // finder is forgotten before it.
        assertTrue(logout.indexOf("bulletinReadStateStore.clear()") in at[5]..at[7])
        assertTrue(logout.indexOf("forgetEmptyClassroom(classroomUsage, prefs)") in at[4]..at[5])
        // The wipe runs on the app scope, after everything above has been started.
        assertTrue(logout.indexOf("appScope.launch") in at[10]..at[11])
    }

    @Test
    fun `the sign-out forgets the term picked in the class table`() {
        // Left behind, the next student's class table would open on the previous student's term.
        assertEquals(1, Regex("""\bprefs\.classTableSelectedSemester = null\b""").findAll(body("fun logout()")).count())
    }

    @Test
    fun `the sign-out forgets the departing student's holiday choices`() {
        // Left behind, the next student would inherit their quiet days, and the waiting ones would go up as theirs.
        assertEquals(1, Regex("""\bprefs\.forgetHolidayOverrides\(\)""").findAll(body("fun logout()")).count())
    }

    @Test
    fun `the widgets are drawn again once the departing student's timetable is wiped`() {
        // Not before: a widget drawn from the cache still on disk would keep the timetable.
        val logout = body("fun logout()")
        val wiped = logout.indexOf("dataCache.clearAllUserData()")
        val redrawn = logout.indexOf("widgetUpdater.updateAll()")
        assertTrue("logout() wipes the cache", wiped >= 0)
        assertTrue("logout() redraws the widgets after the wipe", redrawn > wiped)
    }

    @Test
    fun `the departing session is the one the sign-out wipes, taken as it moves the generation`() {
        // Taken before, a refresh landing in between would store a pair the unregister does not
        // hold, and the token it holds would have been replaced. In the same step as the school
        // credentials go, under the lock a sign-in under way checks the generation with.
        val logout = body("fun logout()")
        val takenAsItDrops = Regex(
            """val departing = synchronized\(accountLock\) \{\s*credentials\.signOutSchoolAccount\(\)\s*authTokenManager\.logout\(\)\s*\}""",
        )
        assertTrue(logout, takenAsItDrops.containsMatchIn(logout))
        assertFalse(logout, "departingSession()" in logout)
    }

    @Test
    fun `the school credentials' clearing leaves the session to the sign-out that takes it`() {
        val credentials = File(appDir(), "src/main/java/org/ntust/app/tigerduck/data/preferences/CredentialManager.kt").readText()
        val clear = credentials.substringAfter("fun clearNtustCredentials()").substringBefore("\n    }")
        assertFalse(clear, "V3TokenStore." in clear)
    }
}
