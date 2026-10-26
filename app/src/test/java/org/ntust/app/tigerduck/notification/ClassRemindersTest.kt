package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Class reminders with a switch and a lead time of their own: when they are
 * armed, what an existing install starts with, and the last check before one
 * is posted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassRemindersTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    // ── Arming ─────────────────────────────────────────────────────────────

    @Test
    fun `reminders are armed at their own lead time`() {
        assertEquals(30 * 60L, ClassReminders.leadSecondsToArm(ClassReminderSettings(true, 30), signedIn = true))
    }

    @Test
    fun `switched off or signed out, nothing is armed`() {
        assertNull(ClassReminders.leadSecondsToArm(ClassReminderSettings(false, 30), signedIn = true))
        assertNull(ClassReminders.leadSecondsToArm(ClassReminderSettings(true, 30), signedIn = false))
    }

    @Test
    fun `the Live Update settings have no say in class reminders`() {
        val prefs = AppPreferences(context)
        val liveUpdate = LiveActivityPreferences(context)
        prefs.classReminderEnabled = true
        prefs.classReminderLeadMinutes = 15

        liveUpdate.isEnabled = false
        liveUpdate.showClassPreparing = false
        liveUpdate.classPreparingLeadTimeSec = 3_600

        val settings = ClassReminderSettings(prefs.classReminderEnabled, prefs.classReminderLeadMinutes)
        assertEquals(15 * 60L, ClassReminders.leadSecondsToArm(settings, signedIn = true))
    }

    @Test
    fun `a stored lead time is held to the range`() {
        assertEquals(5 * 60L, ClassReminders.leadSecondsToArm(ClassReminderSettings(true, 0), signedIn = true))
        assertEquals(240 * 60L, ClassReminders.leadSecondsToArm(ClassReminderSettings(true, 10_000), signedIn = true))
        val prefs = AppPreferences(context)
        prefs.classReminderLeadMinutes = -3
        assertEquals(5, prefs.classReminderLeadMinutes)
    }

    // ── The lead times on offer ────────────────────────────────────────────

    @Test
    fun `the screen offers iOS's five lead times`() {
        assertEquals(listOf(5, 10, 15, 30, 60), ClassReminders.leadOptions(10))
    }

    @Test
    fun `a lead time carried over from the Live Update is listed in its place`() {
        assertEquals(listOf(5, 10, 15, 20, 30, 60), ClassReminders.leadOptions(20))
        assertEquals(listOf(5, 10, 15, 30, 60, 120), ClassReminders.leadOptions(120))
    }

    // ── Migration from the Live Update settings ─────────────────────────────

    @Test
    fun `an existing install keeps the reminder it had with the Live Update`() {
        assertEquals(
            ClassReminderSettings(enabled = true, leadMinutes = 15),
            ClassReminders.migrated(true, liveUpdateEnabled = true, showClassPreparing = true, classPreparingLeadSec = 900),
        )
    }

    @Test
    fun `an existing install keeps a lead time the choices do not offer`() {
        assertEquals(
            ClassReminderSettings(enabled = true, leadMinutes = 20),
            ClassReminders.migrated(true, liveUpdateEnabled = true, showClassPreparing = true, classPreparingLeadSec = 1_200),
        )
        assertEquals(
            ClassReminderSettings(enabled = true, leadMinutes = 240),
            ClassReminders.migrated(true, liveUpdateEnabled = true, showClassPreparing = true, classPreparingLeadSec = 14_400),
        )
    }

    @Test
    fun `an install that had no reminder still has none`() {
        assertFalse(ClassReminders.migrated(true, liveUpdateEnabled = false, showClassPreparing = true, classPreparingLeadSec = 900).enabled)
        assertFalse(ClassReminders.migrated(true, liveUpdateEnabled = true, showClassPreparing = false, classPreparingLeadSec = 900).enabled)
    }

    @Test
    fun `a new install starts where an iPhone does`() {
        assertEquals(
            ClassReminderSettings(enabled = true, leadMinutes = 10),
            ClassReminders.migrated(false, liveUpdateEnabled = true, showClassPreparing = true, classPreparingLeadSec = 900),
        )
    }

    @Test
    fun `the migration reads the Live Update settings the previous build stored`() {
        val prefs = AppPreferences(context)
        val liveUpdate = LiveActivityPreferences(context)
        prefs.hasCompletedOnboarding = true
        liveUpdate.isEnabled = true
        liveUpdate.showClassPreparing = true
        liveUpdate.classPreparingLeadTimeSec = 45 * 60

        ClassReminders.migrateIfNeeded(prefs, liveUpdate)

        assertTrue(prefs.classReminderEnabled)
        assertEquals(45, prefs.classReminderLeadMinutes)
        assertFalse("a carried-over lead time is not a pick", prefs.hasPickedClassReminderLead)
    }

    @Test
    fun `the migration turns reminders off for an install whose Live Update was off`() {
        val prefs = AppPreferences(context)
        val liveUpdate = LiveActivityPreferences(context)
        prefs.hasCompletedOnboarding = true
        liveUpdate.isEnabled = false

        ClassReminders.migrateIfNeeded(prefs, liveUpdate)

        assertFalse(prefs.classReminderEnabled)
    }

    @Test
    fun `the migration runs once, and never over a later choice`() {
        val prefs = AppPreferences(context)
        val liveUpdate = LiveActivityPreferences(context)
        prefs.hasCompletedOnboarding = true
        ClassReminders.migrateIfNeeded(prefs, liveUpdate)
        prefs.classReminderEnabled = false
        prefs.classReminderLeadMinutes = 60

        ClassReminders.migrateIfNeeded(prefs, liveUpdate)

        assertFalse(prefs.classReminderEnabled)
        assertEquals(60, prefs.classReminderLeadMinutes)
    }

    // ── The last check before posting ──────────────────────────────────────

    @Test
    fun `a reminder fires only while the switch is on and notifications are allowed`() {
        val prefs = AppPreferences(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        prefs.classReminderEnabled = true
        assertTrue(ClassPreparingNotificationReceiver.mayPost(context, prefs))

        prefs.classReminderEnabled = false
        assertFalse(ClassPreparingNotificationReceiver.mayPost(context, prefs))

        prefs.classReminderEnabled = true
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ClassPreparingNotificationReceiver.mayPost(context, prefs))
    }
}
