package org.ntust.app.tigerduck.ui.screen.settings

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import javax.inject.Inject

/**
 * What signing out of the school account deletes that exists nowhere else, each named by a line
 * of the confirmation the sign-out asks first with. With none, it signs out at once.
 */
enum class SignOutLoss(@param:StringRes val message: Int) {
    /**
     * The user's own edits that TigerSync does not keep: it is off, that kind's switch is, or no
     * full sync has taken that kind on this phone yet.
     */
    UNSYNCED_DATA(R.string.settings_sign_out_unsynced_data),

    /** The class tables imported from share codes, which only this phone has. Last: "…too". */
    IMPORTED_CLASS_TABLES(R.string.settings_sign_out_imported_tables),
}

/** The user's own edits on this phone, by the TigerSync switch that would keep each. */
data class LocalEdits(
    /** Courses added by hand, or hidden from the class table. */
    val courses: Boolean = false,
    val courseNames: Boolean = false,
    val courseColours: Boolean = false,
    /** Assignments ignored or marked done. */
    val assignmentMarks: Boolean = false,
    /** Courses whose assignments are ignored, which sync only with assignments and courses both. */
    val ignoredCourses: Boolean = false,
    /** Holidays the user kept class reminders on, which sync with TigerSync alone. */
    val holidays: Boolean = false,
)

/**
 * Edits the server has not confirmed, which a sign-out deletes even with their kind synced: a
 * course added by hand that has not been uploaded or whose add still waits (PendingCourseAdds),
 * a removal whose DELETE still waits (PendingCourseDeletes), a colour or name still waiting to go
 * up (PendingCourseOverrides), a name on a course with no Moodle ID, which never goes up
 * ([holdsLocalOnlyCourseNames]), a holiday choice still waiting to go up
 * (PendingHolidayOverrides), an ignore not yet confirmed, a done or ignored mark still waiting to
 * be sent (UnsentMarks), and edits whose upload the re-enable check holds back (the sign-out
 * drops the hold).
 */
data class Unconfirmed(
    val courses: Boolean = false,
    val courseNames: Boolean = false,
    val courseColours: Boolean = false,
    val assignments: Boolean = false,
    val holidays: Boolean = false,
)

/** What TigerSync keeps for the account; on fdroid, nothing. */
data class SyncedKinds(
    val enabled: Boolean,
    val courses: Boolean,
    val courseNames: Boolean,
    val courseColours: Boolean,
    val assignments: Boolean,
    /**
     * The kinds, by their re-enable check names ([REENABLE_CATEGORIES]), whose server data a full
     * sync has taken on this phone for the account: [AppPreferences.syncedCategories]. Before that,
     * as after a sign-in, the server may hold none of what the phone has.
     */
    val syncedOnce: Set<String>,
)

fun signOutLosses(
    edits: LocalEdits,
    synced: SyncedKinds,
    hasImportedClassTables: Boolean,
    unconfirmed: Unconfirmed = Unconfirmed(),
): Set<SignOutLoss> {
    // As the iPhone's signOutDeletesUnsyncedData: TigerSync on, the kind's switch on, and its data
    // taken by a full sync here.
    fun kept(switchedOn: Boolean, category: String) = synced.enabled && switchedOn && category in synced.syncedOnce
    val keepsAssignments = kept(synced.assignments, "assignments")
    val keepsCourses = kept(synced.courses, "courses")
    val unsynced = (edits.courses && !keepsCourses) ||
        (edits.courseNames && !kept(synced.courseNames, "course_names")) ||
        (edits.courseColours && !kept(synced.courseColours, "course_colors")) ||
        (edits.assignmentMarks && !keepsAssignments) ||
        // CourseIgnoreSync.syncsCourseIgnores: both switches.
        (edits.ignoredCourses && !(keepsAssignments && keepsCourses)) ||
        // No switch of their own: a full sync sends and takes them with TigerSync on.
        (edits.holidays && !synced.enabled) ||
        unconfirmed.holidays ||
        unconfirmed.courses || unconfirmed.courseNames || unconfirmed.courseColours || unconfirmed.assignments
    return buildSet {
        if (unsynced) add(SignOutLoss.UNSYNCED_DATA)
        if (hasImportedClassTables) add(SignOutLoss.IMPORTED_CLASS_TABLES)
    }
}

/**
 * Whether a name the user gave a course in [courses] stays on this phone alone. A name goes up
 * under its course's Moodle ID (`ClassTableViewModel.syncCourseOverride`), and the course upload
 * carries none, so a course without one, as every course added by hand is, keeps its name here
 * whatever the course_names switch says. Each course by its own ID: a rename goes up through the
 * course renamed alone, so another term's course of that number with a Moodle ID has not sent it.
 * As iOS's holdsLocalOnlyCourseNames (tigerduck-app/tigerduck-app#744).
 */
internal fun holdsLocalOnlyCourseNames(names: Map<String, Map<String, String>>, courses: List<Course>): Boolean =
    courses.any { course ->
        course.moodleIdNumber == null &&
            (course.customCourseName != null || names[course.courseNo].orEmpty().values.any { it.isNotEmpty() })
    }

/**
 * Reads what this phone holds for [signOutLosses]. Off the main thread: it reads every term's
 * cached courses.
 */
class SignOutLossCheck @Inject constructor(
    private val dataCache: DataCache,
    private val prefs: AppPreferences,
    private val importedClassTables: ImportedClassTableStore,
) {
    suspend fun losses(): Set<SignOutLoss> {
        val terms = dataCache.loadEveryTermCourses()
        val courses = terms.values.flatten()
        val names = dataCache.loadCourseCustomNames()
        val edits = LocalEdits(
            courses = courses.any { it.isManual } || dataCache.loadDeletedCourseNos().isNotEmpty(),
            courseNames = courses.any { it.customCourseName != null } || names.values.any { it.isNotEmpty() },
            courseColours = courses.any { it.customColorHex != null },
            assignmentMarks = dataCache.loadIgnoredAssignments().isNotEmpty() ||
                dataCache.loadMarkedCompletedAssignments().isNotEmpty(),
            ignoredCourses = dataCache.loadIgnoredCourses().isNotEmpty(),
            holidays = prefs.holidayNotifyOverrides.isNotEmpty(),
        )
        val synced = SyncedKinds(
            enabled = prefs.cloudSyncEnabled,
            courses = prefs.syncCourses,
            courseNames = prefs.syncCourseNames,
            courseColours = prefs.syncCourseColors,
            assignments = prefs.syncAssignments,
            // The background worker's full sync records only that it took the marks.
            syncedOnce = prefs.syncedCategories + if (prefs.assignmentMarksSyncedOnce) setOf("assignments") else emptySet(),
        )
        // Held back for the re-enable check, by its category names (REENABLE_CATEGORIES).
        val held = prefs.pendingConflictCategories
        val serverKnown = dataCache.loadServerKnownNos()
        val overrides = dataCache.loadPendingCourseOverrides()
        // Only adds whose course is still here: PendingCourseAdds lets the others go unsent.
        val adds = dataCache.loadPendingCourseAdds()
        val unconfirmed = Unconfirmed(
            courses = (edits.courses && "courses" in held) || terms.any { (semester, termCourses) ->
                termCourses.any {
                    (it.isManual && it.courseNo !in serverKnown[semester].orEmpty()) ||
                        it.courseNo in adds[semester].orEmpty()
                }
            } || dataCache.loadPendingCourseDeletes().values.any { it.isNotEmpty() },
            courseNames = (edits.courseNames && "course_names" in held) || overrides.any { it.customName != null } ||
                holdsLocalOnlyCourseNames(names, courses),
            courseColours = (edits.courseColours && "course_colors" in held) || overrides.any { it.colorHex != null },
            assignments = ((edits.assignmentMarks || edits.ignoredCourses) && "assignments" in held) ||
                dataCache.loadPendingCourseIgnores().isNotEmpty() ||
                // A mark tapped offline waits in unsent_marks.json for the next sync.
                dataCache.loadUnsentMarks().isNotEmpty(),
            holidays = prefs.holidayOverridesAwaitingUpload.isNotEmpty(),
        )
        return signOutLosses(edits, synced, importedClassTables.load().hasImportedClassTables, unconfirmed)
    }
}
