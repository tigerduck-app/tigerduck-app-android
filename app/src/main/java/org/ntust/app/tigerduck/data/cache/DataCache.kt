package org.ntust.app.tigerduck.data.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.Course
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    // MARK: - Courses

    fun saveCourses(courses: List<Course>) = save(courses, "courses.json")

    fun loadCourses(): List<Course> {
        val type = object : TypeToken<List<Course>>() {}.type
        return load(type, "courses.json") ?: emptyList()
    }

    // MARK: - Assignments

    fun saveAssignments(assignments: List<Assignment>) = save(assignments, "assignments.json")

    fun loadAssignments(): List<Assignment> {
        val type = object : TypeToken<List<Assignment>>() {}.type
        return load(type, "assignments.json") ?: emptyList()
    }

    // MARK: - Calendar Events

    fun saveCalendarEvents(events: List<CalendarEvent>) = save(events, "calendar_events.json")

    fun loadCalendarEvents(): List<CalendarEvent> {
        val type = object : TypeToken<List<CalendarEvent>>() {}.type
        return load(type, "calendar_events.json") ?: emptyList()
    }

    // MARK: - Private helpers

    private fun <T> save(value: T, filename: String) {
        try {
            File(cacheDir, filename).writeText(gson.toJson(value))
        } catch (e: Exception) {
            // Ignore write errors
        }
    }

    private fun <T> load(type: java.lang.reflect.Type, filename: String): T? {
        return try {
            val file = File(cacheDir, filename)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            null
        }
    }
}
