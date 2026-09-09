package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemesterCatalogTest {

    /** Trimmed verbatim from the live endpoint on 2026-08-22. */
    private val realPayload = """
        [{"Semester":"1151","EngSemester":"2026 Fall","Static":false,"LoginEnable":true,"ShowRemind":false,"CurrentSemester":true},
         {"Semester":"114H","EngSemester":"2026 Summer","Static":false,"LoginEnable":false,"ShowRemind":false,"CurrentSemester":true},
         {"Semester":"1142","EngSemester":"2026 Spring","Static":false,"LoginEnable":false,"ShowRemind":false,"CurrentSemester":true},
         {"Semester":"1141","EngSemester":"2025 Fall","Static":false,"LoginEnable":false,"ShowRemind":false,"CurrentSemester":true}]
    """.trimIndent()

    @Test
    fun `decodes the live payload newest first`() {
        val list = SemesterCatalog.decodeSemesters(realPayload)
        assertEquals(listOf("1151", "114H", "1142", "1141"), list.map { it.semester })
    }

    @Test
    fun `open term comes from LoginEnable, not from position`() {
        // Deliberately puts the open term third: the whole point of reading
        // LoginEnable is that "newest published" and "what 選課 is serving"
        // are different questions.
        val outOfOrder = """
            [{"Semester":"1152","LoginEnable":false},
             {"Semester":"1151","LoginEnable":false},
             {"Semester":"1142","LoginEnable":true}]
        """.trimIndent()
        assertEquals("1142", SemesterCatalog.openTerm(SemesterCatalog.decodeSemesters(outOfOrder)))
    }

    @Test
    fun `no open term yields null so the caller keeps its previous value`() {
        val none = """[{"Semester":"1151","LoginEnable":false}]"""
        assertNull(SemesterCatalog.openTerm(SemesterCatalog.decodeSemesters(none)))
    }

    @Test
    fun `missing LoginEnable key defaults to not-open rather than crashing`() {
        // Gson instantiates data classes through Unsafe, so an absent key
        // leaves the JVM zero value. For a Boolean that is `false`, which is
        // the safe reading — see the upgrade-safe-persistence checklist.
        val list = SemesterCatalog.decodeSemesters("""[{"Semester":"1151"}]""")
        assertEquals(1, list.size)
        assertNull(SemesterCatalog.openTerm(list))
    }

    @Test
    fun `rows without a semester code are dropped`() {
        val list = SemesterCatalog.decodeSemesters(
            """[{"LoginEnable":true},{"Semester":"","LoginEnable":true},{"Semester":"1151","LoginEnable":true}]"""
        )
        assertEquals(listOf("1151"), list.map { it.semester })
    }

    @Test
    fun `malformed payload decodes to empty instead of throwing`() {
        assertTrue(SemesterCatalog.decodeSemesters("not json").isEmpty())
        assertTrue(SemesterCatalog.decodeSemesters("").isEmpty())
    }

    @Test
    fun `picker reaches back to the admission term, summer terms included`() {
        // The case a fixed depth of six gets wrong: 114H and 113H eat two
        // slots, so a 113 admit stopped at 1132 and could not reach 1131.
        val catalogue =
            listOf("1151", "114H", "1142", "1141", "113H", "1132", "1131", "112H", "1122")
        assertEquals(
            listOf("1151", "114H", "1142", "1141", "113H", "1132", "1131"),
            SemesterCatalog.termsFrom(catalogue, admissionYear = 113),
        )
    }

    @Test
    fun `space-padded pre-100 terms never leak past the admission year`() {
        // The reason the cut-off compares numbers, not strings: "99 1" sorts
        // after "1131" lexicographically, which is how terms back to 95-1
        // leaked into the picker on iOS.
        val catalogue = listOf(
            "1151", "114H", "1142", "1141", "113H", "1132", "1131", "112H",
            "1001", "99 H", "99 2", "99 1", "95 1",
        )
        assertEquals(
            listOf("1151", "114H", "1142", "1141", "113H", "1132", "1131"),
            SemesterCatalog.termsFrom(catalogue, admissionYear = 113),
        )
        assertEquals(
            listOf(
                "1151", "114H", "1142", "1141", "113H", "1132", "1131", "112H",
                "1001", "99 H", "99 2", "99 1",
            ),
            SemesterCatalog.termsFrom(catalogue, admissionYear = 99),
        )
    }

    @Test
    fun `academic year is everything before the term character, whitespace tolerant`() {
        assertEquals(115, SemesterCatalog.academicYear("1151"))
        assertEquals(114, SemesterCatalog.academicYear("114H"))
        assertEquals(99, SemesterCatalog.academicYear("99 1"))
        assertNull(SemesterCatalog.academicYear(""))
        assertNull(SemesterCatalog.academicYear("H"))
    }

    @Test
    fun `unknown student id keeps the fixed depth`() {
        val catalogue = listOf("1151", "114H", "1142", "1141", "113H", "1132", "1131", "112H")
        assertEquals(
            SemesterCatalog.PICKER_DEPTH,
            SemesterCatalog.termsFrom(catalogue, admissionYear = null).size,
        )
    }

    @Test
    fun `admission after the newest published term offers nothing, not older terms`() {
        // Not a fallback to six: every catalogue term predates this student, so
        // offering any of it is wrong. ClassTableViewModel.semesterOptions
        // keeps the current term selectable, so the picker still renders one.
        val catalogue = listOf("1151", "114H", "1142", "1141", "113H", "1132", "1131", "112H")
        assertTrue(SemesterCatalog.termsFrom(catalogue, admissionYear = 116).isEmpty())
    }

    @Test
    fun `admission year is the three digits after the degree letter`() {
        assertEquals(113, SemesterCatalog.admissionYear("B11315000"))
        assertEquals(110, SemesterCatalog.admissionYear("M11000001"))
        assertNull(SemesterCatalog.admissionYear("abc"))
        assertNull(SemesterCatalog.admissionYear(null))
    }
}
