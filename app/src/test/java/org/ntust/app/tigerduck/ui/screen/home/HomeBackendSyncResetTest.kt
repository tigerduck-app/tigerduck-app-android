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
 * and what is pinned is where the stamp is taken and under which lock the deletes go. The sync is
 * the `Synchronizer`'s, and the reset its `CourseResetReconciler`.
 */
class HomeBackendSyncResetTest {

    private fun source(file: String): String {
        val rel = "src/main/java/org/ntust/app/tigerduck/$file"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readText() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $file from ${File("").absolutePath}")
    }

    @Test
    fun `a reset read from another device lets go of the deletes asked before its fetch, under the adds lock`() {
        val pull = source("sync/Synchronizer.kt").substringAfter("suspend fun sync(").substringBefore("\n    }\n")
        val steps = source("sync/ForegroundSteps.kt")
        val step = source("sync/reconcile/CourseResetReconciler.kt")
        val reset = step.substringAfter("private suspend fun applyCourseReset(").substringBefore("\n    }\n")

        val stamp = pull.indexOf("val beforeFetch = steps.stampDeletes()")
        val fetch = pull.indexOf("steps.fetch(")
        assertTrue("the stamp is taken before the fetch", stamp in 0 until fetch)
        assertTrue("the stamp is the deletes'", "stampDeletes(): Long = pendingCourseDeletes.stamp()" in steps)
        assertTrue("the pass carries it", "SyncPass(result, signIn, mode, steps.policy(), state, courseSync, beforeFetch," in pull)
        assertTrue("the reset is given that stamp", "applyCourseReset(pass.result, pass.beforeFetch, pass.courseSync, pass.signIn)" in step)
        assertTrue(
            "the deletes asked before it go, under the adds lock, once the reset applies",
            Regex("""(?s)if \(reset\.apply\) \{.*?pendingCourseAdds\.removing \{\s*pendingCourseDeletes\.forgetAskedBefore\(beforeFetch, signIn\)""")
                .containsMatchIn(reset),
        )
    }
}
