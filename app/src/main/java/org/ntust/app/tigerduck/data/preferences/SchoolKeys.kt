package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences

/**
 * The school account's keys in `tigerduck_prefs`. Each writes only with a sign-in ([AccountKey]);
 * [AppPreferences] holds the raw file, and `clearSyncStamps` and the sign-out's other clearings
 * take the keys off it.
 */
class SchoolKeys internal constructor(
    prefs: SharedPreferences,
    onSchoolDataSynced: (Long) -> Unit,
) {
    /**
     * The re-enable check's categories waiting for the user's answer, and until it comes what
     * every sync leaves alone; see `ReenableCheck`.
     */
    val pendingConflictCategories = AccountKey<Set<String>>(
        read = { prefs.getStringSet("pendingConflictCategories", emptySet()) ?: emptySet() },
        write = { prefs.edit().putStringSet("pendingConflictCategories", it).apply() },
        empty = emptySet(),
    )

    /**
     * The re-enable check's categories, named as in [pendingConflictCategories],
     * whose data a full sync has applied on this phone for the signed-in
     * account. Only these are marked for the check when switched back on: for
     * any other, the phone holds none of the account's data to compare. Added
     * to by `HomeBackendSync.pull`, filled by `DataMigration.migrate3to4`, and
     * cleared at sign-out by [AppPreferences.clearSyncStamps].
     */
    val syncedCategories = AccountKey<Set<String>>(
        read = { prefs.getStringSet("syncedCategories", emptySet()) ?: emptySet() },
        write = { prefs.edit().putStringSet("syncedCategories", it).apply() },
        empty = emptySet(),
    )

    /**
     * Whether a full sync has taken the signed-in account's assignment marks
     * on this phone, or an older build sent them up as its first sync, so the
     * marks have synced here before (LWW D13): no first sync queues them as
     * the oldest again. Cleared at sign-out by [AppPreferences.clearSyncStamps].
     * Turning sync off keeps it: turning it back on marks the category for
     * the re-enable check, and syncs leave the marks alone until that is
     * settled.
     */
    val assignmentMarksSyncedOnce = AccountKey(
        read = { prefs.getBoolean("assignmentMarksSyncedOnce", false) },
        write = { prefs.edit().putBoolean("assignmentMarksSyncedOnce", it).apply() },
        empty = false,
    )

    /**
     * The server's `courses_reset_at` as an older build's last full sync read
     * it, to tell a new reset from one already applied. Nothing reads it any
     * more: a reset's tombstones take the courses it cleared, field by field
     * (LWW D10). Kept so a sign-out still clears what an older build stored
     * ([AppPreferences.clearSyncStamps]).
     */
    val lastSeenCoursesResetAt = AccountKey<String?>(
        read = { prefs.getString("lastSeenCoursesResetAt", null) },
        write = { prefs.edit().putString("lastSeenCoursesResetAt", it).apply() },
        empty = null,
    )

    /** When the school session last signed in, epoch ms; `NtustSessionManager` dates its cookies by it. */
    val ssoLoginTimestamp = AccountKey(
        read = { prefs.getLong("ssoLoginTimestamp", 0L) },
        write = { prefs.edit().putLong("ssoLoginTimestamp", it).apply() },
        empty = 0L,
    )

    /**
     * When Moodle last answered a whole round of assignments, epoch ms; 0
     * for never, or not since the last sign-out. The age of what Home, the
     * class table and the calendar show, and what the background worker
     * measures its assignment refresh against — see
     * [org.ntust.app.tigerduck.ui.RefreshPolicies]. Read through
     * [AppPreferences.schoolDataSyncedAtMs], the flow that follows this.
     */
    val schoolDataSyncedAtMs = AccountKey(
        read = { prefs.getLong("schoolDataSyncedAtMs", 0L) },
        write = {
            prefs.edit().putLong("schoolDataSyncedAtMs", it).apply()
            onSchoolDataSynced(it)
        },
        empty = 0L,
    )

    /**
     * The assignment list the backend last accepted from this phone, as a
     * fingerprint, and when; see `push.UploadLedger`. Forgotten at sign-out
     * by [AppPreferences.clearSyncStamps] and when a sync switch comes back on
     * by [AppPreferences.forgetUploads].
     */
    val assignmentsUploadFingerprint = AccountKey<String?>(
        read = { prefs.getString("assignmentsUploadFingerprint", null) },
        write = { prefs.edit().putString("assignmentsUploadFingerprint", it).apply() },
        empty = null,
    )

    val assignmentsUploadedAtMs = AccountKey(
        read = { prefs.getLong("assignmentsUploadedAtMs", 0L) },
        write = { prefs.edit().putLong("assignmentsUploadedAtMs", it).apply() },
        empty = 0L,
    )

    /** When the background worker last synced with the backend, epoch ms. */
    val backgroundOverridesSyncedAtMs = AccountKey(
        read = { prefs.getLong("backgroundOverridesSyncedAtMs", 0L) },
        write = { prefs.edit().putLong("backgroundOverridesSyncedAtMs", it).apply() },
        empty = 0L,
    )

    /**
     * When the background worker last refreshed the timetable, epoch ms. Its
     * own clock, unlike assignments': only the worker reads it, and only if
     * the class table asks for background refreshes.
     */
    val backgroundCoursesSyncedAtMs = AccountKey(
        read = { prefs.getLong("backgroundCoursesSyncedAtMs", 0L) },
        write = { prefs.edit().putLong("backgroundCoursesSyncedAtMs", it).apply() },
        empty = 0L,
    )

    /**
     * The term the background worker last fetched courses in, by the app's clock; null before the
     * first. Its first run in another term fetches that term's courses.
     */
    val backgroundCoursesTerm = AccountKey<String?>(
        read = { prefs.getString("backgroundCoursesTerm", null) },
        write = { prefs.edit().putString("backgroundCoursesTerm", it).apply() },
        empty = null,
    )

    /** Semester the user last viewed in the class table. Null until first pick. */
    val classTableSelectedSemester = AccountKey<String?>(
        read = { prefs.getString("classTableSelectedSemester", null) },
        write = { value ->
            val editor = prefs.edit()
            if (value == null) editor.remove("classTableSelectedSemester")
            else editor.putString("classTableSelectedSemester", value)
            editor.apply()
        },
        empty = null,
    )

    /**
     * The building the empty-classroom finder last showed at the user's pick, by the booking
     * site's code (e.g. "TR"). Null until the first pick; cleared at sign-out, so the next
     * account opens on its own building.
     */
    val emptyClassroomBuilding = AccountKey<String?>(
        read = { prefs.getString("emptyClassroomBuilding", null) },
        write = { value ->
            val editor = prefs.edit()
            if (value == null) editor.remove("emptyClassroomBuilding")
            else editor.putString("emptyClassroomBuilding", value)
            editor.apply()
        },
        empty = null,
    )
}
