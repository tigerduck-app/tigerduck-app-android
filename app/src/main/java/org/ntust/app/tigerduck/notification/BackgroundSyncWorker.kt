package org.ntust.app.tigerduck.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authService: AuthService,
    private val moodleService: MoodleService,
    private val courseService: CourseService,
    private val dataCache: DataCache,
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val liveActivityManager: org.ntust.app.tigerduck.liveactivity.LiveActivityManager,
    private val prefs: org.ntust.app.tigerduck.data.preferences.AppPreferences,
    private val widgetUpdater: org.ntust.app.tigerduck.widget.WidgetUpdater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) return Result.success()

        val coursesOk = syncCourses(studentId, password)
        // The user may have logged out while the network call was in flight.
        // Bail before touching the cache or scheduling anything.
        if (authService.storedStudentId != studentId) return Result.success()

        val assignmentsOk = syncAssignments()
        if (authService.storedStudentId != studentId) return Result.success()

        liveActivityManager.refreshAndWait()
        widgetUpdater.updateAll()

        // Retry whenever either half failed: treating a partial failure as
        // success would silently drop the failing component until the next
        // hourly tick. WorkManager's exponential backoff is the right
        // recovery path for transient Moodle/NTUST outages.
        return if (coursesOk && assignmentsOk) Result.success() else Result.retry()
    }

    private suspend fun syncCourses(studentId: String, password: String): Boolean {
        return try {
            val semester = courseService.currentSemesterCode()
            val (selectionNos, moodleAll) = coroutineScope {
                val selectionDef = async {
                    try {
                        courseService.fetchEnrolledCourseNos(studentId, password)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch enrolled course numbers", e)
                        null
                    }
                }
                val moodleDef = async {
                    try {
                        moodleService.fetchEnrolledCourses()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch Moodle enrolled courses", e)
                        null
                    }
                }
                selectionDef.await() to moodleDef.await()
            }

            if (selectionNos == null && moodleAll == null) return false
            val moodleForSemester = moodleAll
                .orEmpty()
                .filter { it.semesterCode == semester && it.courseNo.isNotEmpty() }
            val moodleByNo = moodleForSemester.associateBy { it.courseNo }

            val orderedCourseNos = LinkedHashSet<String>().apply {
                selectionNos?.forEach { add(it) }
                moodleForSemester.forEach { add(it.courseNo) }
            }.toList()

            if (orderedCourseNos.isEmpty()) return true

            val fetched = coroutineScope {
                orderedCourseNos.map { courseNo ->
                    async {
                        try {
                            val results = courseService.lookupCourse(semester, courseNo)
                            if (results.isNotEmpty()) {
                                val r = results.first()
                                val schedule = courseService.mergeSchedules(
                                    *results.map { it.node }.toTypedArray()
                                )
                                Course.fromSchedule(
                                    courseNo = r.courseNo,
                                    courseName = r.courseName,
                                    instructor = r.courseTeacher,
                                    credits = r.creditPoint.toIntOrNull() ?: 0,
                                    classroom = r.classRoomNo ?: "",
                                    enrolledCount = r.chooseStudent ?: 0,
                                    maxCount = r.maxEnrollment,
                                    schedule = schedule,
                                    moodleIdNumber = moodleByNo[courseNo]?.idnumber ?: "${r.semester}${r.courseNo}"
                                )
                            } else {
                                fallbackCourseFromMoodle(courseNo, moodleByNo[courseNo])
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Course lookup failed for $courseNo", e)
                            fallbackCourseFromMoodle(courseNo, moodleByNo[courseNo])
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (fetched.isNotEmpty()) {
                // Preserve user-picked tile colors and manually-added courses
                // across the background refresh.
                val cached = dataCache.loadCourses()
                val cachedByNo = cached.associateBy { it.courseNo }
                val fetchedWithState = fetched.map { c ->
                    val prior = cachedByNo[c.courseNo]
                    c.copy(
                        customColorHex = prior?.customColorHex,
                        isManual = prior?.isManual == true,
                    )
                }
                val fetchedNos = fetchedWithState.map { it.courseNo }.toSet()
                val rosterNos = orderedCourseNos.toSet()
                val unresolvedNos = rosterNos - fetchedNos
                val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
                // Keep stale non-manual cache entries only for courses still in
                // this cycle's roster but unresolved due to transient lookup failures.
                val cachedRemoteFallbacks = cached.filter { !it.isManual && it.courseNo in unresolvedNos }
                val merged = fetchedWithState + manualLeftovers + cachedRemoteFallbacks
                dataCache.saveCourses(merged)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Course refresh failed", e)
            false
        }
    }

    private fun fallbackCourseFromMoodle(courseNo: String, moodle: MoodleEnrolledCourse?): Course? {
        moodle ?: return null
        return Course.fromSchedule(
            courseNo = courseNo,
            courseName = moodle.fullname ?: courseNo,
            moodleIdNumber = moodle.idnumber
        )
    }

    private suspend fun syncAssignments(): Boolean {
        return try {
            val enrolled = moodleService.fetchEnrolledCourses()
            val remote = moodleService.fetchAssignments(enrolled)
            val completed = dataCache.loadAssignments()
                .filter { it.isCompleted }
                .map { it.assignmentId }
                .toSet()
            val merged = remote.map { a ->
                if (a.assignmentId in completed) a.copy(isCompleted = true) else a
            }
            dataCache.saveAssignments(merged)

            if (prefs.notifyAssignments) {
                // Mirror HomeViewModel's filter so background re-scheduling
                // doesn't resurrect notifications the user manually silenced.
                val ignored = dataCache.loadIgnoredAssignments()
                val marked = dataCache.loadMarkedCompletedAssignments()
                notificationScheduler.scheduleAll(
                    merged.filter {
                        !it.isCompleted &&
                            it.assignmentId !in ignored &&
                            it.assignmentId !in marked
                    }
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Assignment refresh failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "BackgroundSyncWorker"
        // Keep the old unique name so existing users' enqueued 6h work is
        // replaced in place via ExistingPeriodicWorkPolicy.UPDATE instead of
        // leaving an orphaned entry behind.
        private const val UNIQUE_NAME = "homework_refresh_periodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
