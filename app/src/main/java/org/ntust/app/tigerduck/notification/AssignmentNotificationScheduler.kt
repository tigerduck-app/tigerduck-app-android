package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AssignmentReminderPrefs
import org.ntust.app.tigerduck.shared.clock.AppClock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssignmentNotificationScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val trackerPrefs =
        context.getSharedPreferences("notification_tracker", Context.MODE_PRIVATE)

    // BootReceiver (goAsync) and BackgroundSyncWorker can both call
    // scheduleAll() concurrently right after boot. Without a lock the two
    // read-cancel-rewrite passes over `scheduled_ids` interleave: the second
    // write overwrites the first, orphaning PendingIntents that are then
    // never cancelled. Same pattern as ClassPreparingNotificationScheduler.
    private val schedulerLock = Any()

    /**
     * Schedule a reminder for each (upcoming assignment × enabled offset).
     *
     * [assignments] are the ones that may still remind the user, which is
     * what [org.ntust.app.tigerduck.data.cache.DataCache.loadOutstandingAssignments]
     * returns: an assignment ignored or marked done gets no reminder at all.
     *
     * Soft cap of 60 pending alarms matches the iOS scheduler so power-users
     * with many courses don't drift toward Android's 500-alarm budget. The
     * budget is distributed across assignments (each gets its closest reminder
     * before any gets a second) so a few early assignments can't crowd out
     * later ones. Far-future reminders that get dropped will be rescheduled on
     * the next sync once the nearest ones fire.
     *
     * [stillCurrent] is asked under the lock, before anything is cancelled:
     * false leaves every reminder as it is.
     */
    fun scheduleAll(
        assignments: List<Assignment>,
        offsets: Set<AssignmentReminderOffset> = AssignmentReminderOffset.DEFAULTS,
        stillCurrent: () -> Boolean = { true },
    ): Unit = synchronized(schedulerLock) {
        if (!stillCurrent()) return
        cancelAllTracked()
        if (offsets.isEmpty()) return

        val now = AppClock.nowMillis()
        val pending = assignments.flatMap { pendingFor(it, offsets, now) }

        // Soft cap, distributed across assignments. Selecting globally by
        // fire time lets a few early assignments expand into all their
        // offsets and consume the whole budget, starving later assignments
        // (10 assignments × 6 default offsets = 60 slots, so the 11th gets
        // nothing even if it's due soon). Instead, hand out reminders in
        // rounds: every assignment gets its closest-to-deadline reminder
        // before any assignment gets a second one. triggerTime is
        // dueDate - offset, so the closest-to-deadline reminder has the
        // latest triggerTime; sort each assignment's reminders descending so
        // round 0 keeps it. Assignments are then ordered by that closest
        // reminder's fire time so the due-soonest wins the last slot when the
        // budget runs out mid-round.
        val byAssignment = pending
            .groupBy { it.assignmentId }
            .values
            .map { it.sortedByDescending { p -> p.triggerTime } }
            .sortedBy { it.first().triggerTime }
        val capped = mutableListOf<Pending>()
        var round = 0
        outer@ while (capped.size < MAX_PENDING) {
            var addedThisRound = false
            for (reminders in byAssignment) {
                if (round >= reminders.size) continue
                capped.add(reminders[round])
                addedThisRound = true
                if (capped.size == MAX_PENDING) break@outer
            }
            if (!addedThisRound) break
            round++
        }
        val scheduledKeys = mutableSetOf<String>()

        for (item in capped) scheduledKeys.add(arm(item))

        trackerPrefs.edit().putStringSet("scheduled_ids", scheduledKeys).apply()
    }

    /** One reminder to arm: an offset of an assignment's due date, the student's own. */
    private data class Pending(
        val assignmentId: String,
        val offset: AssignmentReminderOffset,
        val triggerTime: Long,
        val dueAt: Long,
        val title: String,
        val courseName: String,
    )

    /** [assignment]'s reminders for [offsets] still ahead of [now]; none once it is handed in. */
    private fun pendingFor(assignment: Assignment, offsets: Set<AssignmentReminderOffset>, now: Long): List<Pending> {
        if (assignment.isCompleted) return emptyList()
        return offsets
            .map { it to assignment.studentDueDate.time - it.milliseconds }
            .filter { it.second > now }
            .map { (offset, triggerTime) ->
                Pending(
                    assignmentId = assignment.assignmentId,
                    offset = offset,
                    triggerTime = triggerTime,
                    dueAt = assignment.studentDueDate.time,
                    title = assignment.title,
                    courseName = assignment.courseName,
                )
            }
    }

    /** Arms [item]'s alarm; its tracker key. Under [schedulerLock]. */
    private fun arm(item: Pending): String {
        val key = trackerKey(item.assignmentId, item.offset)

        val intent = Intent(context, AssignmentNotificationReceiver::class.java).apply {
            putExtra(AssignmentNotificationReceiver.EXTRA_TITLE, item.title)
            putExtra(AssignmentNotificationReceiver.EXTRA_COURSE_NAME, item.courseName)
            putExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID, item.assignmentId)
            putExtra(AssignmentNotificationReceiver.EXTRA_OFFSET, item.offset.rawValue)
            putExtra(AssignmentNotificationReceiver.EXTRA_DUE_AT, item.dueAt)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Without exact alarms, which Android 14 grants no app by default,
        // the reminder is still allowed through Doze: a plain set() would
        // wait for Doze's next maintenance window, however late that is.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(item.triggerTime),
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(item.triggerTime),
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                AppClock.realTimeFor(item.triggerTime),
                pendingIntent
            )
        }
        return key
    }

    /**
     * Rebuilds every reminder from the assignment cache when reminders are
     * on, for the outstanding assignments only: what Home and the worker do
     * after they write that cache, for the screens that write it without
     * them. An empty cache cancels them all, since an answer from Moodle with
     * no assignments takes the reminders of the ones it dropped with it, such
     * as those of a course the user TAs (issue #160).
     *
     * With [generation], the cache's when the caller's work began, nothing is
     * armed once the data has been wiped since: what it read may be the
     * departing account's, and the next account's reminders stay as they are.
     */
    suspend fun rescheduleFromCache(dataCache: DataCache, prefs: AssignmentReminderPrefs, generation: Int? = null) {
        if (!prefs.notifyAssignments) return
        scheduleAll(dataCache.loadOutstandingAssignments(), prefs.notifyAssignmentOffsets) {
            generation == null || generation == dataCache.userDataGeneration
        }
    }

    /**
     * Arms [assignmentId]'s reminders again from the cache, and leaves every other assignment's
     * as they are. For a reminder check that learned one assignment's news: rebuilding them all
     * arms only times still ahead, so it would cancel another assignment's reminder that is due
     * but not delivered yet, as Doze delivers a batch of them one by one. With [generation],
     * nothing once the data has been wiped since; see [rescheduleFromCache].
     */
    suspend fun rescheduleOne(
        assignmentId: String,
        dataCache: DataCache,
        prefs: AssignmentReminderPrefs,
        generation: Int? = null,
    ) {
        val assignment = dataCache.loadOutstandingAssignments().firstOrNull { it.assignmentId == assignmentId }
        val offsets = prefs.notifyAssignmentOffsets
        synchronized(schedulerLock) {
            if (generation != null && generation != dataCache.userDataGeneration) return
            cancel(assignmentId)
            if (!prefs.notifyAssignments || assignment == null) return
            val armed = pendingFor(assignment, offsets, AppClock.nowMillis()).map(::arm)
            if (armed.isNotEmpty()) trackerPrefs.edit().putStringSet("scheduled_ids", trackedKeys() + armed).apply()
        }
    }

    fun cancelAllTracked(): Unit = synchronized(schedulerLock) {
        // Legacy entries written before per-offset scheduling are bare
        // assignment IDs (no `::`). Hashing them recovers the original
        // request code, so a single cancel pass clears both new composite
        // keys and pre-upgrade single-token entries.
        for (key in trackedKeys()) cancelAlarm(key)
        trackerPrefs.edit().remove("scheduled_ids").apply()
    }

    /**
     * Cancels the reminders still armed for [assignmentId], legacy ones
     * included, and leaves every other assignment's.
     */
    fun cancel(assignmentId: String): Unit = synchronized(schedulerLock) {
        val keys = trackedKeys()
        val ours = keys.filter { it == assignmentId || it.startsWith("$assignmentId::") }.toSet()
        if (ours.isEmpty()) return
        for (key in ours) cancelAlarm(key)
        trackerPrefs.edit().putStringSet("scheduled_ids", keys - ours).apply()
    }

    // A copy: SharedPreferences docs forbid mutating the set it returns, and
    // concurrent BackgroundSyncWorker / BootReceiver callers iterating the
    // same backing instance risk ConcurrentModificationException.
    private fun trackedKeys(): Set<String> =
        (trackerPrefs.getStringSet("scheduled_ids", emptySet()) ?: emptySet()).toHashSet()

    private fun cancelAlarm(key: String) {
        val intent = Intent(context, AssignmentNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }

    private fun trackerKey(assignmentId: String, offset: AssignmentReminderOffset): String =
        "$assignmentId::${offset.rawValue}"

    companion object {
        /**
         * Soft cap on concurrently-pending reminders — matches the iOS
         * scheduler. Leaves headroom under Android's per-app alarm budget so
         * a power user with many courses doesn't crowd out other components.
         */
        const val MAX_PENDING = 60
    }
}
