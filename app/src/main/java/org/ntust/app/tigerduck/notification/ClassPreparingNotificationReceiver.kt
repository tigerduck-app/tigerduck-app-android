package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.pendingIntent
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ClassPreparingNotificationReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun academicCalendar(): AcademicCalendarStore
        fun appPreferences(): AppPreferences
        fun notificationChannels(): NotificationChannelRegistrar
    }

    override fun onReceive(rawContext: Context, intent: Intent) {
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: return
        val classroom = intent.getStringExtra(EXTRA_CLASSROOM).orEmpty()
        val instructor = intent.getStringExtra(EXTRA_INSTRUCTOR).orEmpty()
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        // Stable id injected by the scheduler's persisted code map. Matches the
        // PendingIntent request code, so two concurrent class alerts never
        // collide the way slotId.hashCode() could.
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId < 0) return

        // Defence in depth for the term gate, which primarily lives in
        // ClassPreparingNotificationScheduler.upcomingSlots. v1.4.3 armed these
        // alarms for pre-開學 days, and an AlarmManager entry outlives the
        // upgrade until the next scheduleAll cancels it — which only happens on
        // the next sync or Live Update refresh. Until then a stale alarm would
        // still land here and post, so re-check at the point of posting rather
        // than trusting that the alarm should have existed at all. An empty
        // calendar reads as in session, so a device that has never reached the
        // backend keeps the reminders it had. The term the day falls in is
        // re-checked the same way; see stillDue.
        val startDate = runCatching {
            Instant.ofEpochMilli(startMs).atZone(AppConstants.TAIPEI_ZONE).toLocalDate()
        }.getOrNull() ?: return
        val deps = EntryPointAccessors
            .fromApplication(rawContext.applicationContext, Deps::class.java)
        if (!stillDue(deps.academicCalendar().current(), startDate, intent.getStringExtra(EXTRA_TERM))) return
        // The lead-time extra lets us auto-cancel the "即將上課" notification when
        // class actually starts: post-time + leadTimeMs ≈ classStart. Without it
        // (older intents from before the field existed) we fall back to manual
        // cancel via auto-cancel-on-tap, matching the original behaviour.
        val leadTimeMs = intent.getLongExtra(EXTRA_LEAD_TIME_MS, 0L)

        if (!mayPost(rawContext, deps.appPreferences())) return

        // One shot: if the channel is missing, the reminder is dropped for good.
        deps.notificationChannels().ensureRegistered()
        post(
            rawContext, deps.appPreferences().appLanguage,
            notificationId, courseName, classroom, instructor, startMs, endMs, leadTimeMs,
        )
    }

    companion object {
        /** Created with the others at launch; see [NotificationChannels.registerAll]. */
        const val CHANNEL_ID = NotificationChannels.CLASS_PREPARING
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_CLASSROOM = "classroom"
        const val EXTRA_INSTRUCTOR = "instructor"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_END_MS = "end_ms"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_LEAD_TIME_MS = "lead_time_ms"

        /** The term whose timetable the reminder was armed from; absent on alarms armed before it was added. */
        const val EXTRA_TERM = "term"

        /**
         * Whether a reminder for a class on [startDate], armed from
         * [armedTerm]'s timetable, still goes out: classes meet that day,
         * and the calendar still puts it in that term. An alarm armed before
         * the calendar moved 開學 would otherwise announce the other term's
         * class. With no calendar, or no term on the alarm (one armed by a
         * build before [EXTRA_TERM]), only the first check applies.
         */
        internal fun stillDue(calendar: AcademicCalendar, startDate: LocalDate, armedTerm: String?): Boolean {
            if (!calendar.isInSession(startDate)) return false
            val term = calendar.currentTerm(startDate)?.code ?: return true
            return armedTerm == null || armedTerm == term
        }

        /**
         * On the posted banner, not the alarm: the class's start, epoch ms,
         * which LiveActivityNotifier.clearStartedClassBanners reads to clear
         * the banner once the class has begun.
         */
        const val EXTRA_CLASS_START_MS = "org.ntust.app.tigerduck.class_start_ms"

        /**
         * The class reminder switch is on, and the app may post
         * notifications. Read when the alarm fires: turning reminders off
         * cancels the alarms already armed, but one armed in a race with that
         * cancel would still land here, and the switch has the last word.
         */
        internal fun mayPost(context: Context, prefs: AppPreferences): Boolean =
            prefs.classReminderEnabled &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)

        /**
         * Post the reminder, past every check onReceive makes first; the
         * developer menu's preview posts through here too. The channels must
         * exist.
         *
         * In [appLanguage]: receiver contexts carry the system locale, not
         * the in-app choice (AppCompat per-app locales don't reach broadcast
         * contexts when the alarm wakes a dead process), so the chosen
         * language is resolved here, where no caller can skip it.
         *
         * A tap opens the class table, through `tigerduck://classtable`.
         */
        fun post(
            baseContext: Context,
            appLanguage: String,
            notificationId: Int,
            courseName: String,
            classroom: String,
            instructor: String,
            startMs: Long,
            endMs: Long,
            leadTimeMs: Long,
        ) {
            val context = AppLanguageManager.localizedContext(baseContext, appLanguage)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val timeRange = formatTimeRange(startMs, endMs)
            val detail = listOfNotNull(
                timeRange.takeIf { it.isNotBlank() },
                classroom.takeIf { it.isNotBlank() },
                instructor.takeIf { it.isNotBlank() },
            ).joinToString(" · ")

            // Anchor the auto-dismiss to actual class-start time rather than
            // post-time + leadTimeMs. When the alarm is delivered via the inexact
            // fallback, post-time can drift up to ~5 min late, which would leave
            // the banner up past class start. A duration on the app's clock, as
            // the class times are: under the developer menu's clock the real
            // one is days away from them.
            val timeout = when {
                startMs > 0L -> (startMs - AppClock.nowMillis()).coerceAtLeast(1_000L)
                leadTimeMs > 0L -> leadTimeMs
                else -> 0L
            }

            val notification = context.brandedNotification(CHANNEL_ID)
                .setContentTitle(
                    context.getString(R.string.notification_class_preparing_title, courseName)
                )
                .setContentText(
                    detail.ifBlank {
                        context.getString(R.string.notification_class_preparing_content_fallback)
                    }
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(AppLink.ClassTable.pendingIntent(context))
                .setGroup(NotificationGroup.CLASS.key)
                .apply { if (startMs > 0L) addExtras(Bundle().apply { putLong(EXTRA_CLASS_START_MS, startMs) }) }
                .apply { if (timeout > 0L) setTimeoutAfter(timeout) }
                .build()

            nm.notify(notificationId, notification)
            // Expires with the reminder, or it would outlive it as an empty row.
            NotificationGroup.CLASS.postSummary(context, timeoutAfterMs = timeout)
        }

        private fun formatTimeRange(startMs: Long, endMs: Long): String {
            if (startMs <= 0 || endMs <= 0) return ""
            val zone: ZoneId = AppConstants.TAIPEI_ZONE
            val s = Instant.ofEpochMilli(startMs).atZone(zone).toLocalTime()
            val e = Instant.ofEpochMilli(endMs).atZone(zone).toLocalTime()
            return "%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
        }
    }
}
