package org.ntust.app.tigerduck.mail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.MailDemoGate
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.store.MailStateStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleDecision(val alarm: Boolean, val worker: Boolean)

/** Spec §8.5: exact alarm every [INTERVAL_MINUTES] plus a 15-minute WorkManager backstop. */
object MailSchedulePolicy {
    /**
     * Initial value; T5/T6 may change it (spec A.6). In Doze the platform also
     * enforces a minimum gap between an app's allow-while-idle alarms, so idle
     * ticks can land further apart than this.
     */
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

    /**
     * A hand-off run that finds [CheckOutcome.Busy] ran into the alarm check it
     * replaces, still unwinding a stalled read: it retries, or that round is
     * lost. The periodic run just waits for its next period.
     */
    fun shouldRetry(outcome: CheckOutcome, handOff: Boolean): Boolean = handOff && outcome == CheckOutcome.Busy
}

@Singleton
class MailAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentials: MailCredentialStore,
    private val state: MailStateStore,
    private val demoGate: MailDemoGate,
) : MailBackgroundScheduler {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun schedule() {
        val decision = MailSchedulePolicy.decide(
            signedIn = !credentials.mailStudentId.isNullOrBlank() && !credentials.mailPassword.isNullOrEmpty(),
            // Same rule as MailAccount.isDemo: the app-wide demo never opens a socket either.
            demo = state.demoMailbox || demoGate.appDemoActive,
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

/**
 * One exact-alarm tick: re-arm first (exact alarms are one-shot), then check,
 * waiting at most [budgetMillis] so the broadcast is released in time.
 *
 * The check is blocking IMAP IO that cancellation cannot interrupt, and closing
 * its connection from another thread would only queue behind the Angus locks
 * the stalled read holds. So the tick never joins the check: it runs detached
 * in [scope], and once the budget runs out [handOff] is called and null
 * returned, while the check finishes on its own socket timeout, recording its
 * outcome and holding [MailChecker]'s lock until then.
 */
internal suspend fun runAlarmTick(
    scope: CoroutineScope,
    budgetMillis: Long,
    rearm: () -> Unit,
    check: suspend () -> CheckOutcome,
    handOff: () -> Unit,
): CheckOutcome? {
    val tick = scope.async(Dispatchers.IO) {
        rearm()
        check()
    }
    val outcome = withTimeoutOrNull(budgetMillis) { tick.await() }
    if (outcome == null) handOff()
    return outcome
}

@AndroidEntryPoint
class MailAlarmReceiver : BroadcastReceiver() {
    // Lazy: building these can open and read the encrypted credential store, which stays off the main thread too.
    @Inject lateinit var checker: dagger.Lazy<MailChecker>
    @Inject lateinit var scheduler: dagger.Lazy<MailBackgroundScheduler>

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        appScope.launch(Dispatchers.IO) {
            try {
                guarded("check") {
                    runAlarmTick(
                        scope = appScope,
                        budgetMillis = RECEIVER_BUDGET_MS,
                        rearm = { guarded("re-arm") { scheduler.get().schedule() } },
                        check = { checker.get().check(CheckSource.ALARM) },
                        handOff = { MailCheckWorker.enqueueOnce(context) },
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MailAlarmReceiver"

        /** goAsync() work should end within about 10 s, and Doze grants an allow-while-idle alarm about as long. */
        const val RECEIVER_BUDGET_MS = 8_000L

        /** Logs only the exception's class: its message can carry a server reply or an address. */
        inline fun guarded(step: String, block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "alarm $step failed: ${t.javaClass.simpleName}")
            }
        }
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
        val outcome = checker.check(CheckSource.WORKER)
        val handOff = inputData.getBoolean(KEY_HAND_OFF, false)
        return if (MailSchedulePolicy.shouldRetry(outcome, handOff)) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "school_mail_periodic"
        private const val ONCE = "school_mail_once"
        private const val KEY_HAND_OFF = "hand_off"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            // KEEP: re-arming from every alarm must not restart the period.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Takes over an alarm check that outlived the broadcast budget. */
        fun enqueueOnce(context: Context) {
            val builder = OneTimeWorkRequestBuilder<MailCheckWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(KEY_HAND_OFF to true))
            // Expedited on API 31+ only: below that WorkManager runs expedited work as a foreground service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, builder.build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(ONCE)
            }
        }
    }
}
