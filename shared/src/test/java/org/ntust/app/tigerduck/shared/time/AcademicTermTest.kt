package org.ntust.app.tigerduck.shared.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicTermTest {

    private val inputs = listOf("1141", "1142", "114H", "113H", "1131", "114h", " 1141 ", "11402", "", "abc")

    @Test
    fun `parse reads each odd shape to one value or to null`() {
        assertEquals(
            """
            '1141' -> 1141
            '1142' -> 1142
            '114H' -> 114H
            '113H' -> 113H
            '1131' -> 1131
            '114h' -> 114H
            ' 1141 ' -> 1141
            '11402' -> 1142
            '' -> null
            'abc' -> null
            """.trimIndent(),
            inputs.joinToString("\n") { "'$it' -> ${AcademicTerm.parse(it)?.code}" },
        )
    }

    @Test
    fun `parse of null, blanks and other shapes is null and never throws`() {
        assertNull(AcademicTerm.parse(null))
        listOf("  ", "114", "11", "1143", "1140", "114A", "11403", "11411", "1141-2", "114-1", "202401", "114中", "١١٤1")
            .forEach { assertNull("'$it'", AcademicTerm.parse(it)) }
    }

    @Test
    fun `parse keeps the space-padded years before 100 that the catalogue lists`() {
        val old = AcademicTerm.parse("99 1")!!
        assertEquals(99, old.year)
        assertEquals("99 1", old.code)
        assertTrue(old < AcademicTerm.parse("1001")!!)
    }

    @Test
    fun `year, part and label`() {
        val term = AcademicTerm.parse("114H")!!
        assertEquals(114, term.year)
        assertEquals('H', term.part)
        assertEquals("114-H", term.label)
        assertEquals("113-1", AcademicTerm.parse("1131")!!.label)
        assertEquals("114-2", AcademicTerm.parse("11402")!!.label)
    }

    @Test
    fun `terms sort by year, then 1 then 2 then H`() {
        val sorted = listOf("114H", "1141", "1131", "113H", "1142").mapNotNull { AcademicTerm.parse(it) }.sorted()
        assertEquals(listOf("1131", "113H", "1141", "1142", "114H"), sorted.map { it.code })
    }

    @Test
    fun `the five-character spelling sorts as the term it names`() {
        val sorted = listOf("1142", "11401", "1131", "11402").mapNotNull { AcademicTerm.parse(it) }.sorted()
        assertEquals(listOf("1131", "1141", "1142", "1142"), sorted.map { it.code })
    }

    @Test
    fun `previous steps back one regular term and stays on a summer term`() {
        assertEquals("1141", AcademicTerm.parse("1142")!!.previous().code)
        // The same as the arithmetic this replaces: the term before 1141 is 1132, with no summer in between.
        assertEquals("1132", AcademicTerm.parse("1141")!!.previous().code)
        assertEquals("114H", AcademicTerm.parse("114H")!!.previous().code)
    }

    @Test
    fun `parseExact accepts only the stored form`() {
        assertEquals("1141", AcademicTerm.parseExact("1141")?.code)
        assertEquals("99 1", AcademicTerm.parseExact("99 1")?.code)
        listOf("114h", " 1141 ", "11402", "", "abc").forEach { assertNull("'$it'", AcademicTerm.parseExact(it)) }
    }

    @Test
    fun `labelOf labels a term, keeps a four-character code of another kind, and leaves the rest`() {
        assertEquals("114-1", AcademicTerm.labelOf("1141"))
        assertEquals("114-H", AcademicTerm.labelOf("114h"))
        assertEquals("114-2", AcademicTerm.labelOf("11402"))
        assertEquals("113-A", AcademicTerm.labelOf("113A"))
        assertEquals("", AcademicTerm.labelOf(""))
        assertEquals("abc", AcademicTerm.labelOf("abc"))
    }

    @Test
    fun `compareCodes orders terms as terms and anything else as written`() {
        assertTrue(AcademicTerm.compareCodes("1142", "114H") < 0)
        assertTrue(AcademicTerm.compareCodes("11402", "1141") > 0)
        assertEquals(0, AcademicTerm.compareCodes("1141", "1141"))
        // A kind outside 1, 2 and H falls back to plain string order against everything.
        assertTrue(AcademicTerm.compareCodes("114A", "1142") > 0)
        assertTrue(AcademicTerm.compareCodes("114H", "114A") > 0)
    }
}
