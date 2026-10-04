package org.ntust.app.tigerduck.notification

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    // Syncs run on a thread of their own, so one can stay RUNNING while the
    // test sends it triggers.
    private val syncThread = Executors.newSingleThreadExecutor()
    private val syncStarted = CountDownLatch(1)
    private val finishSync = CountDownLatch(1)
    @Volatile private var heldSync: HeldSync? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(syncThread)
                .setWorkerFactory(HeldSyncFactory())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        finishSync.countDown()
        syncThread.shutdown()
    }

    // Every request built for a worker carries the worker's class name as a
    // tag, so this counts syncs however they were queued.
    private fun syncs(): List<WorkInfo> =
        workManager.getWorkInfosByTag(BackgroundSyncWorker::class.java.name).get()

    private fun state(id: UUID): WorkInfo.State? = workManager.getWorkInfoById(id).get()?.state

    // Gives the sync waiting on its network a connection. The sync starts
    // before this returns, and holds RUNNING until finishSync is counted down.
    private fun startSync(id: UUID) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        assertTrue("the sync never started", syncStarted.await(5, TimeUnit.SECONDS))
    }

    // Lets the held sync finish, and waits until WorkManager has filed its
    // result: that runs on the sync's thread, straight after doWork returns.
    private fun finishHeldSync() {
        finishSync.countDown()
        syncThread.shutdown()
        assertTrue("the sync never finished", syncThread.awaitTermination(5, TimeUnit.SECONDS))
    }

    /**
     * Stands in for BackgroundSyncWorker, whose real run needs the app's
     * whole Hilt graph and the network.
     */
    private inner class HeldSyncFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == BackgroundSyncWorker::class.java.name) {
                HeldSync(appContext, workerParameters).also { heldSync = it }
            } else {
                null
            }
    }

    private inner class HeldSync(appContext: Context, params: WorkerParameters) :
        Worker(appContext, params) {
        override fun doWork(): Result {
            syncStarted.countDown()
            finishSync.await(5, TimeUnit.SECONDS)
            return Result.success()
        }
    }

    /**
     * A WorkManager task executor that has stopped: it holds every task it
     * is given until [resume], as a busy executor or a slow database would.
     */
    private class StalledExecutor : Executor {
        private val held = ArrayDeque<Runnable>()
        private var resumed = false

        override fun execute(command: Runnable) {
            synchronized(this) {
                if (!resumed) {
                    held.addLast(command)
                    return
                }
            }
            command.run()
        }

        fun resume() {
            while (true) {
                val next = synchronized(this) {
                    held.removeFirstOrNull() ?: run { resumed = true; null }
                } ?: return
                next.run()
            }
        }
    }

    @Test
    fun `a burst of sync triggers queues one sync`() {
        repeat(5) { BackgroundSyncWorker.requestSync(context) }

        assertEquals(1, syncs().size)
    }

    @Test
    fun `triggers during a running sync queue one follow-up behind it`() {
        BackgroundSyncWorker.requestSync(context)
        val running = syncs().single().id
        startSync(running)
        assertEquals(WorkInfo.State.RUNNING, state(running))

        repeat(5) { BackgroundSyncWorker.requestSync(context) }

        // One follow-up, waiting on the running sync; the triggers after the
        // first joined it.
        val followUp = syncs().map { it.id }.minus(running).single()
        assertEquals(WorkInfo.State.BLOCKED, state(followUp))

        finishHeldSync()

        assertEquals(WorkInfo.State.SUCCEEDED, state(running))
        // Next in line, waiting on its network like any triggered sync.
        assertEquals(WorkInfo.State.ENQUEUED, state(followUp))
    }

    @Test
    fun `signing out stops a running triggered sync and its follow-up`() {
        BackgroundSyncWorker.requestSync(context)
        val running = syncs().single().id
        startSync(running)
        BackgroundSyncWorker.requestSync(context)
        val followUp = syncs().map { it.id }.minus(running).single()

        BackgroundSyncWorker.cancel(context)

        // Left running, it went on writing the departing account's courses
        // and assignments over the cache logout was clearing.
        assertEquals(WorkInfo.State.CANCELLED, state(running))
        assertTrue("the running sync was not told to stop", heldSync!!.isStopped)
        assertEquals(WorkInfo.State.CANCELLED, state(followUp))
    }

    @Test
    fun `a trigger does not wait on a stalled queue`() {
        val stalled = StalledExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setTaskExecutor(stalled).build(),
        )
        workManager = WorkManager.getInstance(context)
        val fcmThread = Executors.newSingleThreadExecutor()
        try {
            // FCM hands over one message at a time, so a trigger stuck here
            // held up every push behind it.
            fcmThread.submit { BackgroundSyncWorker.requestSync(context) }
                .get(10, TimeUnit.SECONDS)
        } finally {
            stalled.resume()
            fcmThread.shutdown()
        }

        // Its sync was queued all the same, once WorkManager caught up.
        assertEquals(1, syncs().size)
    }

    @Test
    fun `a trigger adds a sync only when none is waiting to start`() {
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
