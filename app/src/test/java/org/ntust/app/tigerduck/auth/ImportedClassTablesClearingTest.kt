package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * When the class tables imported from share codes go: at the user's own sign-out, and when
 * another student ID signs in. Everything else that signs in reuses the stored credentials and
 * keeps them: [AuthService.attemptRelogin] after the backend session lapses, [StoredAccountSignIn]
 * when there is no backend session, and [AuthService.ensureAuthenticated] when the SSO session
 * expires. Nothing signs the user out on its own; only [AuthService.logout] clears the stored
 * student ID. AuthService needs the Android Keystore, so this reads its source;
 * ImportedClassTableStoreTest covers what the two calls do.
 */
class ImportedClassTablesClearingTest {

    private val app: File = appDir()
    private val source: String = File(app, "src/main/java/org/ntust/app/tigerduck/auth/AuthService.kt").readText()

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

    private fun String.uses(call: String): Int = Regex("""\bimportedClassTables\.$call""").findAll(this).count()

    @Test
    fun `the user's own sign-out empties the imported class tables`() {
        assertEquals(1, body("fun logout()").uses("""clearForSignOut\(\)"""))
    }

    @Test
    fun `the NTUST sign-in and the demo account's both name the student ID that signed in`() {
        // Once, in the step TypedAccountSignIn runs for both as the account shows as signed in;
        // TypedAccountSignInTest sees both run it.
        assertEquals(1, body("suspend fun login(").uses("""accountDidSignIn\(normalizedId\)"""))
    }

    @Test
    fun `nothing else in AuthService touches the imported class tables`() {
        val everywhere = source.uses("")
        assertEquals(everywhere, body("fun logout()").uses("") + body("suspend fun login(").uses(""))
    }

    @Test
    fun `only AuthService empties the imported class tables or tells them who signed in`() {
        val owners = setOf("AuthService.kt", "ImportedClassTableStore.kt")
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
