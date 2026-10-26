package org.ntust.app.tigerduck.mail.index

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What [WorkManagerMailIndexScheduler] queues. A run starts only when a test gives it its network,
 * and then holds RUNNING until the test lets it end; every other run stays where the policy put it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class MailIndexSchedulerTest {
    private lateinit var context: Application
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerMailIndexScheduler

    // Runs on a thread of their own, so one can stay RUNNING while the test asks for more.
    private val runThread = Executors.newSingleThreadExecutor()
    private val runStarted = CountDownLatch(1)
    private val finishRun = CountDownLatch(1)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(runThread).setWorkerFactory(HeldRunFactory()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerMailIndexScheduler(context)
    }

    @After
    fun tearDown() {
        finishRun.countDown()
        runThread.shutdown()
    }

    private fun runs(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("school_mail_index").get()

    /** Gives the queued run its network; it starts before this returns, and holds RUNNING. */
    private fun startTheRun() {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(runs().single().id)
        assertTrue("the run never started", runStarted.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun `a request while a run is queued joins it`() {
        scheduler.request()
        scheduler.request()

        assertEquals(1, runs().size)
    }

    @Test
    fun `a follow-up while a run is only queued is that run, which plans once it starts`() {
        scheduler.request()

        scheduler.requestAgain()

        assertEquals(1, runs().size)
    }

    @Test
    fun `follow-ups while a run is under way queue one run after it, and only one`() {
        scheduler.request()
        startTheRun()
        val first = runs().single().id

        repeat(3) { scheduler.requestAgain() }

        val all = runs()
        assertEquals(2, all.size)
        assertEquals(WorkInfo.State.RUNNING, all.single { it.id == first }.state)
        assertEquals("waiting for the first to end", WorkInfo.State.BLOCKED, all.single { it.id != first }.state)
    }

    /** Stands in for MailIndexWorker, whose real run needs the app's whole Hilt graph. */
    private inner class HeldRunFactory : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            if (workerClassName == MailIndexWorker::class.java.name) HeldRun(appContext, workerParameters) else null
    }

    private inner class HeldRun(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
        override fun doWork(): Result {
            runStarted.countDown()
            finishRun.await(30, TimeUnit.SECONDS)
            return Result.success()
        }
    }
}
