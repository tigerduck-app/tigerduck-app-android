package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SemesterCodesTest {

    @Test
    fun `previous walks term 2 back to term 1 of the same year`() {
        assertEquals("1151", SemesterCodes.previous("1152"))
    }

    @Test
    fun `previous walks term 1 back across the year boundary`() {
        assertEquals("1142", SemesterCodes.previous("1151"))
    }

    @Test
    fun `previous leaves a summer term alone rather than inventing one`() {
        // The catalogue publishes 暑期 as `114H`; the arithmetic has no rule
        // for it, and returning garbage would be worse than standing still.
        assertEquals("114H", SemesterCodes.previous("114H"))
    }

    @Test
    fun `previous leaves a malformed code alone`() {
        assertEquals("", SemesterCodes.previous(""))
        assertEquals("abc", SemesterCodes.previous("abc"))
    }

    @Test
    fun `walkBack yields the requested number of terms newest first`() {
        assertEquals(
            listOf("1151", "1142", "1141", "1132"),
            SemesterCodes.walkBack("1151", 4),
        )
    }

    @Test
    fun `walkBack stops early rather than repeating a fixed point`() {
        // `previous` is identity for summer terms, so the walk would otherwise
        // emit the same code four times.
        assertEquals(listOf("114H"), SemesterCodes.walkBack("114H", 4))
    }

    @Test
    fun `walking back from the pinned term reaches the term it replaced`() {
        // The pre-catalogue picker fallback must anchor on the pinned term,
        // not on the month heuristic. Anchoring on the heuristic in August
        // 2026 would produce 1142, 1141, 1132, 1131 — dropping the 115-1 term
        // NTUST had already opened, which is the bug this hotfix fixes.
        val offered = SemesterCodes.walkBack(org.ntust.app.tigerduck.AppConstants.CurrentTerm.CODE, 4)
        assertEquals(listOf("1151", "1142", "1141", "1132"), offered)
    }

    @Test
    fun `heuristic returns a well-formed code`() {
        // Deliberately not asserting a specific term: the heuristic reads the
        // wall clock, and pinning it here would make the test expire. What
        // matters is that the fallback stays parseable by `previous`.
        val code = SemesterCodes.heuristic()
        assertEquals(4, code.length)
        assert(code.last() == '1' || code.last() == '2')
        assert(code.dropLast(1).toIntOrNull() != null)
    }
}
