package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject

/** What the finder reads from the rest of the app, apart so the tests can stand it in. */
interface FinderEnvironment {
    /** Now on the app clock, in Taipei. */
    fun now(): LocalDateTime

    fun calendar(): AcademicCalendar

    /**
     * Emits when the academic calendar changes, as when it first arrives: until then every
     * weekday counts as a class day, so a day already read may turn out to be off.
     */
    val calendarChanged: Flow<Unit>

    /** The class table's courses for the term in session, without the ones the user deleted. */
    suspend fun courses(): List<Course>

    /**
     * Moves when a sync has written the timetable again, as the class table and Home hear it;
     * its current value is no change.
     */
    val coursesChanged: StateFlow<Int>

    /** The account signed in now, which a course read is checked against before it is shown. */
    fun account(): String?

    /** Null while no one is signed in. */
    fun credentials(): ClassroomUsageService.Credentials?

    val signedIn: StateFlow<Boolean>

    /** Whether the account [credentials] were read for is still the one signed in. */
    fun isCurrent(credentials: ClassroomUsageService.Credentials): Boolean

    /** The building the user last picked, kept across visits and dropped at sign-out. */
    var pickedBuilding: String?

    fun isOffline(error: Throwable): Boolean
}

/**
 * The academic calendar the finder reads. The demo account's week is no term's, so it is shown on
 * every weekday: a store reviewer signing in between terms or on a holiday still sees rooms.
 */
internal fun finderCalendar(isDemo: Boolean, current: () -> AcademicCalendar): AcademicCalendar =
    if (isDemo) AcademicCalendar.EMPTY else current()

class AppFinderEnvironment @Inject constructor(
    private val authService: AuthService,
    private val academicCalendar: AcademicCalendarStore,
    private val dataCache: DataCache,
    private val courseService: CourseService,
    private val prefs: AppPreferences,
    private val networkChecker: NetworkChecker,
    private val demoAccount: DemoAccount,
) : FinderEnvironment {
    override fun now(): LocalDateTime = AppClock.localDateTime(AppConstants.TAIPEI_ZONE)

    override fun calendar(): AcademicCalendar = finderCalendar(demoAccount.isActive, academicCalendar::current)

    override val calendarChanged: Flow<Unit> get() = academicCalendar.changes

    override val coursesChanged: StateFlow<Int> get() = dataCache.backgroundSyncVersion

    override fun account(): String? = authService.storedStudentId

    override suspend fun courses(): List<Course> {
        val hidden = CourseTombstoneKeys.hiddenIn(courseService.currentSemesterCode(), dataCache.loadDeletedCourseNos())
        return dataCache.loadCourses().filterNot { it.courseNo in hidden }
    }

    override fun credentials(): ClassroomUsageService.Credentials? {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId != null && password != null) return ClassroomUsageService.Credentials(studentId, password)
        // The demo account reads its rooms from the device, and a clean install photographed
        // in it may hold no credentials at all.
        return if (demoAccount.isActive) ClassroomUsageService.Credentials(studentId.orEmpty(), "") else null
    }

    override val signedIn: StateFlow<Boolean> get() = authService.authState

    override fun isCurrent(credentials: ClassroomUsageService.Credentials): Boolean =
        authService.authState.value && authService.storedStudentId.orEmpty() == credentials.studentId

    override var pickedBuilding: String?
        get() = prefs.emptyClassroomBuilding
        set(value) {
            prefs.emptyClassroomBuilding = value
        }

    override fun isOffline(error: Throwable): Boolean {
        if (!networkChecker.isAvailable()) return true
        val cause = (error as? SsoLoginError.NetworkError)?.cause_ ?: error
        return cause is UnknownHostException || cause is ConnectException ||
            cause is NoRouteToHostException || cause is SocketTimeoutException ||
            cause is InterruptedIOException
    }
}
