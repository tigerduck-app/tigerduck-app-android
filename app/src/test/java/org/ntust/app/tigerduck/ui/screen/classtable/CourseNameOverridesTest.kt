package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * Renaming is per-language. These pin that the two locales stay independent
 * and that reverting actually clears rather than leaving a husk behind.
 */
class CourseNameOverridesTest {

    private fun course(no: String, name: String = "Course $no", override: String? = null) =
        Course(courseNo = no, courseName = name, customCourseName = override)

    @Test
    fun `an override in one locale does not touch the other`() {
        var names: CustomNameMap = emptyMap()
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", "資結")
        names = CourseNameOverrides.withOverride(names, "CS101", "en", "Data Structures")
        assertEquals("資結", CourseNameOverrides.overrideFor(names, "CS101", "zh"))
        assertEquals("Data Structures", CourseNameOverrides.overrideFor(names, "CS101", "en"))
    }

    @Test
    fun `clearing one locale leaves the other standing`() {
        var names: CustomNameMap = emptyMap()
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", "資結")
        names = CourseNameOverrides.withOverride(names, "CS101", "en", "Data Structures")
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", null)
        assertNull(CourseNameOverrides.overrideFor(names, "CS101", "zh"))
        assertEquals("Data Structures", CourseNameOverrides.overrideFor(names, "CS101", "en"))
    }

    @Test
    fun `a course with no locales left is dropped from the map entirely`() {
        // Not cosmetic: an empty entry makes the map non-empty, which is what
        // resolve() short-circuits on.
        var names: CustomNameMap = emptyMap()
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", "資結")
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", null)
        assertTrue(names.isEmpty())
    }

    @Test
    fun `clearing something that was never set is harmless`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "CS101", "zh", null)
        assertTrue(names.isEmpty())
    }

    @Test
    fun `setting twice replaces rather than accumulates`() {
        var names: CustomNameMap = emptyMap()
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", "資結")
        names = CourseNameOverrides.withOverride(names, "CS101", "zh", "資料結構")
        assertEquals("資料結構", CourseNameOverrides.overrideFor(names, "CS101", "zh"))
        assertEquals(1, names.getValue("CS101").size)
    }

    @Test
    fun `withOverride does not mutate the map it was given`() {
        val original = CourseNameOverrides.withOverride(emptyMap(), "CS101", "zh", "資結")
        CourseNameOverrides.withOverride(original, "CS101", "zh", "changed")
        CourseNameOverrides.withOverride(original, "CS102", "zh", "added")
        assertEquals("資結", CourseNameOverrides.overrideFor(original, "CS101", "zh"))
        assertEquals(setOf("CS101"), original.keys)
    }

    @Test
    fun `resolve stamps the current locale onto matching courses`() {
        val names = CourseNameOverrides.withOverride(emptyMap(), "CS101", "en", "Data Structures")
        val out = CourseNameOverrides.resolve(
            listOf(course("CS101"), course("CS102")), names, "en",
        )
        assertEquals("Data Structures", out[0].customCourseName)
        assertNull(out[1].customCourseName)
    }

    @Test
    fun `resolve leaves an existing override alone when this locale has none`() {
        // The merge path stamps the override first and calls resolve after;
        // clearing here would undo it.
        val names = CourseNameOverrides.withOverride(emptyMap(), "CS101", "zh", "資結")
        val out = CourseNameOverrides.resolve(
            listOf(course("CS101", override = "already set")), names, "en",
        )
        assertEquals("already set", out.single().customCourseName)
    }

    @Test
    fun `resolve on an empty map is a no-op`() {
        val input = listOf(course("CS101", override = "kept"))
        assertEquals("kept", CourseNameOverrides.resolve(input, emptyMap(), "zh").single().customCourseName)
    }
}
