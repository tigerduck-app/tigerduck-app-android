package org.ntust.app.tigerduck.sync

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.CourseRef
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncCategory.ASSIGNMENTS
import org.ntust.app.tigerduck.sync.SyncCategory.COURSES
import org.ntust.app.tigerduck.sync.SyncCategory.COURSE_COLORS
import org.ntust.app.tigerduck.sync.SyncCategory.COURSE_NAMES
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [SyncPolicy] is pinned to each rule it is the home of, on every combination of the switches and
 * the hold: each answer is checked against the rule as the preferences spell it, the backend
 * client's gates by whether a request went out. Both flavors run this, so the flavor is covered as
 * the build gives it, and as an input of its own below. The backend settles each field by its
 * stamp (last writer wins), so a category's edit is kept whatever its switches say.
 */
class SyncPolicyTest {

    private val backend = ScriptedBackend()
    private val context = JvmContext()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = BASE }
    private val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-sync-policy-test", Context.MODE_PRIVATE))
        .apply { save("access", "refresh", Long.MAX_VALUE) }
    private val tokenManager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    private val client = PushApiClient(backend.client, prefs, tokenManager)
    private val policies = SyncPolicies(prefs)
    private val signIn get() = tokenManager.session.signIns.current

    /** Every set of categories: the sixteen settings of the four switches, or of the hold. */
    private val everySet: List<Set<SyncCategory>> = (0 until 16).map { bits ->
        SyncCategory.entries.filterIndexed { i, _ -> bits shr i and 1 == 1 }.toSet()
    }

    private fun store(master: Boolean, on: Set<SyncCategory>, held: Set<SyncCategory>) {
        prefs.cloudSyncEnabled = master
        prefs.syncCourses = COURSES in on
        prefs.syncCourseColors = COURSE_COLORS in on
        prefs.syncCourseNames = COURSE_NAMES in on
        prefs.syncAssignments = ASSIGNMENTS in on
        // A category a newer build may hold is no category of this one's, for either side.
        assertTrue(prefs.school.pendingConflictCategories.set(held.map { it.stored }.toSet() + "bulletins", signIn))
    }

    /** Whether the client's call let a request out. */
    private suspend fun sent(call: suspend () -> Unit): Boolean {
        val before = backend.endpoints().size
        call()
        return backend.endpoints().size > before
    }

    @Test
    fun `every answer equals its rule, on every combination of the switches and the hold`() = runTest {
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val roster = listOf(Course(courseNo = "CS1", courseName = "CS1"))
        var rows = 0
        for (master in listOf(false, true)) for (on in everySet) for (held in everySet) {
            store(master, on, held)
            val policy = policies.current
            val pending = prefs.school.pendingConflictCategories.get()
            val row = "master=$master on=$on held=$held"
            fun check(what: String, today: Boolean, ours: Boolean) = assertEquals("$what at $row", today, ours)

            // Last writer wins: kept whatever the switches say, where the build has TigerSync.
            check("keeps(ASSIGNMENTS)", policy.flavorSyncs, policy.keeps(ASSIGNMENTS))
            check("sends(ASSIGNMENTS)", prefs.cloudSyncEnabled && prefs.syncAssignments && ASSIGNMENTS.stored !in pending, policy.sends(ASSIGNMENTS))
            check("keeps(COURSE_COLORS)", policy.flavorSyncs, policy.keeps(COURSE_COLORS))
            check("keeps(COURSE_NAMES)", policy.flavorSyncs, policy.keeps(COURSE_NAMES))
            check(
                "sends(COURSE_COLORS)",
                prefs.cloudSyncEnabled && prefs.syncCourseColors && COURSE_COLORS.stored !in pending, policy.sends(COURSE_COLORS),
            )
            check(
                "sends(COURSE_NAMES)",
                prefs.cloudSyncEnabled && prefs.syncCourseNames && COURSE_NAMES.stored !in pending, policy.sends(COURSE_NAMES),
            )
            check("applies(COURSE_COLORS)", prefs.syncCourseColors && COURSE_COLORS.stored !in pending, policy.applies(COURSE_COLORS))
            check("applies(COURSE_NAMES)", prefs.syncCourseNames && COURSE_NAMES.stored !in pending, policy.applies(COURSE_NAMES))
            check("applies(COURSES)", prefs.syncCourses && COURSES.stored !in pending, policy.applies(COURSES))
            check(
                "applies(ASSIGNMENTS)",
                AssignmentOverrideReconciler.reasonToLeaveMarks(prefs.syncAssignments, pending) == null,
                policy.applies(ASSIGNMENTS),
            )
            check("tigerSyncOn", prefs.cloudSyncEnabled, policy.tigerSyncOn)
            // The backend client's own gates, which read the switches and never the hold.
            check("syncs(COURSES)", prefs.cloudSyncEnabled && prefs.syncCourses, policy.syncs(COURSES))
            check("syncs(COURSE_COLORS)", prefs.cloudSyncEnabled && prefs.syncCourseColors, policy.syncs(COURSE_COLORS))
            check("syncs(COURSE_NAMES)", prefs.cloudSyncEnabled && prefs.syncCourseNames, policy.syncs(COURSE_NAMES))
            check("syncs(ASSIGNMENTS)", prefs.cloudSyncEnabled && prefs.syncAssignments, policy.syncs(ASSIGNMENTS))
            for (color in listOf(false, true)) for (name in listOf(false, true)) check(
                "syncsOverride(color=$color, name=$name)",
                prefs.cloudSyncEnabled && (!color || prefs.syncCourseColors) && (!name || prefs.syncCourseNames),
                policy.syncsOverride(color, name),
            )
            check(
                "sendsCourseIgnores and sends(ASSIGNMENTS)",
                CourseIgnoreSync.syncsCourseIgnores(prefs), policy.sendsCourseIgnores && policy.sends(ASSIGNMENTS),
            )
            check("sendsCourseIgnores", client.patchCourseIgnoreAnswered(KEY, true, null, signIn, madeAtMs = null).sent, policy.sendsCourseIgnores)
            check("uploadsCourses", client.uploadCoursesAnswered(roster, "1151", signIn) != null, policy.uploadsCourses)
            check("sends(COURSES)", sent { client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null) }, policy.sends(COURSES))
            check("sends(COURSES) is the upload's", policy.uploadsCourses, policy.sends(COURSES))
            check("keeps(COURSES)", policy.flavorSyncs, policy.keeps(COURSES))
            rows++
        }
        assertEquals(512, rows)
    }

    @Test
    fun `the policy takes the build's flavor`() {
        prefs.announcementApiBaseUrlOverride = null
        val policy = policies.current
        assertEquals(!BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true), policy.flavorSyncs)
    }

    @Test
    fun `a build without TigerSync keeps, sends and uploads nothing, whatever its switches say`() {
        for (cloudSync in listOf(false, true)) for (on in everySet) for (held in everySet) {
            val policy = SyncPolicy(cloudSync, flavorSyncs = false, switchedOn = on, held = held)
            val row = "cloudSync=$cloudSync on=$on held=$held"
            assertFalse(row, policy.tigerSyncOn)
            assertFalse(row, policy.uploadsCourses)
            assertFalse(row, policy.sendsCourseIgnores)
            for (category in SyncCategory.entries) {
                assertFalse("syncs($category) at $row", policy.syncs(category))
                assertFalse("syncsOverride at $row", policy.syncsOverride(category == COURSE_COLORS, category == COURSE_NAMES))
                assertFalse("keeps($category) at $row", policy.keeps(category))
                assertFalse("keepsHolidays at $row", policy.keepsHolidays)
                assertFalse("sends($category) at $row", policy.sends(category))
                // A full sync never runs here; asked anyway, the answer is the switch's and the hold's.
                assertEquals("applies($category) at $row", category in on && category !in held, policy.applies(category))
            }
        }
    }

    @Test
    fun `every category and holiday is kept whatever its switches say, where the build has TigerSync`() {
        for (cloudSync in listOf(false, true)) for (flavor in listOf(false, true)) for (on in everySet) for (held in everySet) {
            val policy = SyncPolicy(cloudSync, flavor, on, held)
            val row = "cloudSync=$cloudSync flavor=$flavor on=$on held=$held"
            assertEquals("keepsHolidays at $row", flavor, policy.keepsHolidays)
            for (category in SyncCategory.entries) assertEquals("keeps($category) at $row", flavor, policy.keeps(category))
        }
    }

    @Test
    fun `a policy is one reading, and a switch turned after it changes the next one only`() {
        store(master = true, on = SyncCategory.entries.toSet(), held = emptySet())
        val read = policies.current
        prefs.syncAssignments = false
        prefs.cloudSyncEnabled = false
        assertTrue(prefs.school.pendingConflictCategories.set(setOf(COURSE_COLORS.stored), signIn))

        assertEquals(read.flavorSyncs, read.sends(ASSIGNMENTS))
        assertEquals(read.flavorSyncs, read.tigerSyncOn)
        assertTrue(read.applies(COURSE_COLORS))
        val next = policies.current
        assertFalse(next.sends(ASSIGNMENTS))
        assertFalse(next.tigerSyncOn)
        assertFalse(next.applies(COURSE_COLORS))
    }

    private companion object {
        const val BASE = "http://127.0.0.1:9/v4"
        val KEY = CourseRef(courseKey = "client:1151:CS1")
    }
}
