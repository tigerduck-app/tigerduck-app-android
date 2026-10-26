package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences

/** The class reminder's two settings, as [AppPreferences] stores them. */
data class ClassReminderSettings(val enabled: Boolean, val leadMinutes: Int)

/**
 * Class reminders: the "class starting soon" banner before each class, with
 * a switch and a lead time of their own, apart from the Live Update. Mirrors
 * iOS's `LiveActivityPreferencesStore.isClassReminderEnabled` /
 * `classReminderLeadMinutes`, and syncs through the `notification` settings
 * document's `courses` section as iOS does.
 *
 * The backend pushes class reminders only to iPhones and iPads
 * (`server/push/course_reminders.py`), so this phone posts its own from exact
 * alarms ([ClassPreparingNotificationScheduler]).
 */
object ClassReminders {
    /** The lead times on offer, as on iOS (`classReminderLeadChoices`). */
    val LEAD_CHOICES_MINUTES: List<Int> = listOf(5, 10, 15, 30, 60)

    /** iOS's and the backend's default, so a new install reminds as an iPhone does. */
    const val DEFAULT_LEAD_MINUTES = 10

    /**
     * The range a stored lead time is held to: the choices, and the Live
     * Update's class-preparing range a lead time migrated from it came from
     * ([LiveActivityPreferences.MIN_CLASS_LEAD_SEC]..[LiveActivityPreferences.MAX_CLASS_LEAD_SEC]).
     */
    const val MIN_LEAD_MINUTES = 5
    const val MAX_LEAD_MINUTES = 240

    fun clampLead(minutes: Int): Int = minutes.coerceIn(MIN_LEAD_MINUTES, MAX_LEAD_MINUTES)

    /**
     * How far ahead of each class to arm a reminder, in seconds, or null for
     * none. Only the class reminder's own switch decides, with the sign-in:
     * the Live Update's switch and scenarios have no say any more.
     */
    fun leadSecondsToArm(settings: ClassReminderSettings, signedIn: Boolean): Long? =
        if (settings.enabled && signedIn) clampLead(settings.leadMinutes) * 60L else null

    /**
     * The lead times the settings screen lists: the choices, and [current]
     * where it is none of them, a lead time carried over from the Live
     * Update's slider. Picking a choice drops it, as iOS offers no other.
     */
    fun leadOptions(current: Int): List<Int> =
        (LEAD_CHOICES_MINUTES + clampLead(current)).distinct().sorted()

    /**
     * What the class reminder starts as on the first launch of a build that
     * has it.
     *
     * Before, the banner came with the Live Update: it fired at the Live
     * Update's class-preparing lead time, only while the Live Update and its
     * class-preparing scenario were both on. An existing install keeps
     * exactly that: the switch on only if both were, and the same lead time,
     * even one the choices do not offer. A new install, which has not
     * finished onboarding, starts where an iPhone does.
     *
     * This sets the local values only. The settings sync then works as it
     * does for every section: if the account's `notification` document
     * already holds a `courses` section — an iPhone writes one on its first
     * sync, even before its user opens the screen — the first pull after the
     * update adopts it over what was migrated here. Android never writes the
     * section without an edit of its own (`NotificationSettingsSync`), so a
     * migrated value never overwrites an iPhone's choice.
     */
    fun migrated(
        hasCompletedOnboarding: Boolean,
        liveUpdateEnabled: Boolean,
        showClassPreparing: Boolean,
        classPreparingLeadSec: Long,
    ): ClassReminderSettings =
        if (!hasCompletedOnboarding) {
            ClassReminderSettings(enabled = true, leadMinutes = DEFAULT_LEAD_MINUTES)
        } else {
            ClassReminderSettings(
                enabled = liveUpdateEnabled && showClassPreparing,
                leadMinutes = clampLead((classPreparingLeadSec / 60).toInt()),
            )
        }

    /**
     * Runs [migrated] once per install and stores the result. Called first
     * thing in `TigerDuckApp.onCreate`, after `DataMigration`, so it reads
     * the Live Update settings as the previous build left them: before a
     * worker, a receiver or a pull of the settings document can change them,
     * or arm a reminder from the new settings.
     */
    fun migrateIfNeeded(prefs: AppPreferences, liveUpdate: LiveActivityPreferences) {
        if (prefs.classReminderSettingsMigrated) return
        val settings = migrated(
            hasCompletedOnboarding = prefs.hasCompletedOnboarding,
            liveUpdateEnabled = liveUpdate.isEnabled,
            showClassPreparing = liveUpdate.showClassPreparing,
            classPreparingLeadSec = liveUpdate.classPreparingLeadTimeSec,
        )
        prefs.classReminderEnabled = settings.enabled
        prefs.classReminderLeadMinutes = settings.leadMinutes
        prefs.classReminderSettingsMigrated = true
    }
}
