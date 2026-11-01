package org.ntust.app.tigerduck.coursechanges

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The store's side of the stored copy: the fixture `:shared` decodes, put where the current build
 * puts it, is what a new store reads off the disk.
 */
class StoredCourseChangesStoreTest {

    private val stored = File("../shared/src/test/resources/timeline/course_changes_v1.json").readText()

    @Test
    fun `the store reads a stored copy from the current build off the disk`() {
        val prefs = JvmContext().getSharedPreferences("stored_course_changes_test", 0).also { it.edit().clear().commit() }
        prefs.edit().putString("payload", stored).putString("etag", "W/\"1\"").putString("endpoint", "https://api.example.test/v3").commit()

        val store = CourseChangeStore(prefs, okhttp3.OkHttpClient(), { "https://api.example.test/v3" }, { true })

        assertEquals("1151", store.current().term)
        assertEquals(4, store.current().changes("CC900", thursday).size)
    }
}
