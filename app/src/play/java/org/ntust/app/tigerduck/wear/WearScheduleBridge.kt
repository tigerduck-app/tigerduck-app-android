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
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.WearProtocol
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearScheduleBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val appPreferences: AppPreferences,
    private val credentials: CredentialManager,
) {
    private val gson = Gson()
    private val libraryCredentialsVersion = AtomicLong(0L)

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
        // `resolveExplicitLocale(SYSTEM)` is documented to return null, so go
        // straight to `resolvedSystemLanguage()` for the SYSTEM branch.
        val rawLanguage = appPreferences.appLanguage
        val languageTag = if (rawLanguage == AppLanguageManager.SYSTEM) {
            AppLanguageManager.resolvedSystemLanguage()
        } else {
            rawLanguage
        }

        val request = PutDataMapRequest.create(WearProtocol.Schedule.PATH).apply {
            dataMap.putByteArray(WearProtocol.Schedule.KEY_COURSES, gzipped)
            dataMap.putString(WearProtocol.Schedule.KEY_ACCENT, accentHex)
            dataMap.putLong(WearProtocol.Schedule.KEY_SYNCED_AT, System.currentTimeMillis())
            dataMap.putBoolean(WearProtocol.Schedule.KEY_LOGGED_IN, loggedIn)
            dataMap.putString(WearProtocol.Schedule.KEY_LANGUAGE, languageTag)
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "publish ok: ${courses.size} courses, ${gzipped.size} bytes")
        } catch (t: Throwable) {
            Log.w(TAG, "publish failed: ${t.message}")
        }
    }

    /**
     * Push the current library credentials to the watch over the Data Layer so
     * it can independently call `api.lib.ntust.edu.tw` for the rotating QR.
     * Sends a `hasCredentials=false` packet when the user is logged out so the
     * watch wipes its local copy rather than holding stale creds.
     */
    suspend fun publishLibraryCredentials() {
        val username = credentials.libraryUsername
        val password = credentials.libraryPassword
        val hasCredentials = username != null && password != null

        val request = PutDataMapRequest.create(WearProtocol.LibraryCredentials.PATH).apply {
            dataMap.putBoolean(WearProtocol.LibraryCredentials.KEY_HAS_CREDENTIALS, hasCredentials)
            // Always include the version counter so two pushes with identical
            // payload still propagate (DataClient de-dupes by content).
            dataMap.putLong(
                WearProtocol.LibraryCredentials.KEY_VERSION,
                libraryCredentialsVersion.incrementAndGet(),
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
            }
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "library credentials publish ok (has=$hasCredentials)")
        } catch (t: Throwable) {
            Log.w(TAG, "library credentials publish failed: ${t.message}")
        }
    }

    private fun Course.toDto(): CourseDto = CourseDto(
        courseNo = courseNo,
        // Send the resolved label so any user-set customCourseName survives the
        // round-trip. The watch-side wire schema has no customCourseName field,
        // so baking the override into courseName is the lightest fix.
        courseName = displayName,
        instructor = instructor,
        credits = credits,
        classroom = classroom,
        scheduleJson = scheduleJson,
        classroomMapJson = classroomMapJson,
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
        val classroomMapJson: String = "{}",
        val moodleIdNumber: String?,
        val customColorHex: String?,
        val isManual: Boolean,
    )

    private companion object {
        const val TAG = "WearBridge"
    }
}
