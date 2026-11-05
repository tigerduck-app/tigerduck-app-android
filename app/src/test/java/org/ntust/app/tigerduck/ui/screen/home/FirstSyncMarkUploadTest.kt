package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The record that the account's marks have synced on this phone
 * ([AppPreferences.assignmentMarksSyncedOnce]), which a pull reads as the marks' `syncedBefore`
 * (LWW D13): once set, no first sync queues this phone's marks as the oldest again
 * (tigerduck-app/tigerduck-app-android#155). It outlives the process and goes at sign-out.
 */
class FirstSyncMarkUploadTest {

    private val context = JvmContext()

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        prefs = AppPreferences(context)
    }

    @Test
    fun `the record outlives the process`() {
        prefs.school.assignmentMarksSyncedOnce.set(true, testSignIn())

        val nextLaunch = AppPreferences(context)

        assertTrue(nextLaunch.school.assignmentMarksSyncedOnce.get())
    }

    @Test
    fun `a sign-out forgets it, so the next account's first sync queues its marks`() {
        prefs.school.assignmentMarksSyncedOnce.set(true, testSignIn())

        // What AuthService.logout clears.
        prefs.clearSyncStamps()

        assertFalse(prefs.school.assignmentMarksSyncedOnce.get())
    }
}
