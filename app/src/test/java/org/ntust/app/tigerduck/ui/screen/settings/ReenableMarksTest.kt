package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [markForReenableCheck]: which categories switched back on wait for the
 * re-enable check. A phone that has not synced a category of the signed-in
 * account holds none of its data, so the check has nothing to compare there,
 * and Use Local would send the emptied caches over the server's.
 */
class ReenableMarksTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        prefs = AppPreferences(JvmContext())
    }

    // What HomeBackendSync.pull records once a full sync has run.
    private fun syncFully() {
        prefs.syncedCategories += categoriesSwitchedOn(prefs)
    }

    // What turning TigerSync on does with every category's switch on.
    private fun turnTigerSyncOn(signedIn: Boolean = true) {
        for (category in REENABLE_CATEGORIES) markForReenableCheck(prefs, category, signedIn)
    }

    @Test
    fun `a phone that has synced the account marks every category switched back on`() {
        syncFully()

        turnTigerSyncOn()

        assertEquals(REENABLE_CATEGORIES, prefs.pendingConflictCategories)
    }

    @Test
    fun `after a sign-out, the next sign-in marks nothing and its first sync takes the server's marks`() {
        syncFully()
        // What AuthService.logout clears.
        prefs.clearSyncStamps()

        turnTigerSyncOn()

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(prefs.pendingConflictCategories))
    }

    @Test
    fun `a category switched off in every sync since the sign-in is left to its first sync`() {
        prefs.syncCourses = false
        prefs.syncCourseColors = false
        prefs.syncAssignments = false
        syncFully()

        // Courses back on, which takes their colours with them, and assignments.
        prefs.syncCourses = true
        prefs.syncCourseColors = true
        prefs.syncAssignments = true
        turnTigerSyncOn()

        assertEquals(setOf("course_names"), prefs.pendingConflictCategories)
    }

    @Test
    fun `signed out, nothing is marked for whoever signs in next`() {
        // As after a sync, so the sign-in alone decides.
        syncFully()

        turnTigerSyncOn(signedIn = false)

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
    }

    @Test
    fun `an install signed in at the update keeps its re-enable checks`() {
        // Off at the update, with whatever this phone changed since.
        prefs.syncCourses = false

        DataMigration.settleReenableCheck(prefs, signedIn = true)

        turnTigerSyncOn()
        assertEquals(REENABLE_CATEGORIES, prefs.pendingConflictCategories)
    }

    @Test
    fun `an assignments check left by a build that never held the marks is dropped at the update`() {
        // v2.3.1 went on applying the server's marks while its check waited,
        // so there is nothing of this phone's left to ask about; kept, it
        // would hold the marks until TigerSync was next opened.
        prefs.pendingConflictCategories = setOf("assignments", "course_names")

        DataMigration.settleReenableCheck(prefs, signedIn = true)

        assertEquals(setOf("course_names"), prefs.pendingConflictCategories)
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(prefs.pendingConflictCategories))
    }

    @Test
    fun `a fresh install, or one signed out at the update, marks nothing before its first sync`() {
        DataMigration.settleReenableCheck(prefs, signedIn = false)

        // Signed in since.
        turnTigerSyncOn()

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
    }

    @Test
    fun `an install signed out at the update drops the checks an older build left pending`() {
        // Marked while signed out, the check failing for want of a session.
        prefs.pendingConflictCategories = setOf("assignments", "courses")

        DataMigration.settleReenableCheck(prefs, signedIn = false)

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(prefs.pendingConflictCategories))
    }

    @Test
    fun `assignments are not recorded as synced when the switch went off during the sync`() {
        val switchedOn = categoriesSwitchedOn(prefs)
        // Turned off while the sync ran, so its marks step left the marks alone.
        prefs.syncAssignments = false

        prefs.syncedCategories += categoriesSynced(switchedOn, REENABLE_CATEGORIES, prefs)

        assertEquals(REENABLE_CATEGORIES - "assignments", prefs.syncedCategories)
    }

    @Test
    fun `every category the sync read as on is recorded when none went off`() {
        assertEquals(REENABLE_CATEGORIES, categoriesSynced(categoriesSwitchedOn(prefs), REENABLE_CATEGORIES, prefs))
    }

    @Test
    fun `a courses check an older build left pending stops holding the course list at the update`() {
        // Left by a build that kept reconciling the course list while it waited.
        prefs.pendingConflictCategories = setOf("courses", "assignments")

        DataMigration.dropStaleCoursesCheck(prefs)

        assertEquals(setOf("assignments"), prefs.pendingConflictCategories)
        assertTrue(CourseSyncReconciler.syncsCourseList(prefs.syncCourses, prefs.pendingConflictCategories))
    }

    private fun taken(
        marksTaken: Boolean = true,
        courseListSynced: Boolean = true,
        syncColors: Boolean = true,
        syncNames: Boolean = true,
        hasOverrides: Boolean = true,
        coursesLoaded: Boolean = true,
    ) = categoriesTaken(marksTaken, courseListSynced, syncColors, syncNames, hasOverrides, coursesLoaded)

    @Test
    fun `a category the sync skipped is not recorded as synced`() {
        // Names held for their re-enable check, the course list for its own,
        // and marks left alone: none of their server data was taken.
        val took = taken(marksTaken = false, courseListSynced = false, syncNames = false)

        prefs.syncedCategories += categoriesSynced(categoriesSwitchedOn(prefs), took, prefs)

        assertEquals(setOf("course_colors"), prefs.syncedCategories)
    }

    @Test
    fun `colours and names are taken only over a loaded course list, unless the server has none`() {
        assertEquals(setOf("assignments", "courses"), taken(coursesLoaded = false))
        assertEquals(REENABLE_CATEGORIES, taken(coursesLoaded = false, hasOverrides = false))
        assertEquals(REENABLE_CATEGORIES, taken())
    }

    @Test
    fun `colours and names stay held until Use Local has sent them`() {
        // Let go on the tap, a sync during Use Local's course upload wrote the
        // server's colours and names into the cache, and Use Local then sent
        // those back as this phone's.
        assertEquals(
            setOf("assignments", "course_colors", "course_names"),
            heldUntilAnswered(listOf("courses", "course_colors", "course_names", "assignments")),
        )
        assertEquals(setOf("course_names"), heldUntilAnswered(listOf("courses", "course_names")))
        assertEquals(emptySet<String>(), heldUntilAnswered(listOf("courses")))
    }
}
