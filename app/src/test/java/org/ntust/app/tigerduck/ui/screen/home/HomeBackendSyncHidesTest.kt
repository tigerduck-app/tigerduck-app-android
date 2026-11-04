package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home's sync reads the hides when it starts and commits what its reconcile made of them term by
 * term, while a course selection answer may lift a deleted hand-added course's hide in between
 * (`DataCache.updateDeletedCourseNos`). Every write of the hides it makes goes through that
 * commit, so its older read cannot hide the course again.
 *
 * Read from the source, as `AddCourseTakeBackTest` does: the sync takes a dozen collaborators, and
 * what is pinned is which call writes the hides. The course list's step is `RosterReconciler`.
 */
class HomeBackendSyncHidesTest {

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
    fun `the sync writes the hides only as what it changed in them`() {
        // The course list's step, the first course sync before it, the legacy migration both pin the
        // hides through (LegacyHides.kt), and the commit the course list writes them through.
        val roster = source("sync/reconcile/RosterReconciler.kt")
        val firstSync = source("sync/reconcile/FirstSyncReconciler.kt")
        val legacy = source("sync/reconcile/LegacyHides.kt")
        val text = roster + firstSync + legacy + source("ui/screen/home/HomeBackendSync.kt")
        val migration = legacy.substringAfter("suspend fun DataCache.pinnedLegacyHides(").substringBefore("\n}\n")

        assertTrue("the legacy migration pins the hides as they are when it writes", "pinLegacyCourseHides(stored, rosters, signIn)" in migration)
        assertTrue("the course list pins through it", "dataCache.pinnedLegacyHides(semesters, signIn)" in roster)
        assertTrue("so does the first course sync", "dataCache.pinnedLegacyHides(" in firstSync)
        assertFalse("nothing writes a whole older set back", "dataCache.saveDeletedCourseNos(tombstones)" in text)
        assertFalse("not even the migration", "saveDeletedCourseNos(migrated)" in text)
    }
}
