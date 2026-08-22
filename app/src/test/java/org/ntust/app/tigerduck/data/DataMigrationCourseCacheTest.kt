package org.ntust.app.tigerduck.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the schema 2 → 3 sweep: dropping course caches that were written
 * while 選課 served a term the month heuristic hadn't rolled over to yet.
 */
class DataMigrationCourseCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, body: String = """[{"courseNo":"AT1234"}]""") =
        File(dir, name).apply { writeText(body) }

    @Test
    fun `drops every semester-scoped course cache`() {
        val dir = tmp.newFolder("TigerDuckCache")
        val spring = write(dir, "courses_1142.json")
        val fall = write(dir, "courses_1151.json")

        DataMigration.deleteCourseCaches(dir)

        assertFalse(spring.exists())
        assertFalse(fall.exists())
    }

    @Test
    fun `drops the pre-semester-scoped legacy cache too`() {
        val dir = tmp.newFolder("TigerDuckCache")
        val legacy = write(dir, "courses.json")

        DataMigration.deleteCourseCaches(dir)

        assertFalse(legacy.exists())
    }

    @Test
    fun `deletes well-formed caches, unlike the content-sniffing v1_4_0 sweep`() {
        // The wrong-semester payload is indistinguishable from a correct one
        // at the file level — both carry un-obfuscated `"courseNo":` keys — so
        // this step must not gate on content the way migrate1to2 does.
        val dir = tmp.newFolder("TigerDuckCache")
        val healthy = write(dir, "courses_1151.json", """[{"courseNo":"AT1234","displayName":"X"}]""")

        DataMigration.deleteCourseCaches(dir)

        assertFalse(healthy.exists())
    }

    @Test
    fun `leaves manual courses and unrelated caches alone`() {
        // Manual courses have no remote source to rebuild from — deleting them
        // would destroy timetable rows the user typed in by hand.
        val dir = tmp.newFolder("TigerDuckData")
        val manual = write(dir, "manual_courses_1151.json")
        val assignments = write(dir, "assignments.json")

        DataMigration.deleteCourseCaches(dir)

        assertTrue(manual.exists())
        assertTrue(assignments.exists())
    }

    @Test
    fun `a missing directory is a no-op`() {
        DataMigration.deleteCourseCaches(File(tmp.root, "does-not-exist"))
    }
}
