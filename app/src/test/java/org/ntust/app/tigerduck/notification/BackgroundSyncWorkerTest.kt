package org.ntust.app.tigerduck.notification

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a trigger during a running sync queues one follow-up behind it`() {
        // The running sync may have fetched before the change this trigger
        // announces; dropped, that change waited for the hourly run.
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            BackgroundSyncWorker.triggerPolicy(listOf(WorkInfo.State.RUNNING)),
        )
        // The follow-up, waiting behind it, covers every later trigger.
        assertNull(
            BackgroundSyncWorker.triggerPolicy(
                listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
            ),
        )
        assertNull(BackgroundSyncWorker.triggerPolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(
            ExistingWorkPolicy.KEEP,
            BackgroundSyncWorker.triggerPolicy(listOf(WorkInfo.State.SUCCEEDED)),
        )
    }

    @Test
    fun `a triggered sync waits for a network connection`() {
        BackgroundSyncWorker.requestSync(context)

        assertEquals(NetworkType.CONNECTED, syncs().single().constraints.requiredNetworkType)
    }

    @Test
    fun `a failed triggered sync retries a few times, then gives up`() {
        // Not retried at all, a trigger that met a briefly unreachable school
        // server left its change missing until the next trigger or the
        // hourly run.
        for (attempt in 0..2) {
            assertEquals(
                "attempt $attempt",
                ListenableWorker.Result.retry(),
                BackgroundSyncWorker.resultForFailedSync(triggered = true, runAttemptCount = attempt),
            )
        }
        // Retried without end, its backoff grew to hours with every trigger
        // in the meantime dropped behind it.
        assertEquals(
            ListenableWorker.Result.success(),
            BackgroundSyncWorker.resultForFailedSync(triggered = true, runAttemptCount = 3),
        )
    }

    @Test
    fun `a failed periodic sync keeps retrying`() {
        for (attempt in listOf(0, 3, 10)) {
            assertEquals(
                "attempt $attempt",
                ListenableWorker.Result.retry(),
                BackgroundSyncWorker.resultForFailedSync(triggered = false, runAttemptCount = attempt),
            )
        }
    }
}
