package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [LibraryService] writes and reads the library API's request and response classes with Gson,
 * which matches JSON keys to field names, and none of them names its fields with
 * `@SerializedName`. Every app that minifies this module must keep them whole, so the rule ships
 * with the module rather than being left to each app: the watch had none, and its release builds
 * posted `{"a":…}` and read every answer as empty.
 */
class ConsumerRulesTest {

    @Test
    fun `the module hands its keep rules to every app that minifies it`() {
        val build = File("build.gradle.kts").readText()
        assertTrue(Regex("""consumerProguardFiles\(\s*"consumer-rules\.pro"\s*\)""").containsMatchIn(build))
        val rules = File("consumer-rules.pro").takeIf { it.exists() }?.readLines()?.map { it.trim() }.orEmpty()
        assertTrue(rules.contains("-keep class org.ntust.app.tigerduck.shared.** { *; }"))
    }
}
