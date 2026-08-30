package org.ntust.app.tigerduck.data.cache

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Course

/**
 * Covers the gap between `COURSE_NO_TOKEN` and an actually-usable row.
 *
 * The sentinel proves a cache file was written with un-obfuscated field names,
 * but only that the *key* is present. Gson's `Unsafe.allocateInstance` path
 * bypasses the Kotlin constructor, so a row whose `courseNo` is absent — or
 * explicitly null — deserializes into a `Course` holding null in a field the
 * type system declares non-null, and nothing checks it until
 * `TigerDuckTheme.courseHashIndex` calls `courseNo.fold {}`.
 *
 * These build the rows through Gson rather than the constructor on purpose:
 * the constructor cannot produce the state under test.
 */
class DataCacheUsableRowsTest {

    private val gson = Gson()

    private fun rows(json: String): List<Course> =
        gson.fromJson(json, object : TypeToken<List<Course>>() {}.type)

    @Test
    fun `a row with an explicitly null courseNo still satisfies the sentinel`() {
        val json = """[{"courseNo":null,"courseName":"課程"}]"""

        // This is why the sentinel alone was not enough.
        assertTrue(json.contains(DataCache.COURSE_NO_TOKEN))
        assertTrue(DataCache.usableRows(rows(json)).isEmpty())
    }

    @Test
    fun `a row missing courseNo entirely is dropped`() {
        val json = """[{"courseName":"課程"}]"""

        assertTrue(DataCache.usableRows(rows(json)).isEmpty())
    }

    @Test
    fun `a row missing courseName is dropped because displayName falls back to it`() {
        val json = """[{"courseNo":"AT1234"}]"""

        assertTrue(DataCache.usableRows(rows(json)).isEmpty())
    }

    @Test
    fun `well-formed rows survive untouched`() {
        val json = """[{"courseNo":"AT1234","courseName":"微積分"}]"""

        val kept = DataCache.usableRows(rows(json))

        assertEquals(1, kept.size)
        assertEquals("AT1234", kept.first().courseNo)
    }

    @Test
    fun `keeps the good rows and drops only the broken ones`() {
        val json = """
            [
              {"courseNo":"AT1234","courseName":"微積分"},
              {"courseNo":null,"courseName":"壞掉的"},
              {"courseNo":"AT5678","courseName":"線性代數"}
            ]
        """.trimIndent()

        val kept = DataCache.usableRows(rows(json))

        assertEquals(listOf("AT1234", "AT5678"), kept.map { it.courseNo })
    }

    /**
     * The regression: every surviving row must tolerate the dereference that
     * used to NPE deep inside colour assignment.
     */
    @Test
    fun `surviving rows can be dereferenced the way courseHashIndex does`() {
        val json = """[{"courseNo":null,"courseName":null},{"courseNo":"AT1234","courseName":"微積分"}]"""

        val kept = DataCache.usableRows(rows(json))

        // Would have thrown NullPointerException before the filter landed.
        val folded = kept.map { c -> c.courseNo.fold(0) { acc, ch -> acc * 31 + ch.code } }

        assertEquals(1, folded.size)
    }
}
