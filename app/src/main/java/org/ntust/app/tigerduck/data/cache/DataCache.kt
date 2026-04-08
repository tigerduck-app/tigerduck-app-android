package org.ntust.app.tigerduck.data.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.Course
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists network-fetched data to JSON files in cache dir.
 * Survives app restarts, cleared when system needs space.
 */
@Singleton
class DataCache @Inject constructor(@ApplicationContext context: Context) {

    private val cacheDir: File = File(context.cacheDir, "TigerDuckCache").also { it.mkdirs() }
    // User-generated state that has no remote source — stored in filesDir so the OS never evicts it.
    private val userDataDir: File = File(context.filesDir, "TigerDuckData").also { it.mkdirs() }
    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()

    // MARK: - Courses

    suspend fun saveCourses(courses: List<Course>) = save(courses, "courses.json")

    suspend fun loadCourses(): List<Course> {
        val type = object : TypeToken<List<Course>>() {}.type
        return load(type, "courses.json") ?: emptyList()
    }

    // MARK: - Assignments

    suspend fun saveAssignments(assignments: List<Assignment>) = save(assignments, "assignments.json")

    suspend fun loadAssignments(): List<Assignment> {
        val type = object : TypeToken<List<Assignment>>() {}.type
        return load(type, "assignments.json") ?: emptyList()
    }

    // MARK: - Skipped Dates (courseNo -> list of ISO date strings "yyyy-MM-dd")
    // Stored in filesDir — never cleared by the OS, unlike cacheDir.

    suspend fun saveSkippedDates(data: Map<String, List<String>>) = mutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                File(userDataDir, "skipped_dates.json").writeText(gson.toJson(data))
            } catch (e: Exception) { }
        }
    }

    suspend fun loadSkippedDates(): Map<String, List<String>> = mutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            try {
                val file = File(userDataDir, "skipped_dates.json")
                if (!file.exists()) return@withContext emptyMap()
                gson.fromJson(file.readText(), type) ?: emptyMap()
            } catch (e: Exception) { emptyMap() }
        }
    }

    // MARK: - Calendar Events

    suspend fun saveCalendarEvents(events: List<CalendarEvent>) = save(events, "calendar_events.json")

    suspend fun loadCalendarEvents(): List<CalendarEvent> {
        val type = object : TypeToken<List<CalendarEvent>>() {}.type
        return load(type, "calendar_events.json") ?: emptyList()
    }

    // MARK: - Private helpers

    private suspend fun <T> save(value: T, filename: String) = mutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                File(cacheDir, filename).writeText(gson.toJson(value))
            } catch (e: Exception) {
                // Ignore write errors
            }
        }
    }

    private suspend fun <T> load(type: java.lang.reflect.Type, filename: String): T? = mutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = File(cacheDir, filename)
                if (!file.exists()) return@withContext null
                gson.fromJson(file.readText(), type)
            } catch (e: Exception) {
                null
            }
        }
    }
}
