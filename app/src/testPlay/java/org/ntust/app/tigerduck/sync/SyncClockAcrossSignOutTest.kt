package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.ui.FullReset
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The clock's state is the device's: a school sign-out keeps it, and so does a full reset with its
 * wipe, so the next student's first edit is still stamped after every value this device issued or
 * saw (`tigersync-clock`, "Signing out SHALL NOT reset them"). Runs the real sign-out plans over the
 * real stores, as `AccountSwitchTest` does. Play only, as the world it builds is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncClockAcrossSignOutTest {

    private val context = RuntimeEnvironment.getApplication()
    private val signOutJob = SupervisorJob()
    private val signOutScope = CoroutineScope(signOutJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        signOutScope.cancel()
    }

    private fun signedIn() = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE).apply {
        edit().clear().putString("ntust_student_id", "B00015999").putString("ntust_password", "made-up-password").commit()
    }

    private fun clock() = SyncClock(SyncClockStore(context), { BackendApi.V4 }, { T })

    /** The clock's file as a stamp and a header left it: its values, before [leave] and after it. */
    private fun keptAcross(leave: suspend () -> Unit): Pair<Map<String, *>, Map<String, *>> = runBlocking {
        val clock = clock()
        clock.heard((T + 5 * MINUTE) * 1_000)
        clock.stampMs()
        val file = context.getSharedPreferences("tigerduck_sync_clock", Context.MODE_PRIVATE)
        val before = HashMap(file.all)

        leave()
        withTimeout(10_000) { signOutJob.children.toList().joinAll() }

        before to HashMap(file.all)
    }

    @Test
    fun `a school sign-out keeps the clock`() {
        val (before, after) = keptAcross {
            accountWorld(context, signedIn(), signOutScope, idleScope).plans.signOut(SignOutReason.SCHOOL)
        }

        assertEquals(4, before.size)
        assertEquals(before, after)
        // The next sign-in's first edit goes after the last stamp.
        assertEquals(T + 5 * MINUTE + 2, clock().stampMs())
    }

    @Test
    fun `a full reset keeps the clock`() {
        val (before, after) = keptAcross {
            val w = accountWorld(context, signedIn(), signOutScope, idleScope)
            FullReset(
                signOut = { w.plans.signOut(SignOutReason.FULL_RESET) },
                wiped = w.plans::resetWiped,
                clearCaches = { w.dataCache.clearEverything() },
                credentials = w.credentials,
                prefs = w.appPrefs,
                publishLibraryCredentialsToWatch = {},
                clearBrowser = {},
            ).start(signOutScope) {}.join()
        }

        assertEquals(4, before.size)
        assertEquals(before, after)
    }

    private companion object {
        const val T = 1_800_000_000_000L
        const val MINUTE = 60_000L
    }
}
