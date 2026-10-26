package org.ntust.app.tigerduck.mail.index

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Starts and stops the background download of every mail into the search index. */
interface MailIndexScheduler {
    /** Brings the index up to the server soon, on any network; a run already queued or going stays. */
    fun request()

    /**
     * A run that plans after now: for mail found missing that a run under way may have planned
     * without, or be past its last look for. One already waiting to start is that run; otherwise
     * a run of its own after the one going. Blocks on WorkManager's answer, so not for the main
     * thread.
     */
    fun requestAgain()

    /** Stops it, for a sign-out. */
    fun cancel()
}

class WorkManagerMailIndexScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MailIndexScheduler {
    override fun request() {
        // KEEP: a run going already fetches whatever the server has.
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, run())
    }

    // At most one run waiting: it plans once it starts, after whatever was found missing before.
    // Asked and queued in one step, so two searches at once queue one. A run queued here goes after
    // the one going, or in its place should that one have failed or been cancelled, and finds the
    // index due ([SchoolMailRepository.updateSearchIndex]) or ends at once when the one before
    // got it all.
    @Synchronized
    override fun requestAgain() {
        val workManager = WorkManager.getInstance(context)
        val waiting = workManager.getWorkInfosForUniqueWork(NAME).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (!waiting) workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, run())
    }

    private fun run() = OneTimeWorkRequestBuilder<MailIndexWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    private companion object {
        const val NAME = "school_mail_index"
    }
}

/** Downloads every mail the search index lacks, see [SchoolMailRepository.updateSearchIndex]. */
@HiltWorker
class MailIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SchoolMailRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        repository.updateSearchIndex { !isStopped }
        Result.success()
    } catch (e: MailError) {
        when (e) {
            // Tried again later: the network or the server's connection limit may let it through.
            is MailError.Network, is MailError.ServerBusy -> Result.retry()
            // A rejected password, a sign-out or the demo: nothing to fetch until the next request.
            else -> Result.success()
        }
    }
}
