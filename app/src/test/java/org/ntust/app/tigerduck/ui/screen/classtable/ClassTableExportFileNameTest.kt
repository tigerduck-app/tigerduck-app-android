package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An exported class table keeps its file name wherever it is saved or sent,
 * so the name has to say what it is and whose, and survive every place a file
 * can land. Mirrors iOS `ClassTableExportTests`.
 */
class ClassTableExportFileNameTest {

    @Test
    fun `the name carries the title, the term and the student id`() {
        assertEquals("課表 114-2 B11315000.png", classTableExportFileName("課表", "114-2", "B11315000"))
    }

    @Test
    fun `a missing student id is dropped, not left as a trailing space`() {
        assertEquals("課表 114-2.png", classTableExportFileName("課表", "114-2", null))
        assertEquals("課表 114-2.png", classTableExportFileName("課表", "114-2", ""))
    }

    /**
     * Nothing stops a translation carrying a slash, and a path separator would
     * put the file in a directory that does not exist.
     */
    @Test
    fun `path characters are stripped, spaces inside a part are kept`() {
        assertEquals(
            "Class tableTimetable 114-2 B11315000.png",
            classTableExportFileName("Class table/Timetable", "114-2", "B11315000"),
        )
        assertEquals("Class table 114-2.png", classTableExportFileName("Class table", "114-2", null))
    }
}
