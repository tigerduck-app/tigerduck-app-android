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
 * what is pinned is which call writes the hides.
 */
class HomeBackendSyncHidesTest {

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
    fun `the sync writes the hides only as what it changed in them`() {
        val text = source()
        val migration = text.substringAfter("private suspend fun migratedTombstones(").substringBefore("\n    }\n")

        assertTrue("the legacy migration pins the hides as they are when it writes", "dataCache.pinLegacyCourseHides(stored, rosters)" in migration)
        assertFalse("nothing writes a whole older set back", "dataCache.saveDeletedCourseNos(tombstones)" in text)
        assertFalse("not even the migration", "dataCache.saveDeletedCourseNos(migrated)" in text)
    }
}
