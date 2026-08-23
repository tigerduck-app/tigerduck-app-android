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
    fun `picker depth reaches back two years across interleaved summer terms`() {
        // 1151, 114H, 1142, 1141, 113H, 1132 — four slots would stop at 1141.
        val list = SemesterCatalog.decodeSemesters(
            """
            [{"Semester":"1151","LoginEnable":true},{"Semester":"114H","LoginEnable":false},
             {"Semester":"1142","LoginEnable":false},{"Semester":"1141","LoginEnable":false},
             {"Semester":"113H","LoginEnable":false},{"Semester":"1132","LoginEnable":false},
             {"Semester":"1131","LoginEnable":false}]
            """.trimIndent()
        )
        val offered = list.mapNotNull { it.semester }.take(SemesterCatalog.PICKER_DEPTH)
        assertTrue("1132" in offered)
        assertEquals(SemesterCatalog.PICKER_DEPTH, offered.size)
    }
}
