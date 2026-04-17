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
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.MoodleService
import java.util.concurrent.TimeUnit

@HiltWorker
class HomeworkRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authService: AuthService,
    private val moodleService: MoodleService,
    private val dataCache: DataCache,
    private val notificationScheduler: AssignmentNotificationScheduler,
    private val liveActivityManager: org.ntust.app.tigerduck.liveactivity.LiveActivityManager,
    private val prefs: org.ntust.app.tigerduck.data.preferences.AppPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val studentId = authService.storedStudentId
        val password = authService.storedPassword
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) return Result.success()

        return try {
            val remote = moodleService.fetchAssignments(studentId, password)
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
            liveActivityManager.refresh()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Homework refresh failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HomeworkRefreshWorker"
        private const val UNIQUE_NAME = "homework_refresh_periodic"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HomeworkRefreshWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
