package org.ntust.app.tigerduck.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val acceptCourses: (String) -> Boolean = { name ->
        name == "courses.json" || (name.startsWith("courses_") && name.endsWith(".json"))
    }

    @Test
    fun `file containing sentinel is kept`() {
        val dir = tempFolder.newFolder("cache")
        val file = File(dir, "courses_2024.json")
        file.writeText("""{"courseNo":"CS101","courseName":"Algorithms"}""")

        DataMigration.sweepCourseFiles(dir, acceptCourses)

        assertTrue("File with sentinel should not be deleted", file.exists())
    }

    @Test
    fun `file without sentinel is deleted`() {
        val dir = tempFolder.newFolder("cache_obfuscated")
        val file = File(dir, "courses_2024.json")
        file.writeText("""{"a":"x","b":"y"}""")

        DataMigration.sweepCourseFiles(dir, acceptCourses)

        assertFalse("File with obfuscated keys should be deleted", file.exists())
    }

    @Test
    fun `file whose name does not match accept predicate is untouched`() {
        val dir = tempFolder.newFolder("cache_mixed")
        val file = File(dir, "other.json")
        // No sentinel — would be deleted if accepted
        file.writeText("""{"a":"x","b":"y"}""")

        DataMigration.sweepCourseFiles(dir, acceptCourses)

        assertTrue("File rejected by accept predicate should remain untouched", file.exists())
    }

    @Test
    fun `non-existent directory does not crash`() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        // Should return without throwing
        DataMigration.sweepCourseFiles(nonExistent, acceptCourses)
    }

    @Test
    fun `legacy courses json without sentinel is deleted`() {
        val dir = tempFolder.newFolder("cache_legacy")
        val file = File(dir, "courses.json")
        file.writeText("""{"a":"x","b":"y"}""")

        DataMigration.sweepCourseFiles(dir, acceptCourses)

        assertFalse("Legacy courses.json with obfuscated keys should be deleted", file.exists())
    }

    @Test
    fun `legacy courses json with sentinel is kept`() {
        val dir = tempFolder.newFolder("cache_legacy_valid")
        val file = File(dir, "courses.json")
        file.writeText("""{"courseNo":"CS101","courseName":"Algorithms"}""")

        DataMigration.sweepCourseFiles(dir, acceptCourses)

        assertTrue("Legacy courses.json with sentinel should be kept", file.exists())
    }

    @Test
    fun `file that mentions courseNo as value without key token is deleted`() {
        val dir = tempFolder.newFolder("cache_value_mention")
        val file = File(dir, "courses_2024.json")
        // Contains the word "courseNo" inside a string value — not as a JSON key.
        file.writeText("""{"x":"courseNo is mentioned"}""")

        DataMigration.sweepCourseFiles(dir, acceptCourses)

        assertFalse(
            "File containing courseNo only as a payload value (not the key token) should be deleted",
            file.exists()
        )
    }

    /** Step 5 to 6: the sync clock builds before `age_ms` kept goes, and nothing else. */
    @Test
    fun `the old sync clock's file is deleted, and only it`() {
        val context = org.ntust.app.tigerduck.util.JvmContext()
        context.getSharedPreferences("tigerduck_sync_clock", 0).edit().putLong("lastIssuedMs", 1_790_000_000_000L).putLong("lastSeenUs", 1L).commit()
        context.getSharedPreferences("tigerduck_outbox", 0).edit().putLong("firstRunMs", 5L).commit()

        DataMigration.deleteLegacySyncClock(context)
        DataMigration.deleteLegacySyncClock(context)

        assertTrue(context.getSharedPreferences("tigerduck_sync_clock", 0).all.isEmpty())
        assertTrue(context.getSharedPreferences("tigerduck_outbox", 0).contains("firstRunMs"))
    }
}
