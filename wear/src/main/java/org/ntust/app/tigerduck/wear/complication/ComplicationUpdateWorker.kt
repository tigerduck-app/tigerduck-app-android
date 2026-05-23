package org.ntust.app.tigerduck.wear.complication

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ComplicationUpdateWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        NextClassComplicationService.requestUpdate(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "complication_tick"

        // PeriodicWorkRequest minimum interval is 15 minutes per WorkManager.
        // Watch face systems already call onComplicationRequest on their own
        // cadence, and the DataItem listener triggers immediate updates on
        // schedule changes — this worker is just a backstop for wall-clock
        // transitions (class start/end times).
        fun ensureScheduled(context: Context) {
            val req =
                PeriodicWorkRequestBuilder<ComplicationUpdateWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }
    }
}
