package org.ntust.app.tigerduck.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sends the device unregister a sign-out owes ([DepartingDeviceUnregister]) once there is a
 * network, and again with a growing wait until the server has it, across restarts of the app.
 */
@HiltWorker
class DepartingUnregisterWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val unregister: DepartingDeviceUnregister,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) {
            unregister.abandon()
            return Result.success()
        }
        return when (unregister.send()) {
            DepartingDeviceUnregister.Outcome.DONE -> Result.success()
            DepartingDeviceUnregister.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        internal const val UNIQUE_NAME = "departing_device_unregister"

        /**
         * With the backoff capped at five hours by WorkManager, past the refresh token's 90 days,
         * after which [DepartingDeviceUnregister] drops the debt itself: this is for a debt that
         * cannot even be read.
         */
        internal const val MAX_ATTEMPTS = 500

        /** Queued at every sign-out that owes one; a run finding nothing owed ends at once. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DepartingUnregisterWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // Replaced, so a run backing off for an earlier debt starts again for this one.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
