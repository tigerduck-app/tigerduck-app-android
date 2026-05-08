package org.ntust.app.tigerduck.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearScheduleBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val appPreferences: AppPreferences,
) {
    private val gson = Gson()

    /**
     * Publishes the current course list, accent color, and login state to the
     * watch via DataClient. Safe to call repeatedly — same payload no-ops.
     */
    suspend fun publish() {
        val loggedIn = authService.authState.value
        val courses = if (loggedIn) {
            dataCache.loadCourses()
        } else {
            // On logout, explicitly clear the watch's view.
            emptyList()
        }
        val accentHex = "#%06X".format(0xFFFFFF and appPreferences.accentColorHex)
        val payload = gson.toJson(courses.map { it.toDto() }).toByteArray()
        val gzipped = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(payload) }
        }.toByteArray()

        // Resolve "system" to the concrete tag the watch should mirror — the
        // watch can't observe the phone's system locale directly, so we send
        // the resolved BCP-47 tag instead of the literal "system" sentinel.
        val rawLanguage = appPreferences.appLanguage
        val languageTag = if (rawLanguage == AppLanguageManager.SYSTEM) {
            AppLanguageManager.resolveExplicitLocale(AppLanguageManager.SYSTEM)?.toLanguageTag()
                ?: AppLanguageManager.resolvedSystemLanguage()
        } else {
            rawLanguage
        }

        val request = PutDataMapRequest.create(SCHEDULE_PATH).apply {
            dataMap.putByteArray(KEY_COURSES, gzipped)
            dataMap.putString(KEY_ACCENT, accentHex)
            dataMap.putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            dataMap.putBoolean(KEY_LOGGED_IN, loggedIn)
            dataMap.putString(KEY_LANGUAGE, languageTag)
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "publish ok: ${courses.size} courses, ${gzipped.size} bytes")
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed: ${t.message}")
        }
    }

    private fun Course.toDto(): CourseDto = CourseDto(
        courseNo = courseNo,
        courseName = courseName,
        instructor = instructor,
        credits = credits,
        classroom = classroom,
        scheduleJson = scheduleJson,
        moodleIdNumber = moodleIdNumber,
        customColorHex = customColorHex,
        isManual = isManual,
    )

    /** On-the-wire shape; explicit so we can evolve [Course] without breaking watch JSON. */
    data class CourseDto(
        val courseNo: String,
        val courseName: String,
        val instructor: String,
        val credits: Int,
        val classroom: String,
        val scheduleJson: String,
        val moodleIdNumber: String?,
        val customColorHex: String?,
        val isManual: Boolean,
    )

    companion object {
        const val SCHEDULE_PATH = "/tigerduck/schedule"
        const val KEY_COURSES = "courses"
        const val KEY_ACCENT = "accentHex"
        const val KEY_SYNCED_AT = "syncedAtMs"
        const val KEY_LOGGED_IN = "loggedIn"
        const val KEY_LANGUAGE = "languageTag"
        private const val TAG = "WearBridge"
    }
}
