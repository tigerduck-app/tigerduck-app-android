package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A course reset made on another device brings back the courses this phone hid, and its wipe here
 * (`applyCourseReset`) clears the hides and the waiting adds. A delete still waiting to go up
 * from before the fetch that read the reset went with them: sent on a later retry, it took a
 * restored course off the server again. So the sync takes a stamp before its fetch, and the reset
 * lets go of the deletes asked before it under the adds lock, as a term reset does
 * (`PendingCourseDeletes.forgetTerm`).
 *
 * Read from the source, as [HomeBackendSyncHidesTest] does: the sync takes a dozen collaborators,
 * and what is pinned is where the stamp is taken and under which lock the deletes go.
 */
class HomeBackendSyncResetTest {

    private fun source(): String {
        val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/home/HomeBackendSync.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readText() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate HomeBackendSync.kt from ${File("").absolutePath}")
    }

    @Test
    fun `a reset read from another device lets go of the deletes asked before its fetch, under the adds lock`() {
        val text = source()
        val pull = text.substringAfter("suspend fun pull(").substringBefore("\n    }\n")
        val reset = text.substringAfter("private suspend fun applyCourseReset(").substringBefore("\n    }\n")

        val stamp = pull.indexOf("val beforeFetch = pendingCourseDeletes.stamp()")
        val fetch = pull.indexOf("syncApiClient.fetchFullSync()")
        assertTrue("the stamp is taken before the fetch", stamp in 0 until fetch)
        assertTrue("the reset is given that stamp", "applyCourseReset(result, account, beforeFetch, courseSync)" in pull)
        assertTrue(
            "the deletes asked before it go, under the adds lock, once the reset applies",
            Regex("""(?s)if \(reset\.apply\) \{.*?pendingCourseAdds\.removing \{\s*pendingCourseDeletes\.forgetAskedBefore\(beforeFetch\)""")
                .containsMatchIn(reset),
        )
    }
}
