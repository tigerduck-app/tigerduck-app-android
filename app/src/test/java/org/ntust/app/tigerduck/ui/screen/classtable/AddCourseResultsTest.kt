package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.coursecatalog.CatalogFixture

/**
 * The results as the sheet lists them: a course's rows merged, its names in the app's language
 * from the catalog with the other language's beside them for a query in that language, and the
 * course an add stores.
 */
class AddCourseResultsTest {

    private val catalogs = mapOf("zh" to CatalogFixture.catalog("zh"), "en" to CatalogFixture.catalog("en"))

    // As CourseService.parseNodeToSchedule reads a node: a weekday letter, then the period.
    private val parseNode: (String?) -> Map<Int, List<String>> = { node ->
        val days = mapOf('M' to 1, 'T' to 2, 'W' to 3, 'R' to 4, 'F' to 5, 'S' to 6, 'U' to 7)
        node.orEmpty().split(",").map { it.trim() }.filter { it.length > 1 && it[0] in days }
            .groupBy({ days.getValue(it[0]) }, { it.drop(1) })
    }

    private fun shown(query: String, language: String): List<Pair<AddCourseShown, AddCourseNames>> {
        val results = CourseSearch.search(query, catalogs.mapValues { it.value.courses }, CourseSearchFilters())
        val byNumber = CourseSearch.foundByNumber(query, results)
        val other = if (language == "zh") "en" else "zh"
        return AddCourseResults.shown(results, language, emptyList(), CourseSearchFilters(), parseNode).map { item ->
            val no = item.course.courseNo
            item to AddCourseResults.names(
                item.course, catalogs.getValue(language).row(no), catalogs.getValue(other).row(no), query, byNumber, language,
            )
        }
    }

    @Test
    fun `a course's rows merge into one with every meeting`() {
        val calculus = shown("MA160A001", "zh").single().first.course
        assertEquals(mapOf(4 to listOf("6"), 5 to listOf("2", "3")), calculus.schedule)
        assertEquals("R6, F2,F3", calculus.nodeDisplay)
        assertEquals("TR-313", calculus.classroom)
    }

    @Test
    fun `a query in the other language shows that language's name beside the app's`() {
        val (item, names) = shown("data", "zh").single()
        assertEquals("CS1002301", item.course.courseNo)
        assertEquals("資料結構 (Data Structures)", names.title)
        assertEquals("資料結構", names.name)
        assertEquals("李小華", names.instructor)

        assertEquals(listOf("Calculus (I) (微積分(上))", "Calculus (微積分)"), shown("微積分", "en").map { it.second.title })
    }

    @Test
    fun `a course number or a query in the app's language shows its name alone`() {
        assertEquals(listOf("計算機概論", "資料結構"), shown("cs10", "zh").map { it.second.title })
        assertEquals(listOf("微積分(上)", "微積分"), shown("微積分", "zh").map { it.second.title })
        assertEquals(listOf("Introduction to Computers", "Data Structures"), shown("cs10", "en").map { it.second.title })
    }

    @Test
    fun `an added course takes its app-language row from the catalog`() {
        val (item, names) = shown("data", "zh").single()

        val course = item.course.tableCourse(names.name, names.instructor)

        assertEquals("資料結構", course.courseName)
        assertEquals("李小華", course.instructor)
        assertEquals(mapOf(1 to listOf("3")), course.schedule)
        assertEquals("R", course.requireOption)
    }

    @Test
    fun `without the app-language row the row it was found with names it`() {
        val (item, _) = shown("data", "zh").single()

        val names = AddCourseResults.names(item.course, appRow = null, otherRow = null, "data", foundByNumber = false, language = "zh")

        assertEquals("Data Structures", names.name)
        assertEquals("Hsiao-Hua Li", names.instructor)
        assertEquals("Data Structures", names.title)
    }
}
