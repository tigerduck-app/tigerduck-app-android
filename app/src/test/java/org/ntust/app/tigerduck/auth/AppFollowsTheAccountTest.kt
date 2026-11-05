package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The application's own reactors to the school account: a Hilt app no test builds, so read. What
 * each follows and how is held by AccountEventsTest; this holds that they do, subscribed from
 * the start, and that the first step of the slow one leaves the thread [TigerDuckApp.onCreate] is on.
 */
class AppFollowsTheAccountTest {
    private fun source(): String {
        val rel = "src/main/java/org/ntust/app/tigerduck/TigerDuckApp.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readText() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate TigerDuckApp.kt from ${File("").absolutePath}")
    }

    @Test
    fun `the sync dot and the library's read follow each change, from before the first run`() {
        val following = Regex("""appScope\.follow\(sessions\.school\.changes\)""").findAll(source()).count()

        assertEquals(2, following)
    }

    @Test
    fun `reading the stores leaves the thread the app starts on`() {
        val text = source().substringAfter("private fun followSchoolAccount()")
        val hop = text.indexOf("withContext(Dispatchers.Default)")
        val read = text.indexOf("librarySession.refresh()")

        assertTrue("the encrypted stores would be read on the main thread", hop in 0 until read)
    }
}
