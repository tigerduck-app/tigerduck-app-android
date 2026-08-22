package org.ntust.app.tigerduck.push

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Maps local identifiers (semester|courseKey, moodleCourseId:moodleAssignmentId)
 * to server-side numeric IDs. Rebuilt on each full sync; used by the outbox to
 * resolve IDs before sending targeted PATCH requests.
 *
 * Persisted to SharedPreferences as JSON.
 */
class SyncIdMap(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // "semester|courseKey" -> serverId
    private var courses: MutableMap<String, Int> = mutableMapOf()

    // "moodleCourseId:moodleAssignmentId" -> serverId
    private var assignments: MutableMap<String, Int> = mutableMapOf()

    init {
        load()
    }

    fun recordCourse(semester: String, courseKey: String, serverId: Int) {
        courses["$semester|$courseKey"] = serverId
        persist()
    }

    fun recordAssignment(moodleCourseId: Int, moodleAssignmentId: Int, serverId: Int) {
        assignments["$moodleCourseId:$moodleAssignmentId"] = serverId
        persist()
    }

    fun courseId(semester: String, courseKey: String): Int? =
        courses["$semester|$courseKey"]

    fun assignmentId(moodleCourseId: Int, moodleAssignmentId: Int): Int? =
        assignments["$moodleCourseId:$moodleAssignmentId"]

    /**
     * Replace the entire map contents from a full sync response.
     * Does NOT persist — call [persist] explicitly after rebuilding.
     */
    fun rebuild(
        newCourses: Map<String, Int>,
        newAssignments: Map<String, Int>,
    ) {
        courses = newCourses.toMutableMap()
        assignments = newAssignments.toMutableMap()
        persist()
    }

    fun clear() {
        courses.clear()
        assignments.clear()
        prefs.edit().clear().apply()
    }

    fun load() {
        courses = loadMap(KEY_COURSES)
        assignments = loadMap(KEY_ASSIGNMENTS)
    }

    private fun persist() {
        val editor = prefs.edit()
        editor.putString(KEY_COURSES, gson.toJson(courses))
        editor.putString(KEY_ASSIGNMENTS, gson.toJson(assignments))
        editor.apply()
    }

    private fun loadMap(key: String): MutableMap<String, Int> {
        val json = prefs.getString(key, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson<MutableMap<String, Int>>(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.w(TAG, "$key decode failed — starting empty", e)
            mutableMapOf()
        }
    }

    companion object {
        private const val TAG = "CloudSync.IdMap"
        private const val PREFS_NAME = "cloud_sync_id_map"
        private const val KEY_COURSES = "courses"
        private const val KEY_ASSIGNMENTS = "assignments"
    }
}
