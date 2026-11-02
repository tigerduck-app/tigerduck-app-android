package org.ntust.app.tigerduck.sync

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.effectiveCloudSyncEnabled
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a sync category's edits are kept, sent and applied: the one home of the rules each queue,
 * the backend client and the full sync otherwise copy. One value is one reading of its inputs, so
 * every decision a caller takes from it agrees, even when a switch turns meanwhile.
 *
 * - [cloudSync] is the master switch as `AppPreferences.cloudSyncEnabled` reads it, which is
 *   already off on fdroid.
 * - [flavorSyncs] says whether this build has TigerSync at all. It is an input of its own, so a
 *   rule that holds whatever the switches say ([keeps] under [lastWriterWins]) still holds nothing
 *   on a build that never sends.
 * - [switchedOn] holds the categories whose switch is on, and [held] those the re-enable check
 *   holds (`pendingConflictCategories`) until the user answers it.
 * - [lastWriterWins] says the backend settles each field by its stamp, as `/v4` does.
 */
class SyncPolicy(
    val cloudSync: Boolean,
    val flavorSyncs: Boolean,
    val switchedOn: Set<SyncCategory>,
    val held: Set<SyncCategory>,
    val lastWriterWins: Boolean,
) {
    /** Whether TigerSync runs at all: its switch is on, on a build that has it. */
    val tigerSyncOn: Boolean get() = cloudSync && flavorSyncs

    /**
     * Whether TigerSync and [category]'s switch are on, whatever the re-enable hold says: the gate
     * of the backend client's own writes, as `PushApiClient` reads the switches. The hold is its
     * callers' to read, as the queues do through [sends], and Use Local sends while it holds.
     */
    fun syncs(category: SyncCategory): Boolean = tigerSyncOn && category in switchedOn

    /**
     * Whether a course override carrying a [color], a [name] or both goes, as the backend client
     * decides: TigerSync on, and the switch of each kind it carries ([syncs]).
     */
    fun syncsOverride(color: Boolean, name: Boolean): Boolean =
        tigerSyncOn && (!color || syncs(SyncCategory.COURSE_COLORS)) && (!name || syncs(SyncCategory.COURSE_NAMES))

    /**
     * Whether an edit of [category] is kept to send: while TigerSync and the category's switch are
     * on, as `UnsentMarks.recordsMarks` and `PendingCourseOverrides.records` decide today. Under
     * [lastWriterWins] it is kept whatever the switches say, and sent once they are on (LWW D12); a
     * sign-out still clears it. The course list's own queues keep their edits whatever the
     * switches say and do not ask this today.
     */
    fun keeps(category: SyncCategory): Boolean = flavorSyncs && (lastWriterWins || syncs(category))

    /**
     * Whether an edit of [category] goes up now: TigerSync and the category's switch are on, and no
     * re-enable check holds it, as `UnsentMarks.sendsMarks` and `PendingCourseOverrides.sends`
     * decide. Last writer wins changes nothing here: an edit kept while off waits for the switches.
     */
    fun sends(category: SyncCategory): Boolean = when (category) {
        // The course list's writes go while the check holds the list, as Use Local sends them then.
        SyncCategory.COURSES -> uploadsCourses
        SyncCategory.COURSE_COLORS, SyncCategory.COURSE_NAMES, SyncCategory.ASSIGNMENTS ->
            syncs(category) && category !in held
    }

    /**
     * Whether a full sync applies the backend's [category]: its switch is on and no re-enable check
     * holds it, as `CourseSyncReconciler.syncsCourseList`, `syncsCourseColors` and
     * `syncsCourseNames` and `AssignmentOverrideReconciler.reasonToLeaveMarks` decide. Like them it
     * does not read the master switch: a full sync runs only while [tigerSyncOn].
     */
    fun applies(category: SyncCategory): Boolean = category in switchedOn && category !in held

    /**
     * Whether the term's course list, its deletes and a term's reset go up: TigerSync and course
     * sync on, held or not, as `PushApiClient.uploadCoursesAnswered` and `deleteCourse` decide.
     */
    val uploadsCourses: Boolean get() = syncs(SyncCategory.COURSES)

    /**
     * Whether a course's ignore goes up: TigerSync, assignment and course sync on, the last since
     * the backend drops the settings with the course rows once no device syncs courses, as
     * `PushApiClient.patchCourseIgnore` decides. `CourseIgnoreSync` also waits while the
     * assignments check holds them, which is this and [sends] of [SyncCategory.ASSIGNMENTS].
     */
    val sendsCourseIgnores: Boolean get() = syncs(SyncCategory.ASSIGNMENTS) && syncs(SyncCategory.COURSES)
}

/** The policy as the preferences stand now. */
@Singleton
class SyncPolicies @Inject constructor(private val prefs: AppPreferences) {

    /** One reading of the switches and the hold; each read is a new one. */
    val current: SyncPolicy
        get() = SyncPolicy(
            cloudSync = prefs.cloudSyncEnabled,
            flavorSyncs = FLAVOR_SYNCS,
            switchedOn = SyncCategory.entries.filterTo(LinkedHashSet()) { prefs.switchOf(it) },
            held = SyncCategory.of(prefs.school.pendingConflictCategories.get()),
            // Every base speaks /v3, whose writes carry no answer from the backend's clock yet.
            lastWriterWins = false,
        )

    private fun AppPreferences.switchOf(category: SyncCategory): Boolean = when (category) {
        SyncCategory.COURSES -> syncCourses
        SyncCategory.COURSE_COLORS -> syncCourseColors
        SyncCategory.COURSE_NAMES -> syncCourseNames
        SyncCategory.ASSIGNMENTS -> syncAssignments
    }

    private companion object {
        /** Whether this build's flavor has TigerSync: the master switch's own rule, the switch on. */
        val FLAVOR_SYNCS = effectiveCloudSyncEnabled(storedValue = true)
    }
}
