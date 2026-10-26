package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The first-sync mark upload runs once per account
 * (tigerduck-app/tigerduck-app-android#155). It used to run whenever the
 * server had no marks and this phone had some, so a phone with an older copy
 * sent back a mark the user had just cleared on another device.
 *
 * The decision is [AssignmentOverrideReconciler.isFirstUpload] read with
 * [AppPreferences.assignmentMarksSyncedOnce], as `HomeBackendSync.pull` and
 * `BackgroundSyncWorker` read it.
 */
class FirstSyncMarkUploadTest {

    private val context = JvmContext()

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        prefs = AppPreferences(context)
    }

    private fun uploadsLocalMarks(serverCompleted: Set<String>, localCompleted: Set<String>) =
        AssignmentOverrideReconciler.isFirstUpload(
            syncedBefore = prefs.assignmentMarksSyncedOnce,
            serverIgnored = emptySet(),
            serverCompleted = serverCompleted,
            localIgnored = emptySet(),
            localCompleted = localCompleted,
        )

    /** What a sync that does not upload leaves of this phone's marks. */
    private fun marksAfterSync(serverCompleted: Set<String>, localCompleted: Set<String>) =
        AssignmentOverrideReconciler.reconcile(
            serverIgnored = emptySet(),
            serverCompleted = serverCompleted,
            localIgnored = emptySet(),
            localCompleted = localCompleted,
            inFlightIgnored = emptySet(),
            inFlightCompleted = localCompleted,
            pendingOverrides = emptySet(),
            labelFor = { null },
        ).completed

    @Test
    fun `the account's first sync here sends up the marks the server lacks`() {
        assertTrue(uploadsLocalMarks(serverCompleted = emptySet(), localCompleted = setOf("1")))
    }

    @Test
    fun `a mark cleared on another device stays cleared on a phone that synced it before`() {
        // Phone B's first sync took the mark from phone A.
        assertFalse(uploadsLocalMarks(serverCompleted = setOf("1"), localCompleted = emptySet()))
        prefs.assignmentMarksSyncedOnce = true

        // A clears it, so the server holds no marks; B still has its copy.
        assertFalse(
            "B must not send its copy back up",
            uploadsLocalMarks(serverCompleted = emptySet(), localCompleted = setOf("1")),
        )
        assertEquals(emptySet<String>(), marksAfterSync(serverCompleted = emptySet(), localCompleted = setOf("1")))
    }

    @Test
    fun `the record outlives the process`() {
        prefs.assignmentMarksSyncedOnce = true

        val nextLaunch = AppPreferences(context)

        assertTrue(nextLaunch.assignmentMarksSyncedOnce)
    }

    @Test
    fun `a sign-out forgets it, so the next account's first sync sends its marks up`() {
        prefs.assignmentMarksSyncedOnce = true

        // What AuthService.logout clears.
        prefs.clearSyncStamps()

        assertFalse(prefs.assignmentMarksSyncedOnce)
        assertTrue(uploadsLocalMarks(serverCompleted = emptySet(), localCompleted = setOf("7")))
    }
}
