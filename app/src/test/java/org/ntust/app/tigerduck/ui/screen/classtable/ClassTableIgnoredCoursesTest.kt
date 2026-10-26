package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The class table's ignored courses against reloads from the cache. */
class ClassTableIgnoredCoursesTest {

    private val marksVersion = MutableStateFlow(0)
    private val courses = ClassTableIgnoredCourses(marksVersion)

    private fun ignore(key: String) = courses.edit { it + key }

    private fun stopIgnoring(key: String) = courses.edit { it - key }

    /** A write of the file landing, as DataCache bumps the version after it. */
    private fun landed() {
        marksVersion.value++
    }

    @Test
    fun `a reload that read the file before a tap was written keeps the tap`() {
        ignore("1141:AAA")

        courses.reloaded(emptySet(), readAt = marksVersion.value)

        assertEquals(setOf("1141:AAA"), courses.keys.value)
    }

    @Test
    fun `two taps on one course end on the second whatever a reload read in between`() {
        ignore("1141:AAA")
        landed()
        stopIgnoring("1141:AAA")

        courses.reloaded(setOf("1141:AAA"), readAt = marksVersion.value)

        assertEquals(emptySet<String>(), courses.keys.value)
    }

    @Test
    fun `a reload a write landed during is dropped, though that tap is saved by then`() {
        courses.reloaded(setOf("1141:BBB"), readAt = marksVersion.value)
        val readAt = marksVersion.value
        val tap = ignore("1141:AAA")
        landed()
        courses.saved(tap)

        courses.reloaded(setOf("1141:BBB"), readAt)

        assertEquals(setOf("1141:AAA", "1141:BBB"), courses.keys.value)
    }

    @Test
    fun `once a tap is saved the file decides`() {
        val tap = ignore("1141:AAA")
        landed()
        courses.saved(tap)

        // Use Server, say, took the backend's set while the tap was on its way.
        courses.reloaded(setOf("1141:BBB"), readAt = marksVersion.value)

        assertEquals(setOf("1141:BBB"), courses.keys.value)
    }

    @Test
    fun `a sign-out drops the taps still on their way`() {
        ignore("1141:AAA")

        courses.clear()
        courses.reloaded(emptySet(), readAt = marksVersion.value)

        assertEquals(emptySet<String>(), courses.keys.value)
    }
}
