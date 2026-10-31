package org.ntust.app.tigerduck.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.library.libraryCredentialsForWatch
import org.ntust.app.tigerduck.network.tls.PinManager
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.tls.PinAcceptances
import org.ntust.app.tigerduck.util.logSummary
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
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
    private val pins: PinManager,
    private val courseChangeStore: CourseChangeStore,
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

    /**
     * The TLS pins push's version: the wall clock, or one past the last when the clock is behind
     * it. A clock-based start keeps a reinstalled phone, whose counter would begin again at 1,
     * ahead of what the watch last took, so its pushes, empty acceptances included, aren't
     * refused.
     */
    @Synchronized
    private fun nextTlsPinsEpoch(): Long {
        val next = tlsPinsVersion(bridgePrefs.getLong(KEY_TLS_PINS_EPOCH, 0L), System.currentTimeMillis())
        bridgePrefs.edit().putLong(KEY_TLS_PINS_EPOCH, next).apply()
        return next
    }

    @Synchronized
    private fun nextCredentialEpoch(): Long {
        val next = bridgePrefs.getLong(KEY_CRED_EPOCH, 0L) + 1
        bridgePrefs.edit().putLong(KEY_CRED_EPOCH, next).apply()
        return next
    }

    /**
     * Publishes the current course list, accent color, login state, the
     * user's quiet days and the classes the school changed to the watch via
     * DataClient. Safe to call repeatedly — same payload no-ops.
     */
    suspend fun publish() = schedule.publish()

    // One publish at a time, as the TLS pins': a course save, the sign-in, the calendar, the
    // course changes feed and the rest each start one of their own, and a publish that read the
    // phone before another must not be put after it, which would leave the watch signed in after
    // a sign-out or showing a feed the phone no longer holds.
    private val schedule = WatchItemPublisher(snapshot = { scheduleItem() }) { (request, summary) ->
        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "publish ok: $summary")
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed: ${t.logSummary()}")
        }
    }

    /** The schedule item as the phone stands now, with what the log says of it. */
    private suspend fun scheduleItem(): Pair<PutDataRequest, String> {
        val loggedIn = authService.authState.value
        val courses = if (loggedIn) {
            dataCache.loadCourses()
        } else {
            // On logout, explicitly clear the watch's view.
            emptyList()
        }
        val accentHex = wearAccentHex(appPreferences)
        val accentGradient = wearAccentGradient(appPreferences)
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
        val courseChanges = wearCourseChanges(courses, courseChangeStore.current())

        val request = PutDataMapRequest.create(WearProtocol.Schedule.PATH).apply {
            dataMap.putByteArray(WearProtocol.Schedule.KEY_COURSES, gzipped)
            dataMap.putString(WearProtocol.Schedule.KEY_ACCENT, accentHex)
            dataMap.putString(WearProtocol.Schedule.KEY_VISUAL_PRESET, appPreferences.visualPreset.rawValue)
            // Left out for a single color: each publish is a whole new item,
            // so the watch then reads no gradient.
            if (accentGradient != null) {
                dataMap.putString(WearProtocol.Schedule.KEY_ACCENT_END, accentGradient.endHex)
                dataMap.putInt(WearProtocol.Schedule.KEY_ACCENT_DIRECTION, accentGradient.directionCode)
            }
            dataMap.putLong(WearProtocol.Schedule.KEY_SYNCED_AT, System.currentTimeMillis())
            dataMap.putBoolean(WearProtocol.Schedule.KEY_LOGGED_IN, loggedIn)
            dataMap.putString(WearProtocol.Schedule.KEY_LANGUAGE, languageTag)
            dataMap.putBoolean(
                WearProtocol.Schedule.KEY_DISABLE_SCREEN_CAPTURE_PROTECTION,
                disableScreenCaptureProtection,
            )
            dataMap.putStringArrayList(WearProtocol.Schedule.KEY_QUIET_DAYS, quietDays)
            // A key of its own rather than fields on CourseDto, so the course JSON keeps the
            // shape every watch build reads.
            dataMap.putString(WearProtocol.Schedule.KEY_COURSE_CHANGES, courseChanges)
        }.asPutDataRequest().setUrgent()

        return request to "${courses.size} courses, ${gzipped.size} bytes, " +
            "${quietDays.size} quiet days, ${courseChanges.length} chars of course changes"
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

    /**
     * The TLS pins for the watch (TLS pins spec, A8): the document envelope the phone verified,
     * as received, and the student's acceptances, each with the phone's build number. The watch
     * verifies the envelope with its own keys and applies an acceptance only while its build is
     * the matching one.
     */
    suspend fun publishTlsPins() = tlsPins.publish()

    private val tlsPins = WatchItemPublisher(
        snapshot = {
            PutDataMapRequest.create(WearProtocol.TlsPins.PATH).apply {
                pins.envelope?.let { dataMap.putByteArray(WearProtocol.TlsPins.KEY_ENVELOPE, it) }
                dataMap.putString(WearProtocol.TlsPins.KEY_ACCEPTANCES, PinAcceptances.encode(pins.currentAcceptances))
                dataMap.putLong(WearProtocol.TlsPins.KEY_VERSION, nextTlsPinsEpoch())
            }.asPutDataRequest().setUrgent()
        },
    ) { request ->
        try {
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (t: Throwable) {
            Log.w(TAG, "TLS pins publish failed: ${t.logSummary()}")
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
        const val KEY_TLS_PINS_EPOCH = "tlsPinsEpoch"
    }
}

/** The TLS pins push after [last]: the wall clock [now], or one past [last] when the clock is behind. */
internal fun tlsPinsVersion(last: Long, now: Long): Long = maxOf(last + 1, now)

/**
 * The accent the watch is sent, as `#RRGGBB`: the swatch last picked, or the
 * start of the gradient last picked (see [wearAccentGradient] for the rest of
 * it), also while the phone follows the wallpaper, as the watch has no
 * wallpaper colors of its own to follow (see [AccentPolicy]).
 */
internal fun wearAccentHex(prefs: AppPreferences): String = wearHex(prefs.accentColorHex)

/** The rest of the accent gradient the watch is sent: its end as `#RRGGBB` and its direction's code. */
internal data class WearAccentGradient(val endHex: String, val directionCode: Int)

/**
 * The end and direction of the gradient picked, which the watch draws from
 * [wearAccentHex], or null for a single color. A gradient counts by the rule
 * the phone's own screens follow, so one an older build picked over goes no
 * more.
 */
internal fun wearAccentGradient(prefs: AppPreferences): WearAccentGradient? {
    val end = AccentPolicy.gradientEnd(prefs.accentColorHex, prefs.accentGradientStartHex, prefs.accentGradientEndHex)
        ?: return null
    return WearAccentGradient(wearHex(end), prefs.accentGradientDirection.code)
}

/**
 * [lightHex] as the watch draws it: a preset as it always has gone, and a
 * color of the user's own as dark mode draws it, as the watch draws on black,
 * where a dark one would leave its rings and guides invisible.
 */
private fun wearHex(lightHex: Int): String {
    val sent = if (AccentPolicy.isPreset(lightHex)) lightHex else AccentPolicy.darkHex(lightHex)
    return "#%06X".format(0xFFFFFF and sent)
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

/**
 * The classes the school changed in [courses], on every day of the feed, past days included, in
 * the form the watch reads under [WearProtocol.Schedule.KEY_COURSE_CHANGES]: each meeting moved,
 * cancelled or given a room, as the classes it splits into, cancelled ones included. A meeting
 * with only a note is left out. As the iPhone's `WatchPayloadEncoder` fills `changes`.
 */
internal fun wearCourseChanges(courses: List<Course>, changes: CourseChanges): String {
    val byCourse = courses.associate { course ->
        course.courseNo to changes.days(course.courseNo)
            .associateWith { day -> CourseMeetings.changedClasses(course, day, changes).map(::wearClassChange) }
            .filterValues { it.isNotEmpty() }
    }
    return WearProtocol.Schedule.encodeCourseChanges(WatchCourseChanges(byCourse.filterValues { it.isNotEmpty() }))
}

/** [slot] with only the values the school changed, as the iPhone's `SnapshotCourseChange`. */
private fun wearClassChange(slot: ClassSlot) = WatchClassChange(
    periods = slot.periods,
    cancelled = slot.isCancelled,
    room = slot.room.takeIf { slot.hasChangedRoom },
    start = slot.originalStart?.let { wearHm(slot.start) },
    end = slot.originalEnd?.let { wearHm(slot.end) },
)

// Latin digits in every locale, as the watch parses them.
private fun wearHm(time: LocalTime): String = "%02d:%02d".format(Locale.US, time.hour, time.minute)

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

/**
 * Publishes a Data Layer item one at a time: a snapshot is taken and put with no other publish of
 * the item in between, so the item last put is always the newest. The TLS pins item's snapshot
 * takes its version too (A8); the schedule's reads the sign-in, the courses and the course changes.
 */
internal class WatchItemPublisher<T>(private val snapshot: suspend () -> T, private val put: suspend (T) -> Unit) {
    private val mutex = Mutex()

    suspend fun publish() = mutex.withLock { put(snapshot()) }
}
