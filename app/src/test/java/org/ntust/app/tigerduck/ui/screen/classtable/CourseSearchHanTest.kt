package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.coursecatalog.CatalogFixture
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Simplified characters match the zh catalog, which names its courses in Traditional ones only.
 * Under Robolectric, whose framework has ICU's transliterator; the plain JVM has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseSearchHanTest {

    @Test
    fun `simplified characters match the zh catalog as traditional`() {
        val catalogs = mapOf("zh" to CatalogFixture.catalog("zh").courses, "en" to CatalogFixture.catalog("en").courses)

        val found = CourseSearch.search("微积分", catalogs, CourseSearchFilters()).mapValues { (_, rows) -> rows.map { it.courseNo } }

        assertEquals(mapOf("zh" to listOf("EE1001301", "MA160A001", "MA160A001")), found)
    }
}
