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

        val assignmentsOk = syncAssignments(studentId)
        if (authService.storedStudentId != studentId) return Result.success()

        liveActivityManager.refreshAndWait()
        widgetUpdater.updateAll()

        return if (coursesOk || assignmentsOk) Result.success() else Result.retry()
    }

    private suspend fun syncCourses(studentId: String, password: String): Boolean {
        return try {
            val semester = courseService.currentSemesterCode()
            val courseNos = courseService.fetchEnrolledCourseNos(studentId, password)
            if (courseNos.isEmpty()) return true

            val fetched = coroutineScope {
                courseNos.map { courseNo ->
                    async {
                        try {
                            val results = courseService.lookupCourse(semester, courseNo)
                            results.firstOrNull()?.let { r ->
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
                                    moodleIdNumber = "${r.semester}${r.courseNo}"
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Course lookup failed for $courseNo", e)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (fetched.isNotEmpty()) {
                // Preserve user-picked tile colors and manually-added courses
                // across the background refresh.
                val cached = dataCache.loadCourses()
                val existingColors = cached.associate { it.courseNo to it.customColorHex }
                val fetchedWithColors = fetched.map { c ->
                    c.copy(customColorHex = existingColors[c.courseNo])
                }
                val fetchedNos = fetchedWithColors.map { it.courseNo }.toSet()
                val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
                val merged = fetchedWithColors + manualLeftovers
                dataCache.saveCourses(merged)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Course refresh failed", e)
            false
        }
    }

    private suspend fun syncAssignments(studentId: String): Boolean {
        return try {
            val enrolled = moodleService.fetchEnrolledCourses(studentId, authService.storedPassword.orEmpty())
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
                notificationScheduler.scheduleAll(merged.filter { !it.isCompleted })
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
