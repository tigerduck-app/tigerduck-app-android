package org.ntust.app.tigerduck.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The course changes are asked for nothing before onboarding ends, and the return to the app that
 * fetches them does not come at its end; so finishing onboarding fetches them. Read from the
 * source, as AppState's dependencies are too many to build here.
 */
class OnboardingCourseChangesTest {

    private fun source(): String {
        val rel = "src/main/java/org/ntust/app/tigerduck/ui/AppState.kt"
        return listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("Could not locate AppState.kt from ${File("").absolutePath}")
    }

    @Test
    fun `finishing onboarding fetches the school's course changes`() {
        val text = source()
        val start = text.indexOf("fun completeOnboarding()")
        assertTrue("AppState has no completeOnboarding", start >= 0)
        val body = text.substring(start, text.indexOf("\n    }\n", start))
        assertTrue(body, "courseChangeStore.refresh()" in body)
    }
}
