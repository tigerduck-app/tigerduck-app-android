package org.ntust.app.tigerduck.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * What a sync keeps per account goes at sign-out ([AppPreferences.clearSyncStamps]),
 * and the upload ledger also when a sync switch comes back on
 * ([AppPreferences.forgetUploads]).
 */
class SyncStampsTest {

    private val context = JvmContext()

    private val prefs = AppPreferences(context)

    private fun keep() {
        prefs.school.lastSeenCoursesResetAt.set("2026-10-10T16:42:49+00:00", testSignIn())
        prefs.school.assignmentsUploadFingerprint.set("abc", testSignIn())
        prefs.school.assignmentsUploadedAtMs.set(42L, testSignIn())
        prefs.recordCoursesUpload("1151", "c1", 7L)
        prefs.recordCoursesUpload("1142", "c2", 8L)
    }

    private fun assertCourseListsForgotten() {
        for (semester in listOf("1151", "1142")) {
            assertNull(prefs.coursesUploadFingerprint(semester))
            assertEquals(0L, prefs.coursesUploadedAtMs(semester))
        }
    }

    @Test
    fun `sign-out forgets the course reset read and the upload ledger`() {
        keep()

        prefs.clearSyncStamps()

        assertNull(prefs.school.lastSeenCoursesResetAt.get())
        assertNull(prefs.school.assignmentsUploadFingerprint.get())
        assertEquals(0L, prefs.school.assignmentsUploadedAtMs.get())
        assertCourseListsForgotten()
    }

    @Test
    fun `a switch coming back on forgets the upload ledger alone`() {
        keep()

        prefs.forgetUploads()

        assertNull(prefs.school.assignmentsUploadFingerprint.get())
        assertEquals(0L, prefs.school.assignmentsUploadedAtMs.get())
        assertCourseListsForgotten()
        assertEquals("2026-10-10T16:42:49+00:00", prefs.school.lastSeenCoursesResetAt.get())
    }

    @Test
    fun `a term reset forgets that term's course list alone`() {
        keep()

        prefs.forgetCourseUploads("1151")

        assertNull(prefs.coursesUploadFingerprint("1151"))
        assertEquals(0L, prefs.coursesUploadedAtMs("1151"))
        assertEquals("c2", prefs.coursesUploadFingerprint("1142"))
        assertEquals(8L, prefs.coursesUploadedAtMs("1142"))
        assertEquals("abc", prefs.school.assignmentsUploadFingerprint.get())
    }

    @Test
    fun `the older build's sync stamp is read once, and sign-out forgets it too`() {
        val legacy = context.getSharedPreferences("tigerduck_sync", 0)
        legacy.edit().putLong("last_course_sync_at", 42L).commit()

        assertEquals(42L, prefs.legacyLastCourseSyncAtMs)
        prefs.forgetLegacyLastCourseSyncAt()
        assertEquals(0L, prefs.legacyLastCourseSyncAtMs)

        legacy.edit().putLong("last_course_sync_at", 42L).commit()
        prefs.clearSyncStamps()
        assertEquals(0L, prefs.legacyLastCourseSyncAtMs)
    }

    @Test
    fun `any sync switch turned back on forgets the upload ledger, wherever it is turned`() {
        // Onboarding's TigerSync step sets the switches directly, not through
        // the Settings screens.
        for (flip in listOf<(Boolean) -> Unit>(
            { prefs.cloudSyncEnabled = it },
            { prefs.syncCourses = it },
            { prefs.syncCourseColors = it },
            { prefs.syncCourseNames = it },
            { prefs.syncAssignments = it },
        )) {
            flip(false)
            keep()
            flip(true)
            assertNull(prefs.school.assignmentsUploadFingerprint.get())
            assertNull(prefs.coursesUploadFingerprint("1151"))
        }
    }

    @Test
    fun `a switch set on again while on keeps the ledger`() {
        prefs.syncAssignments = true
        keep()

        prefs.syncAssignments = true

        assertEquals("abc", prefs.school.assignmentsUploadFingerprint.get())
        assertEquals("c1", prefs.coursesUploadFingerprint("1151"))
    }
}
