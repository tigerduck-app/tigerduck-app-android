package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R8 drops the android.util.Log calls that proguard-rules.pro names in its
 * -assumenosideeffects block. Verbose, debug and info lines print course
 * numbers, ids and server replies, so release builds drop them; warnings and
 * errors stay for bug reports.
 */
class ReleaseLogRulesTest {

    @Test
    fun `release builds drop verbose, debug and info logs and keep warnings and errors`() {
        // Every block, so a second one that also names w or e fails too.
        val blocks = Regex("""-assumenosideeffects\s+class\s+android\.util\.Log\s*\{([^}]*)\}""")
            .findAll(File("proguard-rules.pro").readText())
            .map { block -> block.groupValues[1].lines().map { it.trim() }.filter { it.isNotEmpty() } }
            .toList()
        assertEquals(
            listOf(
                listOf(
                    "public static int v(...) return 0;",
                    "public static int d(...) return 0;",
                    "public static int i(...) return 0;",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `release builds keep the class names warnings and errors log`() {
        // They name a failure by logSummary(), and no release keeps the R8 mapping
        // that would decode an obfuscated name.
        assertTrue(
            File("proguard-rules.pro").readLines().map { it.trim() }
                .contains("-keepnames class * extends java.lang.Throwable"),
        )
    }
}
