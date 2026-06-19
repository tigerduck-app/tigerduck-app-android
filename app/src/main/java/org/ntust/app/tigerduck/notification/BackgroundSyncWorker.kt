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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import java.util.concurrent.TimeUnit

enum class SyncSource { NONE, BACKEND, LOCAL }

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
    private val syncApiClient: org.ntust.app.tigerduck.push.SyncApiClient,
    private val pushApiClient: org.ntust.app.tigerduck.push.PushApiClient,
) : CoroutineWorker(context, params) {

    var lastSyncSource: SyncSource = SyncSource.NONE
        private set

    override suspend fun doWork(): Result {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) return Result.success()

        // Moodle-direct for assignments/courses, backend for override sync.
        syncOverridesFromBackend()

        val coursesOk = syncCourses(studentId, password)
        if (authService.storedStudentId != studentId) return Result.success()
        val assignmentsOk = syncAssignments()
        if (authService.storedStudentId != studentId) return Result.success()

        liveActivityManager.refreshAndWait()
        widgetUpdater.updateAll()

        return if (coursesOk && assignmentsOk) Result.success() else Result.retry()
    }

    private suspend fun syncOverridesFromBackend() {
        if (!prefs.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) return
        try {
            val result = syncApiClient.fetchFullSync()
            val localIgnored = dataCache.loadIgnoredAssignments()
            val localMarked = dataCache.loadMarkedCompletedAssignments()
            val isFirstTimeMigration = result.ignoredIds.isEmpty() && result.completedIds.isEmpty()
                && (localIgnored.isNotEmpty() || localMarked.isNotEmpty())
            if (!isFirstTimeMigration) {
                dataCache.replaceIgnoredAssignments(result.ignoredIds)
                dataCache.replaceMarkedCompletedAssignments(result.completedIds)
                Log.d(TAG, "override sync: ${result.ignoredIds.size} ignored, ${result.completedIds.size} completed")
            }

            if (result.courseOverrides.isNotEmpty()) {
                applyCourseOverridesBackground(result.courseOverrides)
            }
            // Hard-delete model: courses removed on the server are absent from
            // the courses array. Compare against local to update deletedCourseNos.
            if (result.serverCourseNos.isNotEmpty()) {
                val localCourseNos = dataCache.loadCourses().map { it.courseNo }.toSet()
                val deleted = dataCache.loadDeletedCourseNos().toMutableSet()
                val sizeBefore = deleted.size
                for (no in localCourseNos) {
                    if (no !in result.serverCourseNos) {
                        deleted.add(no)
                    }
                }
                deleted.removeAll { it in result.serverCourseNos }
                if (deleted.size != sizeBefore || deleted != dataCache.loadDeletedCourseNos()) {
                    dataCache.saveDeletedCourseNos(deleted)
                    Log.d(TAG, "deletedCourseNos updated: $deleted")
                }
            }
            dataCache.notifyBackgroundSyncComplete()
            Log.d(TAG, "backgroundSyncVersion incremented")
        } catch (e: Exception) {
            Log.w(TAG, "override sync failed", e)
        }
    }

    private suspend fun applyCourseOverridesBackground(overrides: List<org.ntust.app.tigerduck.push.CourseOverrideResult>) {
        val courses = dataCache.loadCourses()
        var changed = false
        val updated = courses.map { course ->
            val override = overrides.find { it.courseNo == course.courseNo }
                ?: return@map course
            val newHex = override.colorHex
            if (newHex != course.customColorHex) {
                changed = true
                Log.d(TAG, "course ${course.courseNo}: color → $newHex")
                course.copy(customColorHex = newHex)
            } else course
        }
        if (changed) {
            dataCache.saveCourses(updated)
            Log.d(TAG, "course overrides applied: colors updated")
        }
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
                                val classroomMap = courseService.buildClassroomMap(results)
                                val allRooms = LinkedHashSet<String>().apply {
                                    for (row in results) {
                                        Course.splitRooms(row.classRoomNo ?: "")
                                            .forEach { add(it) }
                                    }
                                }
                                Course.fromSchedule(
                                    courseNo = r.courseNo,
                                    courseName = r.courseName,
                                    instructor = r.courseTeacher,
                                    credits = r.creditPoint.toIntOrNull() ?: 0,
                                    classroom = allRooms.joinToString(", "),
                                    enrolledCount = r.chooseStudent ?: 0,
                                    maxCount = r.maxEnrollment,
                                    schedule = schedule,
                                    classroomMap = classroomMap,
                                    moodleIdNumber = moodleByNo[courseNo]?.idnumber
                                        ?: "${r.semester}${r.courseNo}"
                                )
                            } else {
                                CourseService.fallbackCourseFromMoodle(
                                    courseNo,
                                    moodleByNo[courseNo]
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Course lookup failed for $courseNo", e)
                            CourseService.fallbackCourseFromMoodle(courseNo, moodleByNo[courseNo])
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (fetched.isNotEmpty()) {
                // Preserve user-picked tile colors and manually-added courses
                // across the background refresh.
                val cached = dataCache.loadCourses()
                val deletedNos = dataCache.loadDeletedCourseNos()
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
                val cachedRemoteFallbacks =
                    cached.filter { !it.isManual && it.courseNo in unresolvedNos }
                val merged = (fetchedWithState + manualLeftovers + cachedRemoteFallbacks)
                    .filter { it.courseNo !in deletedNos }
                dataCache.saveCourses(merged)
                if (prefs.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                    runCatching { pushApiClient.uploadCourses(merged, semester) }
                        .onFailure { Log.w(TAG, "uploadCourses failed (non-fatal)", it) }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Course refresh failed", e)
            false
        }
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
            if (prefs.cloudSyncEnabled && !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                runCatching { pushApiClient.uploadAssignments(merged) }
                    .onFailure { Log.w(TAG, "uploadAssignments failed (non-fatal)", it) }
            }

            if (prefs.notifyAssignments) {
                // Hand the scheduler both the full non-completed list and the
                // dismissed set; it routes ignored/marked ids to the safety-
                // net reminder body instead of dropping them.
                val ignored = dataCache.loadIgnoredAssignments()
                val marked = dataCache.loadMarkedCompletedAssignments()
                notificationScheduler.scheduleAll(
                    merged.filter { !it.isCompleted },
                    ignored + marked,
                    prefs.notifyAssignmentOffsets,
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
