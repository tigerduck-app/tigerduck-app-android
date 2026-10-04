package org.ntust.app.tigerduck.notification

import android.app.Application
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a server sync trigger queues. FCM can hand the app several in a
 * burst, and every BackgroundSyncWorker run syncs the backend and Moodle in
 * full, so a trigger asks for a sync rather than adding one of its own.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class BackgroundSyncWorkerTest {

    private lateinit var context: Application
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    // Every request built for a worker carries the worker's class name as a
    // tag, so this counts syncs however they were queued.
    private fun syncs(): List<WorkInfo> =
        workManager.getWorkInfosByTag(BackgroundSyncWorker::class.java.name).get()

    @Test
    fun `a burst of sync triggers queues one sync`() {
        repeat(5) { BackgroundSyncWorker.requestSync(context) }

        assertEquals(1, syncs().size)
    }

    @Test
    fun `a triggered sync waits for a network connection`() {
        BackgroundSyncWorker.requestSync(context)

        assertEquals(NetworkType.CONNECTED, syncs().single().constraints.requiredNetworkType)
    }
}
