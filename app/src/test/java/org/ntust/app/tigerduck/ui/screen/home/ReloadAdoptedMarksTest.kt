package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Home takes up the marks a re-enable answer wrote ([reloadAdoptedMarks]),
 * so a swipe after Use Server no longer writes the marks it held before back
 * to disk (tigerduck-app-android#189).
 */
class ReloadAdoptedMarksTest {

    private var applied: Pair<Set<String>, Set<String>>? = null
    private var taps = 0
    private var signedIn = true

    private suspend fun reload(duringRead: () -> Unit = {}) = reloadAdoptedMarks(
        read = {
            duringRead()
            setOf("server-ignored") to setOf("server-done")
        },
        tapsMade = { taps },
        stillSignedIn = { signedIn },
        apply = { ignored, completed -> applied = ignored to completed },
    )

    @Test
    fun `the adopted marks replace the ones Home holds`() = runTest {
        reload()

        assertEquals(setOf("server-ignored") to setOf("server-done"), applied)
    }

    @Test
    fun `a swipe made while the files were read is not undone`() = runTest {
        reload(duringRead = { taps++ })

        assertNull(applied)
    }

    @Test
    fun `marks read across a sign-out are the departing account's and are dropped`() = runTest {
        reload(duringRead = { signedIn = false })

        assertNull(applied)
    }
}
