package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules "即將上課" notifications for upcoming class slots — analogous to
 * [AssignmentNotificationScheduler], but for class sessions. Fires an exact
 * alarm at `(classStart - classPreparingLeadTimeSec)` so the notification
 * arrives even while the app is fully closed.
 *
 * The classes are the timetable's as the school's [courseChanges] leave them,
 * as iOS's `ScheduleSyncService` sends them to the server: none for a
 * cancelled class, and the rest at their changed start in their changed room.
 * LiveActivityManager arms them again when the feed changes, which brings the
 * reminders already up in step too; see [refreshShownLocked].
 *
 * [appLanguage] is the in-app language such a reminder is written in again.
 */
@Singleton
class ClassPreparingNotificationScheduler internal constructor(
    private val context: Context,
    private val appLanguage: () -> String = { AppLanguageManager.SYSTEM },
    private val storedStudentId: () -> String? = { null },
    private val courseChanges: () -> CourseChanges = { CourseChanges.EMPTY },
) : ClassReminderAlarms {
    @Inject constructor(
        @ApplicationContext context: Context,
        appPreferences: AppPreferences,
        courseChangeStore: CourseChangeStore,
        credentials: CredentialManager,
    ) : this(context, { appPreferences.appLanguage }, { credentials.ntustStudentId }, courseChangeStore::current)

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val trackerPrefs = context.getSharedPreferences(TRACKER_PREFS, Context.MODE_PRIVATE)

    // Serializes scheduleAll / cancelAllTracked across boot, live-activity
    // refresh, and BackgroundSyncWorker. Without this, concurrent callers
    // would each load their own codeMap copy, mint duplicate request codes
    // off KEY_NEXT_CODE (read-inc-apply is async + non-atomic), and clobber
    // each other's persisted state.
    private val schedulerLock = Any()

    /**
     * @param coursesByTerm each term's courses, by code: a day's reminders
     *   come from the term [SemesterCodes.termOn] puts it in, and a day whose term is
     *   missing here gets none.
     * @param calendar the school calendar, used to skip days classes do not
     *   meet; [AcademicCalendar.EMPTY] suppresses nothing.
     * @param optedInHolidayIds holidays this user asked to keep hearing
     *   about, which put their days back in.
     */
    override fun scheduleAll(
        coursesByTerm: Map<String, List<Course>>,
        skippedDates: Map<String, List<String>>,
        leadTimeSec: Long,
        calendar: AcademicCalendar,
        optedInHolidayIds: Set<Int>,
        startedFor: String?,
    ): Long? = synchronized(schedulerLock) {
        // Load the persisted slotId→requestCode map BEFORE cancelling so cancel
        // looks up the same code each alarm was originally registered with.
        // Previously we hashed slotId into the requestCode, which could collide
        // across two different slots and silently overwrite the earlier alarm.
        // Before the cancel: if the account cannot be read, the reminders already armed stay.
        val armedFor = runCatching { storedStudentId() }.getOrElse { return@synchronized null }
        val codeMap = loadCodeMap()
        cancelAllTrackedLocked(codeMap)
        // The courses are from the student the refresh began under; if another account is stored
        // now, they would be armed for someone else, or tagged with the wrong student.
        if (armedFor != startedFor) return@synchronized null
        if (leadTimeSec <= 0) return@synchronized null
        if (coursesByTerm.values.all { it.isEmpty() }) {
            // No class is left to remind of, so a reminder still up for one goes as its alarm did.
            refreshShownLocked(emptyMap(), leadTimeSec * 1000L, AppClock.nowMillis())
            return@synchronized null
        }

        val now = AppClock.nowMillis()
        val scheduled = mutableSetOf<String>()
        var nextCode = trackerPrefs.getInt(KEY_NEXT_CODE, 1)
        var nextClassStart: Long? = null
        // By CourseMeetings' id, which a posted reminder carries.
        val ahead = mutableMapOf<String, UpcomingSlot>()

        for (slot in upcomingSlots(
            coursesByTerm, skippedDates, daysAhead = DAYS_AHEAD,
            calendar = calendar, optedInHolidayIds = optedInHolidayIds,
            changes = courseChanges(),
        )) {
            // Before the trigger check: a class whose reminder has gone off
            // still has its banner up until it begins.
            if (slot.startMs > now) {
                nextClassStart = minOf(nextClassStart ?: slot.startMs, slot.startMs)
                ahead[slot.classId] = slot
            }
            val triggerTime = slot.startMs - leadTimeSec * 1000
            if (triggerTime <= now) continue

            val requestCode = codeMap.getOrPut(slot.id) {
                val current = nextCode
                nextCode = if (current == Int.MAX_VALUE) 1 else current + 1
                current
            }
            val intent = Intent(context, ClassPreparingNotificationReceiver::class.java).apply {
                putExtra(
                    ClassPreparingNotificationReceiver.EXTRA_COURSE_NAME,
                    slot.course.displayName
                )
                putExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM, slot.classroom)
                putExtra(
                    ClassPreparingNotificationReceiver.EXTRA_INSTRUCTOR,
                    slot.course.instructor
                )
                putExtra(ClassPreparingNotificationReceiver.EXTRA_TERM, slot.term)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_CLASS_ID, slot.classId)
                if (armedFor != null) putExtra(ClassPreparingNotificationReceiver.EXTRA_STUDENT_ID, armedFor)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, slot.startMs)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_END_MS, slot.endMs)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_NOTIFICATION_ID, requestCode)
                putExtra(
                    ClassPreparingNotificationReceiver.EXTRA_LEAD_TIME_MS,
                    leadTimeSec * 1000L,
                )
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Without exact alarms, which Android 14 grants no app by default,
            // the reminder is still allowed through Doze: a plain set() would
            // wait for Doze's next maintenance window, however late that is.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        AppClock.realTimeFor(triggerTime),
                        pendingIntent
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        AppClock.realTimeFor(triggerTime),
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(triggerTime),
                    pendingIntent
                )
            }
            scheduled.add(slot.id)
        }

        codeMap.keys.retainAll(scheduled)
        trackerPrefs.edit()
            .putStringSet(KEY_SCHEDULED_IDS, scheduled)
            .putString(KEY_CODE_MAP, encodeCodeMap(codeMap))
            .putInt(KEY_NEXT_CODE, nextCode)
            .apply()
        refreshShownLocked(ahead, leadTimeSec * 1000L, now)
        nextClassStart
    }

    /**
     * Brings the reminders already up in step with their classes, [ahead]:
     * the alarms above cannot, as one that has gone off arms nothing to post
     * again. A reminder whose class is cancelled, gone or begun goes. One
     * whose class moved or changed rooms says so without sounding again, and
     * now expires at the new start; moved past [leadTimeMs], it goes, and the
     * alarm armed for the new start brings it back then. iOS ends a class's
     * Live Activity and updates it in place the same ways. A reminder that
     * names no class is left as it is.
     */
    private fun refreshShownLocked(ahead: Map<String, UpcomingSlot>, leadTimeMs: Long, now: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val shown = runCatching { manager.activeNotifications }.getOrNull() ?: return
        for (sbn in shown) {
            val posted = sbn.notification
            if (posted.channelId != ClassPreparingNotificationReceiver.CHANNEL_ID) continue
            val classId = posted.extras.getString(ClassPreparingNotificationReceiver.EXTRA_POSTED_CLASS_ID) ?: continue
            val slot = ahead[classId]
            val unchanged = slot != null &&
                posted.extras.getLong(ClassPreparingNotificationReceiver.EXTRA_CLASS_START_MS) == slot.startMs &&
                posted.extras.getLong(ClassPreparingNotificationReceiver.EXTRA_CLASS_END_MS) == slot.endMs &&
                posted.extras.getString(ClassPreparingNotificationReceiver.EXTRA_CLASS_ROOM) == slot.classroom
            when {
                unchanged -> Unit
                // Through the stack, so its summary goes with the last banner.
                slot == null || slot.startMs - leadTimeMs > now ->
                    NotificationGroup.CLASS.cancel(context, sbn.id, sbn.tag)
                else -> ClassPreparingNotificationReceiver.post(
                    context, appLanguage(), sbn.id,
                    slot.course.displayName, slot.classroom, slot.course.instructor,
                    slot.startMs, slot.endMs, leadTimeMs,
                    classId = slot.classId, update = true,
                )
            }
        }
    }

    override fun cancelAllTracked() = synchronized(schedulerLock) {
        cancelAllTrackedLocked(loadCodeMap())
    }

    private fun cancelAllTrackedLocked(codeMap: Map<String, Int>) {
        val ids = trackerPrefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()
        for (id in ids) {
            val code = codeMap[id] ?: continue
            val intent = Intent(context, ClassPreparingNotificationReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        // Drop the per-slot code map alongside the scheduled-ids set so it
        // doesn't accumulate dead entries across reschedules, boots, and
        // logouts. KEY_NEXT_CODE is intentionally preserved to avoid handing
        // out a code that some still-pending alarm might be using.
        trackerPrefs.edit()
            .remove(KEY_SCHEDULED_IDS)
            .remove(KEY_CODE_MAP)
            .apply()
    }

    private fun loadCodeMap(): MutableMap<String, Int> {
        val raw = trackerPrefs.getString(KEY_CODE_MAP, null) ?: return mutableMapOf()
        if (raw.isEmpty()) return mutableMapOf()
        val out = mutableMapOf<String, Int>()
        for (entry in raw.split(';')) {
            val sep = entry.lastIndexOf('=')
            if (sep <= 0) continue
            val key = entry.substring(0, sep)
            val code = entry.substring(sep + 1).toIntOrNull() ?: continue
            out[key] = code
        }
        return out
    }

    private fun encodeCodeMap(map: Map<String, Int>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private data class UpcomingSlot(
        val course: Course,
        /** The term whose timetable [course] came from. */
        val term: String,
        val startMs: Long,
        val endMs: Long,
        val id: String,
        /** [CourseMeetings]' id for the class, which the receiver checks against the feed again. */
        val classId: String,
        /**
         * This class's room, the changed one included: a course meeting twice
         * a day can switch rooms in between.
         */
        val classroom: String,
    )

    private fun upcomingSlots(
        coursesByTerm: Map<String, List<Course>>,
        skippedDates: Map<String, List<String>>,
        daysAhead: Int,
        calendar: AcademicCalendar,
        optedInHolidayIds: Set<Int>,
        changes: CourseChanges,
    ): List<UpcomingSlot> {
        val today = AppClock.localDateTime().toLocalDate()
        val results = mutableListOf<UpcomingSlot>()
        for (dayOffset in 0 until daysAhead) {
            val date = today.plusDays(dayOffset.toLong())
            // No 即將上課 alarms outside the term. The timetable is populated
            // weeks before 開學 (選課 opens ahead of the term), so an install in
            // late August would otherwise arm reminders for classes that have
            // not started.
            if (!calendar.isInSession(date)) continue
            // Classes do not meet on a school holiday, so nothing should be
            // scheduled for that day at all. Both checks are per day rather
            // than at the call site because this loop reaches ten days ahead
            // and either 開學 or a holiday can land partway through that window.
            if (calendar.suppressesClasses(date, optedInHolidayIds)) continue
            // Each day from its own term's timetable: the window reaches past
            // 開學 while the term the app shows is still the last one, whose
            // classes, times and rooms are not the new term's. A term with
            // nothing cached arms nothing rather than borrow another's.
            val term = SemesterCodes.termOn(calendar, date)
            val courses = coursesByTerm[term] ?: continue
            val isoDate = date.toString()
            // A meeting the school split arms each class of it that still
            // meets, from its own start.
            val classes = CourseMeetings.meetingSlots(
                courses, date, changes,
                // Checked against the calendar above.
                isClassDay = { true },
                skipped = { course, day -> skippedDates[course.courseNo]?.contains(day.toString()) == true },
            )
            for (slot in classes) {
                results += UpcomingSlot(
                    course = slot.course,
                    term = term,
                    startMs = slot.startsAt.toEpochMilli(),
                    endMs = slot.endsAt.toEpochMilli(),
                    id = "${slot.course.courseNo}@${isoDate}#${slot.periods.first()}-${slot.periods.last()}",
                    classId = slot.id,
                    classroom = slot.room,
                )
            }
        }
        return results
    }

    companion object {
        private const val TRACKER_PREFS = "class_preparing_tracker"
        private const val KEY_SCHEDULED_IDS = "scheduled_ids"
        private const val KEY_CODE_MAP = "slot_request_codes"
        private const val KEY_NEXT_CODE = "next_request_code"
        private const val DAYS_AHEAD = 10

        /**
         * The terms whose courses [scheduleAll] needs to arm the days ahead
         * from [today]; see [SemesterCodes.termOn].
         */
        internal fun termsAhead(calendar: AcademicCalendar, today: LocalDate): Set<String> =
            (0 until DAYS_AHEAD).mapTo(linkedSetOf()) { SemesterCodes.termOn(calendar, today.plusDays(it.toLong())) }
    }
}
