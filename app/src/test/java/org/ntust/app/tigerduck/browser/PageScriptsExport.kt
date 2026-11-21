package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the browser's page scripts that change how a page's own scripts behave to
 * `build/page-scripts`, where `tools/page-scripts/empty-window.test.cjs` runs them in jsdom: the JVM
 * has no JavaScript to run them in.
 */
class PageScriptsExport {

    @Test
    fun `writes the empty window's script for the jsdom run`() {
        val out = File("build/page-scripts").apply {
            deleteRecursively()
            mkdirs()
        }
        File(out, "empty-window.js").writeText(BrowserPageScripts.EMPTY_WINDOW)

        assertTrue(File(out, "empty-window.js").length() > 0)
    }
}
