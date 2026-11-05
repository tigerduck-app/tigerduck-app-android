package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the user's own sign-out leaves for the next student. AuthService needs the Android Keystore,
 * so this reads the source of the sign-out and of `clearAccount`, what it runs, with the lists of
 * SchoolStores it runs written out ([SignOutSource]), as ImportedClassTablesClearingTest does.
 */
class SignOutClearingTest {

    /** The school's plan, from its class on: the file holds the other accounts' plans before it. */
    private val source: String = SignOutSource.source(SignOutSource.PLAN).let { it.substring(it.indexOf("internal class SchoolSignOut")) }

    private fun appDir(): File = SignOutSource.appDir

    /** The body of the function [declaration] starts, up to its matching brace. */
    private fun body(declaration: String): String = SignOutSource.body(source, declaration)

    /** What a sign-out clears at once, which the recovery runs too. */
    private fun clearing() = body("private fun clearAccount(")

    /** The wipe logout() starts on the app scope, which SignOutWipe runs. */
    private fun wipeBody(): String {
        val wipe = SignOutSource.source("auth/session/SignOutWipe.kt")
        val start = wipe.indexOf("suspend fun run(")
        return wipe.substring(wipe.indexOf('{', start))
    }

    /** What the sign-out clears at once, then its wipe, with each list of the account's stores written out where it runs. */
    private fun signOut() = SignOutSource.expanded(clearing() + wipeBody())

    @Test
    fun `the sign-out runs in this order`() {
        // The order phase 2 keeps while it moves the pieces: the account goes first, and the wipe
        // follows the demo's exit and the bulletins, on the app scope.
        // The wipe is its own function, which logout() starts last.
        val logout = signOut()
        val steps = listOf(
            "credentials.signOutSchoolAccount()",
            "authTokenManager.logout()",
            "notificationSettingsSync.cancelPendingPushes()",
            "courseService.cancelSharedFetches()",
            "moodleService.cancelSharedFetches()",
            "sessionManager.signOut()",
            "prefs.clearSyncStamps()",
            // The account shows as signed out: AuthService's setSignedIn(false), told with the reason.
            "auth.showSignedOut(reason)",
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
    }

    @Test
    fun `the sign-out's Show step clears the sign-in's error, then shows the account as signed out with its reason`() {
        val show = SignOutSource.body(SignOutSource.source("auth/AuthService.kt"), "internal fun showSignedOut(")
        val at = listOf("_loginError.value = null", "setSignedIn(false, reason = reason)").map { step ->
            show.indexOf(step).also { assertTrue("showSignedOut() calls $step", it >= 0) }
        }
        assertEquals(at.sorted(), at)
    }

    @Test
    fun `a sign-out counts itself in before anything is cleared, and starts its wipe after`() {
        val logout = body("override fun signOut(")
        val at = listOf("synchronized(accountLock) { wipe.begin(credentials.ntustStudentId) }", "wipe.finish(cleared = clearAccount(reason))").map { step ->
            logout.indexOf(step).also { assertTrue("logout() calls $step", it >= 0) }
        }
        assertEquals(at.sorted(), at)
    }

    @Test
    fun `the recovery has no list of its own to fall behind the sign-out's`() {
        assertTrue("clearAccount" in source.substringAfter("override fun finishOwedSignOut()").substringBefore("\n\n"))
        // Each clearing once in the whole file: in clearAccount, the function both run.
        val clearings = listOf(
            """credentials\.signOutSchoolAccount\(\)""",
            """authTokenManager\.logout\(\)""",
            """notificationSettingsSync\.cancelPendingPushes\(\)""",
            // The account's stores, one list for both.
            """stores\.atOnce""",
            """pushRegistration\.unregister\(""",
        )
        clearings.forEach { assertEquals(it, 1, Regex(it).findAll(source).count()) }
    }

    /** AuthTokenManager.login refuses an ended sign-in as well, so only the source can show this one. */
    @Test
    fun `a typed sign-in falls back to the stored Moodle token only while its sign-in stands`() {
        val auth = SignOutSource.source("auth/AuthService.kt")
        assertTrue("val stillCurrent = { signIn.isCurrent }" in auth)
        assertTrue("stillCurrent = stillCurrent," in auth)
    }

    @Test
    fun `the start finishes a sign-out before anything acts on the account, and wipes after the migration`() {
        val app = File(appDir(), "src/main/java/org/ntust/app/tigerduck/TigerDuckApp.kt").readText()
        val onCreate = app.substringAfter("override fun onCreate() {")
        val afterSuper = onCreate.substringAfter("super.onCreate()\n").lines().map { it.trim() }.filterNot { it.startsWith("//") }
        assertTrue(afterSuper.first(), afterSuper.first().startsWith("val signOutCutShort = signOutPlans.finishOwedSignOut()"))
        val at = listOf(
            "dataMigration.run()",
            "if (signOutCutShort) signOutPlans.finishOwedWipe()",
            "ClassReminders.migrateIfNeeded",
            "accountSessionEffects.start(appScope, signOutCutShort)",
        ).map { step -> onCreate.indexOf(step).also { assertTrue("onCreate calls $step", it >= 0) } }
        assertEquals(at.sorted(), at)
    }

    @Test
    fun `the next sign-in opens only from the wipe's last pass, never from logout`() {
        val wipe = File(appDir(), "src/main/java/org/ntust/app/tigerduck/auth/session/SignOutWipe.kt").readText()
        assertEquals(wipe, 1, Regex("""session\.open\(\)""").findAll(wipe.substringBefore("/**\n     * At process start, before")).count())
        // logout() starts it, and nothing in logout() ends or opens the sign-in itself.
        val logout = body("override fun signOut(") + clearing()
        assertTrue(logout, "wipe.finish(" in logout)
        assertFalse(logout, "signIns.end()" in logout || "session.open()" in logout)
    }

    @Test
    fun `the sign-out forgets the term picked in the class table`() {
        // Left behind, the next student's class table would open on the previous student's term.
        assertEquals(1, Regex("""\bprefs\.forgetSchoolChoices\(\)""").findAll(signOut()).count())
        val choices = SignOutSource.body(SignOutSource.source("data/preferences/AppPreferences.kt"), "fun forgetSchoolChoices()")
        assertEquals(1, Regex("""\bschool\.classTableSelectedSemester\.clear\(\)""").findAll(choices).count())
    }

    @Test
    fun `the sign-out forgets the departing student's holiday choices`() {
        // Left behind, the next student would inherit their quiet days, and the waiting ones would go up as theirs.
        assertEquals(1, Regex("""\bprefs\.forgetHolidayOverrides\(\)""").findAll(signOut()).count())
    }

    @Test
    fun `the widgets are drawn again once the departing student's timetable is wiped`() {
        // Not before: a widget drawn from the cache still on disk would keep the timetable.
        val logout = signOut()
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
        val logout = clearing()
        val takenAsItDrops = Regex(
            """synchronized\(accountLock\) \{\s*must\("credentials"\) \{ credentials\.signOutSchoolAccount\(\) \}""" +
                """\s*must\("tokens"\) \{ departing = authTokenManager\.logout\(\) \}\s*\}""",
        )
        assertTrue(logout, takenAsItDrops.containsMatchIn(logout))
        assertFalse(logout, "departingSession()" in logout)
    }

    @Test
    fun `everything the clearing does is a step, so a throw in one skips no other`() {
        val bare = clearing().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val notAStep = bare.filterNot {
            Regex("""^(must|once)\("[^"]+"\) \{ .* \}$""").matches(it) ||
                it in listOf(
                    "var departing: DepartingSession? = null",
                    "synchronized(accountLock) {",
                    // Each store of the list is a step of its own.
                    "stores.atOnce.forEach { store -> must(store.javaClass.simpleName) { store.forgetAccount() } }",
                    "}",
                    // The function's answer.
                    "cleared",
                )
        }
        assertEquals(emptyList<String>(), notAStep)
    }

    @Test
    fun `the school credentials' clearing leaves the session to the sign-out that takes it`() {
        val credentials = File(appDir(), "src/main/java/org/ntust/app/tigerduck/data/preferences/CredentialManager.kt").readText()
        val clear = credentials.substringAfter("fun clearNtustCredentials()").substringBefore("\n    }")
        assertFalse(clear, "V3TokenStore." in clear)
    }
}
