package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The school sign-out takes the school account alone: the school mail and the library account stay
 * signed in, whoever's they are, as each account signs in and out by itself (decided for Android
 * on 2026-10-22). AuthService needs the Android Keystore, so this reads its source, as
 * ImportedClassTablesClearingTest does.
 */
class SchoolSignOutKeepsOtherAccountsTest {

    private val source: String = File(appDir(), "src/main/java/org/ntust/app/tigerduck/auth/AuthService.kt").readText()

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
    fun `the school sign-out leaves the school mail signed in`() {
        val logout = body("fun logout()")
        assertFalse(Regex("""\bmailAccount\.""").containsMatchIn(logout))
    }

    @Test
    fun `the school sign-out leaves the library account signed in`() {
        val logout = body("fun logout()")
        assertFalse(Regex("""clearLibraryCredentials|signOutLibrary""").containsMatchIn(logout))
    }
}
