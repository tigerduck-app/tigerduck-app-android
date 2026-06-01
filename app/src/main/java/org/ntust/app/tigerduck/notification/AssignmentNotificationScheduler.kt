package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.model.Assignment
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

    /**
     * Schedule a reminder for each (upcoming assignment × enabled offset).
     *
     * Assignments whose id appears in [safetyNetIds] are still scheduled but
     * with `EXTRA_KIND = KIND_SAFETY_NET`, so the receiver posts a different
     * body warning the user that they ignored / marked-done a homework they
     * haven't actually submitted. Safety-net reminders only fire once per
     * assignment — at the closest enabled offset before the deadline — so a
     * user with many enabled offsets doesn't get spammed with five "you
     * still haven't submitted" alerts for the same dismissed item.
     *
     * Soft cap of 60 pending alarms matches the iOS scheduler so power-users
     * with many courses don't drift toward Android's 500-alarm budget. The
     * budget is distributed across assignments (each gets its closest reminder
     * before any gets a second) so a few early assignments can't crowd out
     * later ones. Far-future reminders that get dropped will be rescheduled on
     * the next sync once the nearest ones fire.
     */
    fun scheduleAll(
        assignments: List<Assignment>,
        safetyNetIds: Set<String> = emptySet(),
        offsets: Set<AssignmentReminderOffset> = AssignmentReminderOffset.DEFAULTS,
    ) {
        cancelAllTracked()
        if (offsets.isEmpty()) return

        val now = AppClock.nowMillis()
        data class Pending(
            val assignmentId: String,
            val offset: AssignmentReminderOffset,
            val triggerTime: Long,
            val title: String,
            val courseName: String,
            val isSafetyNet: Boolean,
        )

        val pending = mutableListOf<Pending>()
        for (assignment in assignments) {
            if (assignment.isCompleted) continue
            val isSafetyNet = assignment.assignmentId in safetyNetIds
            val applicable = if (isSafetyNet) {
                // Surface a safety-net only at the closest-to-deadline still-
                // future offset (largest triggerTime), not all of them — see
                // KDoc rationale. `maxByOrNull` picks the latest fire time =
                // smallest remaining gap to the deadline.
                offsets
                    .map { it to assignment.dueDate.time - it.milliseconds }
                    .filter { it.second > now }
                    .maxByOrNull { it.second }
                    ?.let { listOf(it) }
                    ?: emptyList()
            } else {
                offsets
                    .map { it to assignment.dueDate.time - it.milliseconds }
                    .filter { it.second > now }
            }
            for ((offset, triggerTime) in applicable) {
                pending.add(
                    Pending(
                        assignmentId = assignment.assignmentId,
                        offset = offset,
                        triggerTime = triggerTime,
                        title = assignment.title,
                        courseName = assignment.courseName,
                        isSafetyNet = isSafetyNet,
                    )
                )
            }
        }

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

        for (item in capped) {
            val key = trackerKey(item.assignmentId, item.offset)
            val kind = if (item.isSafetyNet) {
                AssignmentNotificationReceiver.KIND_SAFETY_NET
            } else {
                AssignmentNotificationReceiver.KIND_REGULAR
            }

            val intent = Intent(context, AssignmentNotificationReceiver::class.java).apply {
                putExtra(AssignmentNotificationReceiver.EXTRA_TITLE, item.title)
                putExtra(AssignmentNotificationReceiver.EXTRA_COURSE_NAME, item.courseName)
                putExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID, item.assignmentId)
                putExtra(AssignmentNotificationReceiver.EXTRA_KIND, kind)
                putExtra(AssignmentNotificationReceiver.EXTRA_OFFSET, item.offset.rawValue)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                key.hashCode() and 0x7FFFFFFF,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(
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
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(item.triggerTime),
                    pendingIntent
                )
            }
            scheduledKeys.add(key)
        }

        trackerPrefs.edit().putStringSet("scheduled_ids", scheduledKeys).apply()
    }

    fun cancelAllTracked() {
        // Copy the returned set: SharedPreferences docs forbid mutating it,
        // and concurrent BackgroundSyncWorker / BootReceiver callers iterating
        // the same backing instance risk ConcurrentModificationException.
        //
        // Legacy entries written before per-offset scheduling are bare
        // assignment IDs (no `::`). Hashing them recovers the original
        // request code, so a single cancel pass clears both new composite
        // keys and pre-upgrade single-token entries.
        val keys = (trackerPrefs.getStringSet("scheduled_ids", emptySet()) ?: emptySet()).toHashSet()
        for (key in keys) {
            val intent = Intent(context, AssignmentNotificationReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                key.hashCode() and 0x7FFFFFFF,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        trackerPrefs.edit().remove("scheduled_ids").apply()
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
