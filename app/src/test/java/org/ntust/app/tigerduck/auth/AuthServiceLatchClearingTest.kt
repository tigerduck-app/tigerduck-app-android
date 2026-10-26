package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * AuthService lifts the stored-password latch on sign-out, as the refusal belonged to the password
 * leaving with the account. AuthService needs the Android Keystore, which no JVM test can open, so
 * this reads its source, as ImportedClassTablesClearingTest does; StoredPasswordLatchTest covers
 * what the call does. A sign-in lifts it as it stores the new password: TypedAccountSignInTest.
 */
class AuthServiceLatchClearingTest {

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
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        throw AssertionError("`$declaration` has no closing brace")
    }

    @Test
    fun `the sign-out lifts the latch`() {
        assertEquals(1, Regex("""\bstoredPasswordLatch\.clear\(\)""").findAll(body("fun logout()")).count())
    }
}
