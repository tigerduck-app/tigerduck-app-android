package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * When the class tables imported from share codes go: at the user's own sign-out, and when
 * another student ID signs in. Everything else that signs in reuses the stored credentials and
 * keeps them: [AuthService.attemptRelogin] after the backend session lapses, [StoredAccountSignIn]
 * when there is no backend session, and [AuthService.ensureAuthenticated] when the SSO session
 * expires. Nothing signs the user out on its own; only the sign-out clears the stored student ID.
 * AuthService needs the Android Keystore, so this reads its source and the sign-out's;
 * ImportedClassTableStoreTest covers what the two calls do.
 */
class ImportedClassTablesClearingTest {

    private val app: File = SignOutSource.appDir
    private val source: String = SignOutSource.source("auth/AuthService.kt")
    private val plan: String = SignOutSource.source(SignOutSource.PLAN)

    private fun body(declaration: String, of: String = source): String = SignOutSource.body(of, declaration)

    /**
     * The sign-out: what it clears at once, and the wipe it starts on the app scope, with the lists
     * of the account's stores they run written out.
     */
    private fun signOut() = SignOutSource.expanded(body("private fun clearAccount(", plan) + wipe)

    /** The wipe the sign-out starts on the app scope, which SignOutWipe runs. */
    private val wipe: String = SignOutSource.source("auth/session/SignOutWipe.kt")

    private fun String.uses(call: String): Int = Regex("""\bimportedClassTables\.$call""").findAll(this).count()

    @Test
    fun `the user's own sign-out empties the imported class tables`() {
        assertEquals(1, signOut().uses("""clearForSignOut\(\)"""))
    }

    @Test
    fun `the NTUST sign-in and the demo account's both name the student ID that signed in`() {
        // Once, in the step TypedAccountSignIn runs for both as the account shows as signed in;
        // TypedAccountSignInTest sees both run it.
        assertEquals(1, body("suspend fun login(").uses("""accountDidSignIn\(normalizedId\)"""))
    }

    @Test
    fun `nothing else in AuthService touches the imported class tables`() {
        assertEquals(source.uses(""), body("suspend fun login(").uses(""))
        // And the wipe is the one place that empties them, from the account's list of stores.
        assertEquals(1, SignOutSource.expanded(wipe).uses(""))
    }

    @Test
    fun `only AuthService empties the imported class tables or tells them who signed in`() {
        val owners = setOf("AuthService.kt", "ImportedClassTableStore.kt", "SignOutWipe.kt")
        val offenders = listOf("src/main/java", "src/play/java", "src/fdroid/java", "src/debug/java")
            .map { File(app, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name !in owners }
            .filter { file -> file.readText().let { "clearForSignOut(" in it || "accountDidSignIn(" in it } }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
