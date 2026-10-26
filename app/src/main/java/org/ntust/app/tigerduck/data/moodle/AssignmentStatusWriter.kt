package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.PushApiClient
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a status an assignment's page fetched into the cached assignments, which Home, the Live
 * Update and the reminders read, so they show it at once rather than after the next round. An
 * answer asked for before the one the assignment has, a round's or another page's, is dropped,
 * and nothing is written once the account signed out. Ported from iOS
 * `AppServiceBridge.takeSubmissionStatus`.
 */
@Singleton
class AssignmentStatusWriter internal constructor(
    private val ledger: AssignmentStatusLedger,
    private val dataCache: DataCache,
    /** Told the list as written, when the write changed it: Home, the Live Update, the reminders and their upload. */
    private val published: suspend (List<Assignment>) -> Unit,
) {
    @Inject
    constructor(
        dataCache: DataCache,
        moodleService: MoodleService,
        pushApiClient: PushApiClient,
        notificationScheduler: AssignmentNotificationScheduler,
        liveActivityManager: LiveActivityManager,
        prefs: AppPreferences,
    ) : this(
        ledger = moodleService.statusLedger,
        dataCache = dataCache,
        published = { assignments ->
            // As the calendar's and the class table's rounds publish theirs: the reminders and
            // the Live Update follow the cache, and Home reads it again. The upload is what the
            // backend builds the reminders of other devices from, and goes only with TigerSync on.
            notificationScheduler.rescheduleFromCache(dataCache, prefs)
            liveActivityManager.refresh()
            dataCache.notifyBackgroundSyncComplete()
            runCatching { pushApiClient.uploadAssignments(assignments) }
        },
    )

    /**
     * Takes [answer] for the cached assignment it names, when it is newer than the one the
     * assignment holds and the data was not wiped since the generation the page's request went
     * out under. A status the cache already has writes and publishes nothing. Whether it wrote.
     */
    suspend fun take(answer: SubmissionStatusAnswer): Boolean {
        val written = write(answer) as? StatusWrite.Written ?: return false
        published(written.assignments)
        return true
    }

    /** What [write] made of an answer. */
    sealed interface StatusWrite {
        /** Taken, and the list as written with it. */
        data class Written(val assignments: List<Assignment>) : StatusWrite

        /** Taken, with nothing to change: the cache had it, or has no such assignment. */
        data object Unchanged : StatusWrite

        /** Not taken: one asked for later is, or the account it was asked for signed out. */
        data object Refused : StatusWrite
    }

    /**
     * What [take] writes, told to no one. For a caller that tells only some of those [take]
     * tells, itself.
     */
    suspend fun write(answer: SubmissionStatusAnswer): StatusWrite {
        // Checked before the ledger takes the answer: an answer for the account that left must
        // not stand in the way of the next account's round for the same id.
        if (answer.generation != dataCache.userDataGeneration) return StatusWrite.Refused
        var refused = false
        var changed = false
        val written = dataCache.updateAssignments(answer.generation) { assignments ->
            // Offered under the cache's lock, which a round's write holds too, so a round that
            // writes between the offer and this write cannot be overwritten by an older answer.
            if (!ledger.offer(answer)) {
                refused = true
                return@updateAssignments assignments
            }
            val index = assignments.indexOfFirst { it.assignmentId == answer.assignmentId }
            if (index < 0) return@updateAssignments assignments
            val current = assignments[index]
            val submittedAt = answer.submittedAtMs?.let(::Date)
            val extension = answer.extensionDueMs?.let(::Date)
            if (current.isCompleted == answer.submitted && current.submittedAt == submittedAt &&
                current.extensionDueDate == extension
            ) return@updateAssignments assignments
            changed = true
            assignments.toMutableList().apply {
                this[index] = current.copy(isCompleted = answer.submitted, submittedAt = submittedAt, extensionDueDate = extension)
            }
        }
        return when {
            written == null || refused -> StatusWrite.Refused
            changed -> StatusWrite.Written(written)
            else -> StatusWrite.Unchanged
        }
    }
}
