package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/**
 * "Pin myself" in a course's members list, as the iPhone has it (tigerduck-app#1032): on by default,
 * kept on this device under its own key, and read through a flow by the list, which its switch in
 * Settings sits apart from.
 */
class PinMyselfPreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = JvmContext()
        raw().edit().clear().commit()
    }

    private fun raw() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    @Test
    fun `the account's own row is pinned by default`() {
        val prefs = AppPreferences(context)
        assertTrue(prefs.pinMyselfInCourseMembers.value)
        assertTrue(prefs.pinMyselfInCourseMembers.flow.value)
    }

    @Test
    fun `the choice reaches the flow, is kept under its own key and survives a restart`() {
        val prefs = AppPreferences(context)
        prefs.pinMyselfInCourseMembers.value = false

        assertFalse(prefs.pinMyselfInCourseMembers.flow.value)
        assertEquals(false, raw().all["pinMyselfInCourseMembers"])
        assertFalse(AppPreferences(context).pinMyselfInCourseMembers.value)
    }

    @Test
    fun `a full reset pins the row again`() {
        val prefs = AppPreferences(context)
        prefs.pinMyselfInCourseMembers.value = false

        prefs.clearAllPrefs()

        assertTrue(prefs.pinMyselfInCourseMembers.flow.value)
    }
}
