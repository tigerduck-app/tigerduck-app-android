package org.ntust.app.tigerduck.ui.screen.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.push.parseCourseOverrides

/**
 * The re-enable check's course names against a server that keeps `""` for a
 * name the user cleared (tigerduck-backend#122): a cleared name is no name.
 */
class ReenableNamesTest {

    private fun server(names: String) = parseCourseOverrides(
        JSONObject("""{"course_overrides":[{"moodle_id":"1141CS1001301","course_no":"CS1001301","custom_names":$names}]}"""),
        emptyMap(),
    )

    @Test
    fun `a name cleared on the server matches a phone without one`() {
        assertEquals(0, courseNameMismatches(emptyMap(), server("""{"zh-TW":""}""")))
    }

    @Test
    fun `a name cleared in one language matches a phone with only the other`() {
        val local = mapOf("CS1001301" to mapOf("en" to "Calc"))
        assertEquals(0, courseNameMismatches(local, server("""{"zh-TW":"","en":"Calc"}""")))
    }

    @Test
    fun `a phone still holding the name the server cleared differs`() {
        val local = mapOf("CS1001301" to mapOf("zh-TW" to "微積分"))
        assertEquals(1, courseNameMismatches(local, server("""{"zh-TW":""}""")))
    }
}
