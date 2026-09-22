package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse

/**
 * How a Moodle `idnumber` is read and joined back to the class table.
 *
 * Two shapes used to fall through every exact match: a 合開 course, whose
 * second department's code lives only in `fullname`, and a summer term, whose
 * prefix ends in a letter and is spelled `114h` by Moodle but `114H` by NTUST.
 * Mirrors iOS `MoodleHomeworkRegressionTests`.
 */
class MoodleCourseIdsTest {

    private fun moodle(id: Int, idnumber: String?, fullname: String? = "") =
        MoodleEnrolledCourse(
            id = id,
            fullname = fullname,
            shortname = "",
            idnumber = idnumber,
            startdate = null,
            enddate = null,
        )

    private val coListed = moodle(
        19739,
        "1151AS5140701",
        "115.1【半導體研究所】AS5140701 電腦輔助晶片系統設計" +
            " / 【資工系】CS5140701 電腦輔助晶片系統設計",
    )

    @Test
    fun `a co-listed course answers to both department codes`() {
        assertEquals(listOf("AS5140701", "CS5140701"), coListed.courseNos)
        assertEquals(listOf("1151AS5140701", "1151CS5140701"), coListed.idnumbers)
        assertEquals(
            mapOf("1151AS5140701" to 19739, "1151CS5140701" to 19739),
            MoodleCourseIds.idMap(listOf(coListed)),
        )
    }

    @Test
    fun `an ordinary course has no aliases, all-caps title words included`() {
        val ordinary = moodle(
            1,
            "1142CS5164701",
            "114.2【資工系】CS5164701 隱私資訊安全 Data Privacy and Security",
        )
        assertEquals(listOf("CS5164701"), ordinary.courseNos)

        val acronyms = moodle(
            2,
            "1151EE5428701",
            "115.1【電機系】EE5428701 VLSI CAD FPGA ASIC HTML5 SQL92 Design",
        )
        assertEquals(listOf("EE5428701"), acronyms.courseNos)
    }

    /**
     * Scanning instead of matching whole tokens found `CS3003302` inside the
     * 進修部 form `3CS3003302` and minted an alias for a course number that
     * does not exist.
     */
    @Test
    fun `a leading-3 course number is not truncated into a false alias`() {
        val nightSchool = moodle(4, "11413CS3003302", "114.1【資工系(進修)】3CS3003302 離散數學")

        assertEquals("3CS3003302", nightSchool.courseNo)
        assertEquals(listOf("3CS3003302"), nightSchool.courseNos)
        assertEquals(listOf("11413CS3003302"), nightSchool.idnumbers)
    }

    @Test
    fun `an assignment is filed under the code the class table holds`() {
        assertEquals(
            "CS5140701",
            MoodleCourseIds.assignmentCourseNo(coListed, setOf("CS5140701", "CS3025301")),
        )
        assertEquals(
            "AS5140701",
            MoodleCourseIds.assignmentCourseNo(coListed, setOf("AS5140701")),
        )
        // Cold launch, nothing local yet: the authoritative code.
        assertEquals("AS5140701", MoodleCourseIds.assignmentCourseNo(coListed, emptySet()))
    }

    /**
     * The roster names the student's own code. Matching on `courseNo` alone
     * dropped a co-listed course from `mod_assign_get_assignments` entirely,
     * so its homework never reached the app.
     */
    @Test
    fun `a co-listed course is fetched when the roster holds its other code`() {
        val other = moodle(7, "1151CS3025301", "115.1【資工系】CS3025301 作業系統")
        val dropped = moodle(8, "1151CS1111701", "115.1【資工系】CS1111701 已退選")

        assertEquals(
            listOf(19739, 7),
            MoodleCourseIds.forRoster(
                listOf(coListed, other, dropped),
                setOf("CS5140701", "CS3025301"),
            ).map { it.id },
        )
    }

    @Test
    fun `a real idnumber beats another course's fullname alias in either order`() {
        val aliasing = moodle(100, "1151AA1111701", "115.1【甲系】AA1111701 X / 【乙系】BB2222701 X")
        val owner = moodle(200, "1151BB2222701", "115.1【乙系】BB2222701 X")

        assertEquals(200, MoodleCourseIds.idMap(listOf(aliasing, owner))["1151BB2222701"])
        assertEquals(200, MoodleCourseIds.idMap(listOf(owner, aliasing))["1151BB2222701"])
    }

    /**
     * A summer term's fourth character is a letter. The old four-digit check
     * left `courseNo` and `semesterCode` both empty, which filtered summer
     * courses out of the class table and the assignment pipeline.
     */
    @Test
    fun `a summer-term idnumber is parsed and normalised to NTUST's spelling`() {
        val summer = moodle(3, "114hGD3115301", "114.h【設計系】GD3115301 工程整合設計專題")

        assertEquals("114H", summer.semesterCode)
        assertEquals("GD3115301", summer.courseNo)
        // Rebuilt ids keep Moodle's own spelling so they match a real idnumber.
        assertEquals(listOf("114hGD3115301"), summer.idnumbers)
        // The map key is normalised, and lookups normalise the same way.
        assertEquals(mapOf("114HGD3115301" to 3), MoodleCourseIds.idMap(listOf(summer)))
        assertEquals("114HGD3115301", MoodleCourseIds.normalizedIdnumber("114hGD3115301"))
        assertEquals("1151AS5140701", MoodleCourseIds.normalizedIdnumber("1151AS5140701"))
        assertEquals("moodle:42", MoodleCourseIds.normalizedIdnumber("moodle:42"))
    }

    @Test
    fun `semesterPrefix accepts term codes and rejects everything else`() {
        assertEquals("1151", MoodleCourseIds.semesterPrefix("1151AS5140701"))
        assertEquals("114H", MoodleCourseIds.semesterPrefix("114hGD3115301"))
        assertEquals("114H", MoodleCourseIds.semesterPrefix("114HGD3115301"))
        assertNull(MoodleCourseIds.semesterPrefix("moodle:42"))
        // A non-ASCII term letter or digit is not a term code.
        assertNull(MoodleCourseIds.semesterPrefix("114中GD3115301"))
        assertNull(MoodleCourseIds.semesterPrefix("١١٤1GD3115301"))
        assertNull(MoodleCourseIds.semesterPrefix("1151"))
        assertNull(MoodleCourseIds.semesterPrefix(""))
    }

    @Test
    fun `an idnumber without a term prefix yields no course numbers`() {
        val shell = moodle(9, "moodle:42", "Admin shell AS5140701")
        assertEquals("", shell.courseNo)
        assertEquals(emptyList<String>(), shell.courseNos)
        // Still keyed as-is, as before: a course row can carry any idnumber.
        assertEquals(
            mapOf("moodle:42" to 9),
            MoodleCourseIds.idMap(listOf(shell, moodle(10, null))),
        )
    }
}
