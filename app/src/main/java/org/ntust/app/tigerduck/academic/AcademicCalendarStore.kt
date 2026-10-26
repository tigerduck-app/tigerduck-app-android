package org.ntust.app.tigerduck.academic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.model.AcademicCalendarDto
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the school's academic calendar and keeps it fresh.
 *
 * Deliberately outside the cloud-sync and flavour gates that wrap every
 * other backend call. The calendar carries no user, no device id and no
 * account — it is the school's published dates — so it is fetched over an
 * unauthenticated GET that signed-out users, sync-off users and the fdroid
 * build all make. Suppressing class reminders on a public holiday should not
 * depend on whether someone opted into syncing their timetable.
 *
 * The payload is cached verbatim so the notification scheduler, which runs
 * from an alarm with no network, can still answer "is today a holiday".
 */
@Singleton
class AcademicCalendarStore @Inject constructor(
    @ApplicationContext context: Context,
    baseClient: OkHttpClient,
    private val appPreferences: AppPreferences,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = baseClient.newBuilder().build()
    private val api = BackendServices(AcademicCalendarApi::class.java, client)
    private val gson = Gson()
    private val refreshMutex = Mutex()

    // Lazy so merely injecting the store — which happens on the
    // Application.onCreate path — costs no disk read; the first caller that
    // actually asks a calendar question pays for it.
    private val _calendar: MutableStateFlow<AcademicCalendar> by lazy {
        MutableStateFlow(loadFromDisk())
    }
    val calendar: StateFlow<AcademicCalendar> get() = _calendar.asStateFlow()

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits when a refresh changes the calendar or the user's holiday opt-ins
     * change, for the paired watch, which keeps the quiet days it was last
     * sent and cannot ask this store, and for the empty-classroom finder, which
     * may have read a day the calendar then takes off. Separate from
     * [calendar], whose collectors would load the cached payload on every
     * process start.
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Holidays this user asked to keep hearing about.
     *
     * Local to the device by default; [org.ntust.app.tigerduck.data.preferences.AppPreferences]
     * is also what the cloud-sync path writes into when sync is on, so both
     * modes read the same set here.
     */
    val optedInHolidayIds: Set<Int>
        get() = appPreferences.holidayNotifyOverrides

    /** The calendar as of the last successful fetch, for callers with no
     *  coroutine to collect the flow in (alarm receivers, widgets). */
    fun current(): AcademicCalendar = _calendar.value

    /** The days no class meets on, as the calendar and opt-ins now stand. */
    fun quietDays(): QuietDays = QuietDays(current(), optedInHolidayIds)

    /**
     * Re-fetch, cheaply.
     *
     * Called on every app open. The server answers 304 with no body when
     * nothing changed, so the common case costs one conditional GET. Any
     * failure leaves the cached calendar in place — an unreachable backend
     * must not turn suppression off for a device that already knows the
     * dates.
     */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val base = resolveAnnouncementEndpoint(appPreferences).url
            val call = api.at(base).semesters("application/json", prefs.getString(KEY_ETAG, null))

            runCatching {
                call.execute().use { response ->
                    // This GET carries no account and runs on every app open
                    // whatever the sync setting is, which makes it the one
                    // signal that can tell the status dot whether the backend
                    // is alive for a device that does not sync. 304 counts as
                    // reachable — it is a served answer, just an empty one.
                    ServerStatusTracker.noteBackendReachable(
                        response.isSuccessful || response.code == HTTP_NOT_MODIFIED
                    )
                    when {
                        response.code == HTTP_NOT_MODIFIED -> true
                        response.isSuccessful -> {
                            val body = response.body.string()
                            val parsed = AcademicCalendar.from(
                                gson.fromJson(body, AcademicCalendarDto::class.java)
                            )
                            // Read before the write below: a first read loads
                            // the payload from disk and would see the new one.
                            val previous = _calendar.value
                            prefs.edit()
                                .putString(KEY_PAYLOAD, body)
                                .putString(KEY_ETAG, response.header("ETag"))
                                .apply()
                            _calendar.value = parsed
                            if (parsed != previous) _changes.tryEmit(Unit)
                            true
                        }
                        else -> false
                    }
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                ServerStatusTracker.noteBackendReachable(false)
                Log.w(TAG, "academic calendar refresh failed: ${e.logSummary()}")
                false
            }
        }
    }

    /**
     * Record whether the user wants class reminders on [holidayId].
     *
     * Writes the local set unconditionally — the guard has to work with
     * cloud sync off — and returns whether the value changed, so the caller
     * can decide about uploading without re-reading the set.
     */
    fun setNotifyOnHoliday(holidayId: Int, notify: Boolean): Boolean {
        val current = appPreferences.holidayNotifyOverrides
        val next = if (notify) current + holidayId else current - holidayId
        if (next == current) return false
        appPreferences.holidayNotifyOverrides = next
        _changes.tryEmit(Unit)
        return true
    }

    /** Replace the local set from a cloud-sync snapshot. */
    fun applySyncedOverrides(ids: Set<Int>) {
        val changed = ids != appPreferences.holidayNotifyOverrides
        appPreferences.holidayNotifyOverrides = ids
        if (changed) _changes.tryEmit(Unit)
    }

    private fun loadFromDisk(): AcademicCalendar {
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return AcademicCalendar.EMPTY
        return runCatching {
            AcademicCalendar.from(gson.fromJson(raw, AcademicCalendarDto::class.java))
        }.getOrElse {
            // A payload this build cannot read is worse than none: it would
            // pin a stale calendar forever. Drop it and let the next refresh
            // repopulate.
            Log.w(TAG, "cached academic calendar unreadable; discarding: ${it.logSummary()}")
            prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ETAG).apply()
            AcademicCalendar.EMPTY
        }
    }

    companion object {
        private const val TAG = "AcademicCalendar"
        private const val PREFS_NAME = "academic_calendar"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_ETAG = "etag"
        private const val HTTP_NOT_MODIFIED = 304
    }
}
