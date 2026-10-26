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
        assertEquals("課表_114-2_B11315000.png", classTableExportFileName("課表", "114-2", "B11315000"))
    }

    @Test
    fun `a missing student id is dropped, not left as a trailing underscore`() {
        assertEquals("課表_114-2.png", classTableExportFileName("課表", "114-2", null))
        assertEquals("課表_114-2.png", classTableExportFileName("課表", "114-2", ""))
    }

    /**
     * The English title is "Class table": a space inside a part has to go as
     * well, not only the ones between parts.
     */
    @Test
    fun `spaces become underscores, runs and ends included`() {
        assertEquals("Class_table_114-2.png", classTableExportFileName("Class table", "114-2", null))
        assertEquals("Class_table_114-2.png", classTableExportFileName(" Class   table ", "114-2", null))
    }

    /**
     * Nothing stops a translation carrying a slash, and a path separator would
     * put the file in a directory that does not exist.
     */
    @Test
    fun `path characters are stripped`() {
        assertEquals(
            "Class_tableTimetable_114-2_B11315000.png",
            classTableExportFileName("Class table/Timetable", "114-2", "B11315000"),
        )
    }
}
