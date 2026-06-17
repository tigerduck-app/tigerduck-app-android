package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

class WearScheduleBridgeDtoTest {

    @Test
    fun `null classroomMapJson is coalesced to empty object`() {
        val course = Course(
            courseNo = "CS101",
            courseName = "Algorithms",
            classroomMapJson = null,
        )
        val dto = course.toWearDto()
        assertEquals("{}", dto.classroomMapJson)
    }

    @Test
    fun `non-null classroomMapJson is preserved`() {
        val map = """{"1-3":"T3-101"}"""
        val course = Course(
            courseNo = "CS102",
            courseName = "Data Structures",
            classroomMapJson = map,
        )
        val dto = course.toWearDto()
        assertEquals(map, dto.classroomMapJson)
    }

    @Test
    fun `displayName is used instead of courseName when customCourseName is set`() {
        val course = Course(
            courseNo = "CS103",
            courseName = "Operating Systems",
            customCourseName = "OS",
        )
        val dto = course.toWearDto()
        assertEquals("OS", dto.courseName)
    }

    @Test
    fun `all fields are mapped correctly`() {
        val course = Course(
            courseNo = "CS104",
            courseName = "Networks",
            instructor = "Prof. Lin",
            credits = 3,
            classroom = "T3-101",
            scheduleJson = """{"1":["3","4"]}""",
            classroomMapJson = """{"1-3":"T3-101"}""",
            moodleIdNumber = "12345",
            customColorHex = "#FF0000",
            isManual = true,
        )
        val dto = course.toWearDto()
        assertEquals("CS104", dto.courseNo)
        assertEquals("Networks", dto.courseName)
        assertEquals("Prof. Lin", dto.instructor)
        assertEquals(3, dto.credits)
        assertEquals("T3-101", dto.classroom)
        assertEquals("""{"1":["3","4"]}""", dto.scheduleJson)
        assertEquals("""{"1-3":"T3-101"}""", dto.classroomMapJson)
        assertEquals("12345", dto.moodleIdNumber)
        assertEquals("#FF0000", dto.customColorHex)
        assertEquals(true, dto.isManual)
    }
}
