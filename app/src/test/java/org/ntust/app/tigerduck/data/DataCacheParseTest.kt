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
}
