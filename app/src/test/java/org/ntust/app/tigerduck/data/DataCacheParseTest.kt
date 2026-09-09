package org.ntust.app.tigerduck.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course

class DataCacheParseTest {

    private val gson = Gson()
    private val courseListType = object : TypeToken<List<Course>>() {}.type

    @Test
    fun `valid JSON with sentinel token parses successfully`() {
        val json = """[{"courseNo":"CS101","courseName":"Algorithms"}]"""
        val result: List<Course>? = DataCache.parseJsonIfContains(
            json, DataCache.COURSE_NO_TOKEN, gson, courseListType,
        )
        assertEquals(1, result!!.size)
        assertEquals("CS101", result[0].courseNo)
    }

    @Test
    fun `JSON without sentinel token returns null`() {
        val json = """[{"a":"x","b":"y"}]"""
        val result: List<Course>? = DataCache.parseJsonIfContains(
            json, DataCache.COURSE_NO_TOKEN, gson, courseListType,
        )
        assertNull(result)
    }

    @Test
    fun `null requiredToken skips sentinel check`() {
        val json = """[{"a":"x","b":"y"}]"""
        val result: List<Map<String, String>>? = DataCache.parseJsonIfContains(
            json, null, gson, object : TypeToken<List<Map<String, String>>>() {}.type,
        )
        assertEquals(1, result!!.size)
        assertEquals("x", result[0]["a"])
    }

    @Test
    fun `courseNo as payload value (not key) is rejected`() {
        val json = """[{"x":"courseNo is mentioned"}]"""
        val result: List<Course>? = DataCache.parseJsonIfContains(
            json, DataCache.COURSE_NO_TOKEN, gson, courseListType,
        )
        assertNull(result)
    }

    @Test
    fun `malformed JSON returns null`() {
        val json = """not json at all"""
        val result: List<Course>? = DataCache.parseJsonIfContains(
            json, null, gson, courseListType,
        )
        assertNull(result)
    }

    /**
     * `score_<id>.json` is read across upgrades and `ScoreReportSnapshot`'s
     * fields were made nullable for exactly this reason (Gson bypasses the
     * constructor). A snapshot whose keys are absent or explicitly null must
     * parse into null fields rather than throw, so a stale or truncated file
     * degrades to "no cached report" instead of crashing the Score screen.
     */
    @Test
    fun `score report snapshot with absent or null fields parses to nulls`() {
        val type = object : TypeToken<DataCache.ScoreReportSnapshot>() {}.type
        val absent: DataCache.ScoreReportSnapshot? =
            DataCache.parseJsonIfContains("{}", null, gson, type)
        assertNull(absent!!.report)
        assertNull(absent.cachedAt)

        val explicitNull: DataCache.ScoreReportSnapshot? = DataCache.parseJsonIfContains(
            """{"report":null,"cachedAt":null}""", null, gson, type,
        )
        assertNull(explicitNull!!.report)
        assertNull(explicitNull.cachedAt)
    }
}
