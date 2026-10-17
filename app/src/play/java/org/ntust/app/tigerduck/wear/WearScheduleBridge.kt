package org.ntust.app.tigerduck.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.library.libraryCredentialsForWatch
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.util.logSummary
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearScheduleBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val appPreferences: AppPreferences,
    private val credentials: CredentialManager,
    private val academicCalendar: AcademicCalendarStore,
) {
    private val gson = Gson()

    // Monotonic credential epoch persisted across phone process death so
    // the watch's anti-replay guard (`version <= storedVersion` → reject)
    // sees a strictly increasing version after a phone restart. Kept in a
    // private prefs file so a SharedPreferences corruption in any other
    // user-facing prefs can't reset it.
    private val bridgePrefs = context.getSharedPreferences(
        "wear_bridge_state", Context.MODE_PRIVATE
    )

    @Synchronized
    private fun nextCredentialEpoch(): Long {
        val next = bridgePrefs.getLong(KEY_CRED_EPOCH, 0L) + 1
        bridgePrefs.edit().putLong(KEY_CRED_EPOCH, next).apply()
        return next
    }

    /**
     * Publishes the current course list, accent color, login state and the
     * user's quiet days to the watch via DataClient. Safe to call repeatedly —
     * same payload no-ops.
     */
    suspend fun publish() {
        val loggedIn = authService.authState.value
        val courses = if (loggedIn) {
            dataCache.loadCourses()
        } else {
            // On logout, explicitly clear the watch's view.
            emptyList()
        }
        val effectiveAccent = if (appPreferences.accentColorHex == AppPreferences.ACCENT_WALLPAPER) {
            0x007AFF
        } else {
            appPreferences.accentColorHex
        }
        val accentHex = "#%06X".format(0xFFFFFF and effectiveAccent)
        val payload = gson.toJson(courses.map { it.toDto() }).toByteArray()
        val gzipped = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(payload) }
        }.toByteArray()

        // The watch can't observe the phone's locale, so send the concrete tag
        // the phone UI is rendering in rather than the "system" sentinel.
        val languageTag = AppLanguageManager.uiLanguageTag(appPreferences.appLanguage)

        // Mirror the debug-only screen-capture override to the watch. Force
        // false in release builds so a stale-from-debug pref on disk cannot
        // weaken watch-side protection — the developer-section toggle that
        // writes the pref is itself DEBUG-gated, so this is purely a
        // defense-in-depth for the unlikely "downgrade debug→release with
        // pref carried over" path.
        val disableScreenCaptureProtection =
            BuildConfig.DEBUG && appPreferences.disableScreenCaptureProtection

        val quietDays = wearQuietDays(
            academicCalendar.current(),
            academicCalendar.optedInHolidayIds,
            AppClock.localDateTime().toLocalDate(),
        )

        val request = PutDataMapRequest.create(WearProtocol.Schedule.PATH).apply {
            dataMap.putByteArray(WearProtocol.Schedule.KEY_COURSES, gzipped)
            dataMap.putString(WearProtocol.Schedule.KEY_ACCENT, accentHex)
            dataMap.putLong(WearProtocol.Schedule.KEY_SYNCED_AT, System.currentTimeMillis())
            dataMap.putBoolean(WearProtocol.Schedule.KEY_LOGGED_IN, loggedIn)
            dataMap.putString(WearProtocol.Schedule.KEY_LANGUAGE, languageTag)
            dataMap.putBoolean(
                WearProtocol.Schedule.KEY_DISABLE_SCREEN_CAPTURE_PROTECTION,
                disableScreenCaptureProtection,
            )
            dataMap.putStringArrayList(WearProtocol.Schedule.KEY_QUIET_DAYS, quietDays)
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(
                TAG,
                "publish ok: ${courses.size} courses, ${gzipped.size} bytes, " +
                    "${quietDays.size} quiet days",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed: ${t.logSummary()}")
        }
    }

    /**
     * Push the current library credentials to the watch over the Data Layer so
     * it can independently call `api.lib.ntust.edu.tw` for the rotating QR.
     * Sends a `hasCredentials=false` packet when the user is logged out so the
     * watch wipes its local copy rather than holding stale creds — and while
     * the library has refused the stored password; see [libraryCredentialsForWatch].
     */
    suspend fun publishLibraryCredentials() {
        val account = libraryCredentialsForWatch(credentials)
        val username = account?.first
        val password = account?.second
        val hasCredentials = account != null

        val request = PutDataMapRequest.create(WearProtocol.LibraryCredentials.PATH).apply {
            dataMap.putBoolean(WearProtocol.LibraryCredentials.KEY_HAS_CREDENTIALS, hasCredentials)
            // Monotonic across phone process restarts — see nextCredentialEpoch().
            dataMap.putLong(
                WearProtocol.LibraryCredentials.KEY_VERSION,
                nextCredentialEpoch(),
            )
            if (username != null && password != null) {
                dataMap.putString(WearProtocol.LibraryCredentials.KEY_USERNAME, username)
                dataMap.putString(WearProtocol.LibraryCredentials.KEY_PASSWORD, password)
                credentials.libraryToken?.let {
                    dataMap.putString(WearProtocol.LibraryCredentials.KEY_TOKEN, it)
                }
                dataMap.putLong(
                    WearProtocol.LibraryCredentials.KEY_TOKEN_EXPIRY,
                    credentials.libraryTokenExpiry,
                )
                // Bounds-staleness anchor: the watch wipes its keychain if it
                // hasn't seen a fresh issuedAtMs within 7 days, so a paired
                // wearable that goes silent (uninstalled phone, lost watch)
                // can't hold credentials indefinitely.
                dataMap.putLong(
                    WearProtocol.LibraryCredentials.KEY_ISSUED_AT_MS,
                    System.currentTimeMillis(),
                )
            }
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "library credentials publish ok (has=$hasCredentials)")
        } catch (t: Throwable) {
            Log.w(TAG, "library credentials publish failed: ${t.logSummary()}")
        }
    }

    private fun Course.toDto(): CourseDto = toWearDto()

    /** On-the-wire shape; explicit so we can evolve [Course] without breaking watch JSON. */
    data class CourseDto(
        val courseNo: String,
        val courseName: String,
        val instructor: String,
        /**
         * Whole credits, truncated. Retained for watches that predate
         * [creditsExact]: their Gson reads this key through `nextInt()`,
         * which throws on a fractional value, and `SchedulePersistence`
         * catches that into an empty schedule — the watch would drop every
         * course rather than one number. New watches ignore it.
         */
        val credits: Int,
        /** The real, possibly fractional count. Absent from an older phone. */
        val creditsExact: Float? = null,
        val classroom: String,
        val scheduleJson: String,
        val classroomMapJson: String = "{}",
        val moodleIdNumber: String?,
        val customColorHex: String?,
        val isManual: Boolean,
    )

    private companion object {
        const val TAG = "WearBridge"
        const val KEY_CRED_EPOCH = "libraryCredentialEpoch"
    }
}

/**
 * How far ahead the watch is told about quiet days. Bounded because every day
 * after the last published term is quiet, and the list shares the Data Layer
 * item with the courses; the phone sends a fresh list at every app start and
 * watch sync request, far sooner than a year runs out.
 */
internal const val WEAR_QUIET_DAYS_AHEAD = 366L

/**
 * The days from [today] through [WEAR_QUIET_DAYS_AHEAD] days later on which
 * no class meets for this user ([AcademicCalendar.isQuietDay]), in the form
 * the watch reads under [WearProtocol.Schedule.KEY_QUIET_DAYS].
 */
internal fun wearQuietDays(
    calendar: AcademicCalendar,
    optedInHolidayIds: Set<Int>,
    today: LocalDate,
): ArrayList<String> = WearProtocol.Schedule.encodeQuietDays(
    calendar.quietDays(optedInHolidayIds, today, today.plusDays(WEAR_QUIET_DAYS_AHEAD))
)

internal fun Course.toWearDto(): WearScheduleBridge.CourseDto = WearScheduleBridge.CourseDto(
    courseNo = courseNo,
    courseName = displayName,
    instructor = instructor,
    credits = credits.toInt(),
    creditsExact = credits,
    classroom = classroom,
    scheduleJson = scheduleJson,
    classroomMapJson = classroomMapJson ?: "{}",
    moodleIdNumber = moodleIdNumber,
    customColorHex = customColorHex,
    isManual = isManual,
)
