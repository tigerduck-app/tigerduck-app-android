package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.library.FlipToLibraryDefaultOffMigration
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.ClassReminders
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The class table's display switches in Settings > Display, as the iPhone app
 * has them (tigerduck-app-android#216, #161): classrooms shown by default,
 * today's courses pinned above the grid and required courses outlined only
 * once the user asks. Each is device-local and read through a flow by the
 * class table, which lives on another screen than its switch.
 */
class ClassTableDisplayPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = JvmContext()
        raw().edit().clear().commit()
    }

    private fun raw() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    private fun prefs() = AppPreferences(context)

    @Test
    fun `classrooms are shown on the class table by default`() {
        val prefs = prefs()
        assertTrue(prefs.showClassroomInClassTable)
        assertTrue(prefs.showClassroomInClassTableFlow.value)
    }

    /** Someone who turned the classrooms off before the default changed keeps them off. */
    @Test
    fun `a stored classroom switch is kept either way`() {
        raw().edit().putBoolean("showClassroomInClassTable", false).commit()
        assertFalse(prefs().showClassroomInClassTable)

        raw().edit().putBoolean("showClassroomInClassTable", true).commit()
        assertTrue(prefs().showClassroomInClassTable)
    }

    /**
     * An update from 2.3.1, where the classrooms defaulted off and the switch
     * was stored only once flipped, through every step this version's first
     * launch takes on the preferences: an untouched switch gets the classrooms
     * on, and one turned off keeps them off.
     */
    @Test
    fun `an update from the off-by-default version keeps the classroom switch the user stored`() {
        fun updatedFrom(stored: Boolean?): Boolean {
            raw().edit().clear()
                .putInt("dataSchemaVersion", 3)
                .putBoolean("hasCompletedOnboarding", true)
                .apply { if (stored != null) putBoolean("showClassroomInClassTable", stored) }
                .commit()
            val prefs = prefs()
            DataMigration.settleReenableCheck(prefs, signedIn = true)
            DataMigration.dropStaleCoursesCheck(prefs)
            ClassReminders.migrateIfNeeded(prefs, LiveActivityPreferences(context))
            LibraryTabKeepMigration.runIfNeeded(prefs)
            FlipToLibraryDefaultOffMigration.runIfNeeded(prefs)
            return prefs().showClassroomInClassTable
        }

        assertFalse(updatedFrom(stored = false))
        assertTrue(updatedFrom(stored = true))
        assertTrue(updatedFrom(stored = null))
    }

    @Test
    fun `today's courses are not pinned to the class table by default`() {
        val prefs = prefs()
        assertFalse(prefs.pinTodayCoursesOnClassTable)
        assertFalse(prefs.pinTodayCoursesOnClassTableFlow.value)
    }

    @Test
    fun `required courses are not outlined by default`() {
        val prefs = prefs()
        assertFalse(prefs.outlineRequiredCourses)
        assertFalse(prefs.outlineRequiredCoursesFlow.value)
    }

    @Test
    fun `the switches reach the class table's flows and survive a restart`() {
        val prefs = prefs()
        prefs.pinTodayCoursesOnClassTable = true
        prefs.outlineRequiredCourses = true
        prefs.showClassroomInClassTable = false
        assertTrue(prefs.pinTodayCoursesOnClassTableFlow.value)
        assertTrue(prefs.outlineRequiredCoursesFlow.value)
        assertFalse(prefs.showClassroomInClassTableFlow.value)

        val restarted = prefs()
        assertTrue(restarted.pinTodayCoursesOnClassTable)
        assertTrue(restarted.outlineRequiredCourses)
        assertFalse(restarted.showClassroomInClassTable)
    }

    /** A full reset puts the in-memory mirrors back at the defaults, not only the file. */
    @Test
    fun `a full reset returns every switch to its default`() {
        val prefs = prefs()
        prefs.pinTodayCoursesOnClassTable = true
        prefs.outlineRequiredCourses = true
        prefs.showClassroomInClassTable = false

        prefs.clearAllPrefs()

        assertFalse(prefs.pinTodayCoursesOnClassTableFlow.value)
        assertFalse(prefs.outlineRequiredCoursesFlow.value)
        assertTrue(prefs.showClassroomInClassTableFlow.value)
    }
}
