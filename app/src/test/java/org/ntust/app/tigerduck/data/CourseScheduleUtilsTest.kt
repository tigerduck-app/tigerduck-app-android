package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.collapseContiguousPeriods
import org.ntust.app.tigerduck.shared.parseHm

class CourseScheduleUtilsTest {

    @Test
    fun `collapseContiguousPeriods groups adjacent periods using canonical order`() {
        val collapsed = collapseContiguousPeriods(listOf("4", "3", "A", "B", "D"))
        assertEquals(listOf("3" to "4", "A" to "B", "D" to "D"), collapsed)
    }

    @Test
    fun `collapseContiguousPeriods keeps unknown periods separate`() {
        val collapsed = collapseContiguousPeriods(listOf("E", "1"))
        assertEquals(listOf("E" to "E", "1" to "1"), collapsed)
    }

    @Test
    fun `parseHm converts HH colon MM to total minutes`() {
        assertEquals(620, parseHm("10:20"))
        assertEquals(0, parseHm("00:00"))
        assertEquals(1439, parseHm("23:59"))
    }

    @Test
    fun `parseHm returns null for null or malformed input`() {
        assertNull(parseHm(null))
        assertNull(parseHm("invalid"))
        assertNull(parseHm(""))
        assertNull(parseHm("10"))
    }
}
