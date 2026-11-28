package org.ntust.app.tigerduck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No link opens in a Custom Tab: "Open links with" in app is the TigerDuck browser's links profile
 * ([openLink]), and a Custom Tab would hand the page to another browser's cookies, which the app can
 * neither see nor clear at a sign-out. A source check, as the dependency is gone too.
 */
class NoCustomTabsTest {

    private val roots = listOf("src/main", "src/play", "src/fdroid").map(::File).filter { it.isDirectory }

    private val sources: Map<String, String> = roots
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") } }
        .associate { it.invariantSeparatorsPath to it.readText() }

    @Test
    fun `the sources were found`() {
        assertEquals(3, roots.size)
        assertTrue(sources.keys.any { it.endsWith("ui/OpenLink.kt") })
    }

    @Test
    fun `nothing in the app names a Custom Tab`() {
        val pattern = Regex("""CustomTabsIntent|androidx\.browser\.customtabs""")
        assertEquals(emptyList<String>(), sources.filterValues(pattern::containsMatchIn).keys.sorted())
    }

    @Test
    fun `the app does not depend on androidx browser`() {
        val build = File("build.gradle.kts").readText()
        assertTrue(build.lines().none { it.trim().startsWith("implementation(libs.androidx.browser)") })
    }
}
