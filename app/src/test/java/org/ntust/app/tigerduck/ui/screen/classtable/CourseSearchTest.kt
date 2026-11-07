package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.coursecatalog.CatalogFixture
import org.ntust.app.tigerduck.coursecatalog.CourseCatalog
import org.ntust.app.tigerduck.coursecatalog.CourseSearchOptions
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Periods

/**
 * The Add course search on the device, over the catalog's rows: the course-number prefix first,
 * then the name and teacher, every filter, the filter lists, the clashes and the zh and en merge.
 * As the iPhone's `CourseSearchTests`.
 */
class CourseSearchTest {

    private val catalogs = mapOf("zh" to CatalogFixture.catalog("zh"), "en" to CatalogFixture.catalog("en"))

    private fun search(query: String, filters: CourseSearchFilters = CourseSearchFilters()): Map<String, List<String>> =
        CourseSearch.search(query, catalogs.mapValues { it.value.courses }, filters).mapValues { (_, rows) -> rows.map { it.courseNo } }

    // Local search

    @Test
    fun `letters and digits match a course-number prefix first, ignoring case`() {
        assertEquals(mapOf("zh" to listOf("CS1001301", "CS1002301"), "en" to listOf("CS1001301", "CS1002301")), search("cs10"))
        // "Deep Learning" holds "ee" too, but a course number starts with it.
        assertEquals(mapOf("zh" to listOf("EE1001301"), "en" to listOf("EE1001301")), search("ee"))
        val twice = listOf("MA160A001", "MA160A001")
        assertEquals(mapOf("zh" to twice, "en" to twice), search("MA160A001"))
    }

    @Test
    fun `letters and digits no course number starts with match the name and teacher`() {
        assertEquals(mapOf("en" to listOf("CS1002301")), search("data"))
        assertEquals(mapOf("en" to listOf("EE1001301")), search("HUANG"))
    }

    @Test
    fun `other text matches the name and teacher`() {
        assertEquals(mapOf("zh" to listOf("EE1001301", "MA160A001", "MA160A001")), search("微積分"))
        assertEquals(mapOf("zh" to listOf("EE1001301")), search("黃意婷"))
        assertEquals(mapOf("en" to listOf("CS5101701")), search("deep learn"))
        assertEquals(mapOf("en" to listOf("EE1001301")), search("calculus ("))
    }

    @Test
    fun `a query finds a course number only when the prefix matched`() {
        assertTrue(CourseSearch.foundByNumber("cs10", CourseSearch.search("cs10", catalogs.mapValues { it.value.courses }, CourseSearchFilters())))
        assertFalse(CourseSearch.foundByNumber("data", CourseSearch.search("data", catalogs.mapValues { it.value.courses }, CourseSearchFilters())))
        assertFalse(CourseSearch.foundByNumber("微積分", CourseSearch.search("微積分", catalogs.mapValues { it.value.courses }, CourseSearchFilters())))
    }

    @Test
    fun `a department narrows the prefix, and a prefix outside it is matched as a name`() {
        val inCS = listOf("CS1001301", "CS1002301", "CS5101701")
        assertEquals(mapOf("zh" to inCS, "en" to inCS), search("c", CourseSearchFilters(department = "CS")))
        assertEquals(mapOf("en" to listOf("CS5101701")), search("ee", CourseSearchFilters(department = "CS")))
        assertTrue(search("ma", CourseSearchFilters(department = "CS")).isEmpty())
    }

    private fun admitted(filters: CourseSearchFilters): List<String> =
        catalogs.getValue("zh").courses.filter { CourseSearch.admits(it, filters) }.map { it.row.courseNo }

    @Test
    fun `each filter keeps the courses the school's filter of that name returned`() {
        val all = CatalogFixture.zhRows.map { it.courseNo }
        assertEquals(all, admitted(CourseSearchFilters()))
        assertEquals(listOf("CS1001301", "CS1002301", "CS5101701"), admitted(CourseSearchFilters(department = "CS")))
        assertEquals(listOf("CS5101701"), admitted(CourseSearchFilters(level = CourseSearchFilters.Level.Graduate)))
        assertEquals(all - "CS5101701", admitted(CourseSearchFilters(level = CourseSearchFilters.Level.Undergraduate)))
        assertEquals(listOf("GE1408301"), admitted(CourseSearchFilters(dimension = "A")))
        assertEquals(listOf("GE1408301", "TCG051301"), admitted(CourseSearchFilters(generalOnly = true)))
        assertEquals(listOf("CS5101701"), admitted(CourseSearchFilters(englishTaught = true)))
        assertEquals(listOf("3N1346703"), admitted(CourseSearchFilters(intensiveOnly = true)))
        assertEquals(all - "3N1346703", admitted(CourseSearchFilters(excludeAlliance = true)))
        assertEquals(
            listOf("CS1001301", "CS1002301"),
            admitted(CourseSearchFilters(department = "CS", level = CourseSearchFilters.Level.Undergraduate)),
        )
    }

    @Test
    fun `the filter lists come from the catalog, with each name in both languages`() {
        assertEquals(
            CourseSearchOptions(
                colleges = listOf(
                    CourseSearchOptions.College(
                        "電資學院", "College of EECS",
                        listOf(
                            CourseSearchOptions.Option("CS", "資訊工程系", "Computer Science"),
                            CourseSearchOptions.Option("EE", "電機工程系", "Electrical Engineering"),
                        ),
                    ),
                ),
                dimensions = listOf(CourseSearchOptions.Option("A", "人文素養(A)", "Humanities and Thinking(A)")),
            ),
            CourseSearchOptions.of(catalogs),
        )
        assertEquals(CourseSearchOptions(), CourseSearchOptions.of(emptyMap()))
    }

    @Test
    fun `a row without a course number or name is dropped, and a missing teacher reads as empty`() {
        val catalog = CourseCatalog.decode(
            """{"courses":[{"CourseNo":"CS1001301","CourseName":"計算機概論"},{"CourseName":"無號"},{"CourseNo":"X"}]}""",
        )
        assertEquals(listOf("CS1001301"), catalog.courses.map { it.row.courseNo })
        assertEquals("", catalog.courses.single().row.courseTeacher)
        assertEquals("計算機概論\n", catalog.courses.single().textKey)
        assertFalse(catalog.courses.single().isIntensive)
    }

    // Filters

    @Test
    fun `hide conflicts starts off and, turned on alone, fills the filter button`() {
        val filters = CourseSearchFilters()
        assertFalse(filters.hideConflicts)
        assertTrue(filters.isDefault)
        assertFalse(filters.copy(hideConflicts = true).isDefault)
    }

    @Test
    fun `hide conflicts and periods stay on the device, and reset means every default`() {
        val filters = CourseSearchFilters(hideConflicts = true, periods = setOf(CourseSlot(1, "3")))
        assertFalse(filters.isDefault)
        assertEquals(CourseSearchFilters(), filters.searched)
        assertNotEquals(CourseSearchFilters(), filters.copy(generalOnly = true).searched)
    }

    @Test
    fun `a picked period range keeps only courses that meet inside it`() {
        var filters = CourseSearchFilters(periods = setOf(CourseSlot(1, "3"), CourseSlot(1, "4")))
        fun shown(schedule: Map<Int, List<String>>) = CourseSearch.visibleConflicts("X", schedule, emptyList(), filters) != null
        assertTrue(shown(mapOf(1 to listOf("3", "4"))))
        assertTrue(shown(mapOf(1 to listOf("4"))))
        assertFalse(shown(mapOf(1 to listOf("4", "5"))))
        assertFalse(shown(emptyMap()))

        filters = CourseSearchFilters()
        assertTrue(shown(emptyMap()))
        assertTrue(shown(mapOf(5 to listOf("A"))))
    }

    @Test
    fun `select free periods picks every grid period the class table leaves free`() {
        val free = CourseSearch.freeSlots(setOf(CourseSlot(2, "3"), CourseSlot(7, "1")))
        assertEquals(6 * Periods.chronologicalOrder.size - 1, free.size)
        assertFalse(CourseSlot(2, "3") in free)
        assertTrue(CourseSlot(6, "D") in free)
    }

    // Conflicts

    @Test
    fun `a clash is named by default and hidden when hide conflicts is on`() {
        val table = listOf(
            CourseSearchTableCourse("CS2001301", "資料結構", setOf(CourseSlot(2, "3"))),
            CourseSearchTableCourse("CS2002301", "計算機組織", setOf(CourseSlot(2, "4"))),
        )
        val clash = mapOf(2 to listOf("3", "4"))
        assertEquals(listOf("資料結構", "計算機組織"), CourseSearch.visibleConflicts("MA1001301", clash, table, CourseSearchFilters()))

        val hiding = CourseSearchFilters(hideConflicts = true)
        assertNull(CourseSearch.visibleConflicts("MA1001301", clash, table, hiding))
        assertEquals(emptyList<String>(), CourseSearch.visibleConflicts("MA1001301", mapOf(2 to listOf("5")), table, hiding))
    }

    @Test
    fun `a course already in the class table is no conflict and stays listed`() {
        val table = listOf(
            CourseSearchTableCourse("CS2001301", "資料結構", setOf(CourseSlot(2, "3"))),
            CourseSearchTableCourse("CS2002301", "計算機組織", setOf(CourseSlot(2, "3"))),
        )
        assertEquals(emptyList<String>(), CourseSearch.visibleConflicts("CS2001301", mapOf(2 to listOf("3")), table, CourseSearchFilters()))
    }

    // Results

    private fun result(courseNo: String, name: String) = CourseSearchResult(
        "1151", courseNo, name, "王大明", "", "3", "R", "H", 50, "0", "60", "3", "0", "TR-313", "M3", "",
    )

    @Test
    fun `the primary language's rows come first, with the other language's names beside them`() {
        val results = mapOf(
            "zh" to listOf(result("CS1001301", "計算機概論")),
            "en" to listOf(result("CS1001301", "Introduction to Computers"), result("CS1002301", "Data Structures")),
        )
        val shown = CourseSearch.shown(results, primary = "zh")
        assertEquals(listOf("CS1001301", "CS1002301"), shown.rows.map { it.courseNo })
        assertEquals(listOf("計算機概論", "Data Structures"), shown.rows.map { it.courseName })
        assertEquals("Introduction to Computers", shown.otherNames["CS1001301"])
    }

    @Test
    fun `department search ignores case and matches codes and either name`() {
        val colleges = listOf(
            CourseSearchOptions.College(
                "電資學院", "EECS",
                listOf(
                    CourseSearchOptions.Option("CS", "資訊工程系", "Computer Science"),
                    CourseSearchOptions.Option("EE", "電機工程系", "Electrical Engineering"),
                ),
            ),
            CourseSearchOptions.College("管理學院", "Management", listOf(CourseSearchOptions.Option("IM", "資訊管理系", "Information Management"))),
        )
        assertEquals(listOf("CS"), CourseSearch.colleges(colleges, "cs").flatMap { it.departments }.map { it.code })
        assertEquals(listOf("CS", "IM"), CourseSearch.colleges(colleges, "資訊").flatMap { it.departments }.map { it.code })
        assertEquals(listOf("電資學院"), CourseSearch.colleges(colleges, "ELECTRICAL").map { it.name })
        assertEquals(colleges, CourseSearch.colleges(colleges, " "))
    }
}
