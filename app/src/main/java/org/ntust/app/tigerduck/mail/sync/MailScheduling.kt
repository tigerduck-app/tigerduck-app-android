package org.ntust.app.tigerduck.mail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.store.MailStateStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleDecision(val alarm: Boolean, val worker: Boolean)

/** Spec §8.5: exact alarm every [INTERVAL_MINUTES] plus a 15-minute WorkManager backstop. */
object MailSchedulePolicy {
    /** Initial value; T5/T6 may change it (spec A.6). Doze caps AllowWhileIdle alarms at ~9 min anyway. */
    const val INTERVAL_MINUTES = 15L

    fun decide(
        signedIn: Boolean,
        demo: Boolean,
        notificationsEnabled: Boolean,
        authFailed: Boolean,
        canExactAlarm: Boolean,
    ): ScheduleDecision {
        val active = signedIn && !demo && notificationsEnabled && !authFailed
        return ScheduleDecision(alarm = active && canExactAlarm, worker = active)
    }
}

@Singleton
class MailAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentials: MailCredentialStore,
    private val state: MailStateStore,
) : MailBackgroundScheduler {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun schedule() {
        val decision = MailSchedulePolicy.decide(
            signedIn = !credentials.mailStudentId.isNullOrBlank() && !credentials.mailPassword.isNullOrEmpty(),
            demo = state.demoMailbox,
            notificationsEnabled = state.notificationsEnabled,
            authFailed = state.authFailed,
            canExactAlarm = canScheduleExactAlarms(),
        )
        if (decision.alarm) {
            alarms.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(MailSchedulePolicy.INTERVAL_MINUTES),
                alarmIntent(),
            )
        } else {
            alarms.cancel(alarmIntent())
        }
        if (decision.worker) MailCheckWorker.schedulePeriodic(context) else MailCheckWorker.cancel(context)
    }

    override fun cancel() {
        alarms.cancel(alarmIntent())
        MailCheckWorker.cancel(context)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, MailAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_CODE = 4_2001
    }
}

/** One exact-alarm tick: re-arm first (exact alarms are one-shot), then check within the broadcast window. */
@AndroidEntryPoint
class MailAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var checker: MailChecker
    @Inject lateinit var scheduler: MailBackgroundScheduler

    override fun onReceive(context: Context, intent: Intent) {
        scheduler.schedule()
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val outcome = withTimeoutOrNull(RECEIVER_BUDGET_MS) { checker.check(CheckSource.ALARM) }
                if (outcome == null) MailCheckWorker.enqueueOnce(context)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}

/** Alarms and WorkManager state are re-armed after reboot, an app update, or a change to the exact-alarm grant. */
@AndroidEntryPoint
class MailRescheduleReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: MailBackgroundScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> scheduler.schedule()
        }
    }
}

@HiltWorker
class MailCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: MailChecker,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        checker.check(CheckSource.WORKER)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "school_mail_periodic"
        private const val ONCE = "school_mail_once"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            // KEEP: re-arming from every alarm must not restart the period.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<MailCheckWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(ONCE)
            }
        }
    }
}
