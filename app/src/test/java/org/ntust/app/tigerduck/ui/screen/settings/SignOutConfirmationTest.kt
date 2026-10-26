package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLoss.IMPORTED_CLASS_TABLES
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLoss.UNSYNCED_DATA
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Signing out deletes what exists only on the phone, so it asks first and says what goes: the
 * edits TigerSync does not keep (always, on fdroid), and the imported class tables, which the
 * iPhone app asks about.
 */
class SignOutConfirmationTest {

    private val allSynced = SyncedKinds(
        enabled = true, courses = true, courseNames = true, courseColours = true, assignments = true,
        syncedOnce = REENABLE_CATEGORIES,
    )
    private val syncOff = allSynced.copy(enabled = false)

    @Test
    fun `nothing of the user's own signs out at once`() {
        assertEquals(emptySet<SignOutLoss>(), signOutLosses(LocalEdits(), syncOff, hasImportedClassTables = false))
    }

    @Test
    fun `with TigerSync off, every kind of edit is asked about`() {
        listOf(
            LocalEdits(courses = true),
            LocalEdits(courseNames = true),
            LocalEdits(courseColours = true),
            LocalEdits(assignmentMarks = true),
            LocalEdits(ignoredCourses = true),
            LocalEdits(holidays = true),
        ).forEach { edits ->
            assertEquals("$edits", setOf(UNSYNCED_DATA), signOutLosses(edits, syncOff, hasImportedClassTables = false))
        }
    }

    @Test
    fun `edits TigerSync keeps are not asked about, one whose switch is off is`() {
        val edits = LocalEdits(courses = true, courseColours = true, assignmentMarks = true)

        assertEquals(emptySet<SignOutLoss>(), signOutLosses(edits, allSynced, hasImportedClassTables = false))
        assertEquals(
            setOf(UNSYNCED_DATA),
            signOutLosses(edits, allSynced.copy(courseColours = false), hasImportedClassTables = false),
        )
    }

    @Test
    fun `a kind no full sync has taken on this phone is asked about, its switch on`() {
        // As after a sign-in before the first sync: the server may have none of it.
        val edits = LocalEdits(courseColours = true)

        assertEquals(
            setOf(UNSYNCED_DATA),
            signOutLosses(edits, allSynced.copy(syncedOnce = REENABLE_CATEGORIES - "course_colors"), hasImportedClassTables = false),
        )
        assertEquals(
            emptySet<SignOutLoss>(),
            signOutLosses(edits, allSynced.copy(syncedOnce = setOf("course_colors")), hasImportedClassTables = false),
        )
    }

    @Test
    fun `ignored courses are kept only with assignments and courses both synced`() {
        // CourseIgnoreSync sends them with both switches on, as the iPhone does.
        val edits = LocalEdits(ignoredCourses = true)

        assertEquals(emptySet<SignOutLoss>(), signOutLosses(edits, allSynced, hasImportedClassTables = false))
        assertEquals(setOf(UNSYNCED_DATA), signOutLosses(edits, allSynced.copy(courses = false), hasImportedClassTables = false))
        assertEquals(setOf(UNSYNCED_DATA), signOutLosses(edits, allSynced.copy(assignments = false), hasImportedClassTables = false))
        assertEquals(
            setOf(UNSYNCED_DATA),
            signOutLosses(edits, allSynced.copy(syncedOnce = REENABLE_CATEGORIES - "courses"), hasImportedClassTables = false),
        )
    }

    @Test
    fun `holiday choices are kept with TigerSync alone`() {
        // Not a category with a switch: a full sync sends and takes them whatever the switches say.
        val edits = LocalEdits(holidays = true)
        val switchesOff = allSynced.copy(courses = false, courseNames = false, courseColours = false, assignments = false, syncedOnce = emptySet())

        assertEquals(emptySet<SignOutLoss>(), signOutLosses(edits, switchesOff, hasImportedClassTables = false))
        assertEquals(setOf(UNSYNCED_DATA), signOutLosses(edits, syncOff, hasImportedClassTables = false))
    }

    @Test
    fun `imported class tables are always asked about, after the edits`() {
        assertEquals(setOf(IMPORTED_CLASS_TABLES), signOutLosses(LocalEdits(), allSynced, hasImportedClassTables = true))
        assertEquals(
            listOf(UNSYNCED_DATA, IMPORTED_CLASS_TABLES),
            signOutLosses(LocalEdits(assignmentMarks = true), syncOff, hasImportedClassTables = true).sortedBy { it.ordinal },
        )
    }

    // What the phone holds

    private val context: Context = JvmContext()
    private val prefs = AppPreferences(context).apply { cloudSyncEnabled = false }
    private val dataCache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val imported = ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { "B11300000" }, Dispatchers.Unconfined)
    private val check = SignOutLossCheck(dataCache, prefs, imported)

    private fun course(no: String) = Course(courseNo = no, courseName = "Course $no")

    @Test
    fun `fetched courses alone are not the user's own`() = runTest {
        dataCache.saveCourses(listOf(course("CS1001301")), "1141")

        assertEquals(emptySet<SignOutLoss>(), check.losses())
    }

    @Test
    fun `a course added by hand in an earlier term is the user's own`() = runTest {
        dataCache.saveCourses(listOf(course("CS1001301"), course("XX0000001").copy(isManual = true)), "1132")

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a picked colour and a marked assignment are the user's own`() = runTest {
        dataCache.saveCourses(listOf(course("CS1001301").copy(customColorHex = "#FF0000")), "1141")
        assertEquals(setOf(UNSYNCED_DATA), check.losses())

        dataCache.saveCourses(listOf(course("CS1001301")), "1141")
        dataCache.saveMarkedCompletedAssignments(setOf("a1"))
        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `an imported class table is named`() = runTest {
        val code = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK,
            "B11300001",
            null,
            listOf(ClassTableShareCode.Term("1141", listOf("CS1001301"))),
        )
        imported.add(code, "Ming", emptyMap())

        assertEquals(setOf(IMPORTED_CLASS_TABLES), check.losses())
    }

    // With TigerSync on: what the server has not confirmed yet

    @Test
    fun `an edit the server has not confirmed is asked about with its kind synced`() {
        assertEquals(
            setOf(UNSYNCED_DATA),
            signOutLosses(LocalEdits(), allSynced, hasImportedClassTables = false, unconfirmed = Unconfirmed(assignments = true)),
        )
    }

    private fun syncOn() {
        prefs.cloudSyncEnabled = true
        // F-Droid has no TigerSync, where every edit is asked about anyway.
        assumeTrue(prefs.cloudSyncEnabled)
        // And a full sync on this phone has taken every kind.
        prefs.syncedCategories = REENABLE_CATEGORIES
    }

    @Test
    fun `a course added by hand that the server has not had yet is asked about`() = runTest {
        syncOn()
        dataCache.saveCourses(listOf(course("XX0000001").copy(isManual = true)), "1141")
        assertEquals(setOf(UNSYNCED_DATA), check.losses())

        dataCache.saveServerKnownNos(mapOf("1141" to listOf("XX0000001")))
        assertEquals(emptySet<SignOutLoss>(), check.losses())
    }

    /** A course added back by hand whose add still waits (PendingCourseAdds): the server once had its number. */
    @Test
    fun `a course added back whose add still waits is asked about`() = runTest {
        syncOn()
        dataCache.saveCourses(listOf(course("XX0000001").copy(isManual = true)), "1141")
        dataCache.saveServerKnownNos(mapOf("1141" to listOf("XX0000001")))
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        dataCache.savePendingCourseAdds(mapOf("1141" to listOf("XX0000001")))
        assertEquals(setOf(UNSYNCED_DATA), check.losses())

        // An add whose course has left this phone is not sent again, and nothing of it is lost.
        dataCache.saveCourses(emptyList(), "1141")
        assertEquals(emptySet<SignOutLoss>(), check.losses())
    }

    @Test
    fun `an ignore the server has not confirmed is asked about`() = runTest {
        syncOn()
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        dataCache.savePendingCourseIgnores(
            listOf(PendingCourseIgnore("1141", "CS1001301", "1141CS1001301", null, ignore = true, changedAtMs = 1L)),
        )

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a done or ignored mark the server has not confirmed is asked about`() = runTest {
        syncOn()
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // Marked done offline: the mark waits in unsent_marks.json for the next sync, and the
        // sign-out would delete it before the server ever had it.
        dataCache.saveUnsentMarks(listOf(UnsentMark("a1", "locally_completed")))

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `edits held for the re-enable check are asked about`() = runTest {
        syncOn()
        dataCache.saveCourses(listOf(course("CS1001301").copy(customColorHex = "#FF0000")), "1141")
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // The sign-out clears the hold, and the colours it held back never reach the server.
        prefs.pendingConflictCategories = setOf("course_colors")

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `an edit of a kind no full sync has taken here yet is asked about`() = runTest {
        syncOn()
        dataCache.saveCourses(listOf(course("CS1001301").copy(customColorHex = "#FF0000")), "1141")
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        prefs.syncedCategories = REENABLE_CATEGORIES - "course_colors"

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `marks a background sync has taken are kept, before any sync from Home`() = runTest {
        syncOn()
        prefs.syncedCategories = emptySet()
        dataCache.saveMarkedCompletedAssignments(setOf("a1"))
        assertEquals(setOf(UNSYNCED_DATA), check.losses())

        // BackgroundSyncWorker took the account's marks, which records only this.
        prefs.assignmentMarksSyncedOnce = true

        assertEquals(emptySet<SignOutLoss>(), check.losses())
    }

    @Test
    fun `an ignored course is asked about while courses do not sync`() = runTest {
        syncOn()
        dataCache.saveIgnoredCourses(setOf("1141CS1001301"))
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        prefs.syncCourses = false

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a colour or name still waiting to go up is asked about`() = runTest {
        syncOn()
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // Set offline: the colour waits in PendingCourseOverrides, which the sign-out wipes.
        dataCache.savePendingCourseOverrides(
            listOf(PendingCourseOverride("1141CS1001301", "#FF0000", null, null, changedAtMs = 1L)),
        )

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a name on a course added by hand is asked about with names synced, as it never goes up`() = runTest {
        syncOn()
        // The server has had the course itself, so only its name is at stake.
        dataCache.saveCourses(listOf(course("XX0000001").copy(isManual = true)), "1141")
        dataCache.saveServerKnownNos(mapOf("1141" to listOf("XX0000001")))
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // Renamed in the other language, so the course on the table carries no name of its own.
        dataCache.saveCourseCustomNames(mapOf("XX0000001" to mapOf("en" to "Morning Math")))
        assertEquals(setOf(UNSYNCED_DATA), check.losses())

        dataCache.saveCourseCustomNames(emptyMap())
        dataCache.saveCourses(listOf(course("XX0000001").copy(isManual = true, customCourseName = "早八")), "1141")
        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a name on a course added by hand is asked about though another term's course of that number has a Moodle ID`() = runTest {
        syncOn()
        // Taken for credit in an earlier term, added by hand again in this one.
        dataCache.saveCourses(listOf(course("CS1001301").copy(moodleIdNumber = "1132CS1001301")), "1132")
        dataCache.saveCourses(listOf(course("CS1001301").copy(isManual = true)), "1141")
        dataCache.saveServerKnownNos(mapOf("1141" to listOf("CS1001301")))
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // Renamed on this term's table: the name goes through this term's course alone, which has no Moodle ID.
        dataCache.saveCourses(listOf(course("CS1001301").copy(isManual = true, customCourseName = "早八")), "1141")
        dataCache.saveCourseCustomNames(mapOf("CS1001301" to mapOf("zh" to "早八")))

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a name on an enrolled course is kept with names synced`() = runTest {
        syncOn()
        dataCache.saveCourses(
            listOf(course("CS1001301").copy(moodleIdNumber = "1141CS1001301", customCourseName = "早八")),
            "1141",
        )
        dataCache.saveCourseCustomNames(mapOf("CS1001301" to mapOf("zh" to "早八")))

        assertEquals(emptySet<SignOutLoss>(), check.losses())
    }

    @Test
    fun `a removal still waiting to go up is asked about`() = runTest {
        syncOn()
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // Its DELETE waits in PendingCourseDeletes: lost, the course comes back at the next sign-in.
        dataCache.savePendingCourseDeletes(mapOf("1141" to mapOf("CS1001301" to 1L)))

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a holiday choice is the user's own with TigerSync off`() = runTest {
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        prefs.holidayNotifyOverrides = setOf(3)

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a holiday choice still waiting to go up is asked about`() = runTest {
        syncOn()
        prefs.holidayNotifyOverrides = setOf(3)
        assertEquals(emptySet<SignOutLoss>(), check.losses())

        // Its PUT failed: PendingHolidayOverrides keeps it, and the sign-out wipes it.
        prefs.holidayOverridesAwaitingUpload = setOf(3)

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }
}
