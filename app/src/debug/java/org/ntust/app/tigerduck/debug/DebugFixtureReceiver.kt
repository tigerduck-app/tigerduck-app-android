package org.ntust.app.tigerduck.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.shared.Course
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * Loads a made-up timetable into the app over `adb shell am broadcast`, so
 * store screenshots don't have to show a real student's real enrolment.
 *
 * The fixture arrives as a hand-written JSON file pushed to the device. It is
 * deliberately *not* the on-disk cache format: this receiver reads the
 * friendly shape and builds real [Course] / [Assignment] objects, then hands
 * them to [DataCache], so the bytes that land in `courses_<semester>.json`
 * are produced by the app's own Gson and its own model classes. Writing that
 * file directly from a script would mean re-deriving a format that has
 * already caused two shipped upgrade crashes — including the `courseNo`
 * sentinel that [DataCache.loadCourses] rejects a file for missing.
 *
 * **Debug builds only.** Declared in `app/src/debug/AndroidManifest.xml`, so
 * no such component exists in `playRelease` / `fdroidRelease`. Exported for
 * the same reason [DebugClockReceiver] is: a broadcast from the `shell` uid
 * cannot reach an unexported receiver.
 */
class DebugFixtureReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun dataCache(): DataCache
        fun bulletinCache(): BulletinCache
        fun fixtureStore(): DebugFixtureStore
        @ApplicationScope
        fun appScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors
            .fromApplication(context.applicationContext, Deps::class.java)

        when (intent.action) {
            ACTION_CLEAR -> {
                deps.fixtureStore().clear()
                // Putting the real courses back is a cache write, so this
                // needs goAsync for the same reason LOAD does.
                val pending = goAsync()
                deps.appScope().launch {
                    try {
                        restoreStashedCourses(context, deps)
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to restore stashed courses", e)
                    } finally {
                        pending.finish()
                    }
                }
                Log.i(TAG, "cleared display overrides; sync or pull-to-refresh to restore real data")
            }

            ACTION_LOAD -> {
                val path = intent.getStringExtra(EXTRA_FILE)
                if (path == null) {
                    Log.e(TAG, "ignoring LOAD: pass --es $EXTRA_FILE <path to fixture json on device>")
                    return
                }
                val file = File(path)
                if (!file.isFile) {
                    Log.e(TAG, "ignoring LOAD: no such file $path")
                    return
                }
                val root = try {
                    JSONObject(file.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "ignoring LOAD: $path is not valid JSON", e)
                    return
                }
                // goAsync keeps the receiver alive across the cache writes;
                // without it the process can be killed the moment onReceive
                // returns, leaving a half-written courses file behind.
                val pending = goAsync()
                deps.appScope().launch {
                    try {
                        applyFixture(context, deps, root, resolveLang(context, intent))
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to apply fixture", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    private suspend fun applyFixture(
        context: Context,
        deps: Deps,
        root: JSONObject,
        lang: String,
    ) {
        val store = deps.fixtureStore()

        // Set before anything else: the rest of this method writes caches
        // that a live sync would race, and demo mode is what stops the sync.
        // It only truly takes hold on the next process, which is why the
        // script force-stops the app -- see DebugFixtureStore.demoMode.
        store.demoMode = root.optBoolean(KEY_DEMO_MODE, false)
        if (store.demoMode) {
            Log.i(TAG, "demo mode ON — every server is refused; restart the app for it to take hold")
        }

        store.studentIdOverride = root.optString(KEY_STUDENT_ID).takeIf { it.isNotBlank() }
        root.optJSONObject(KEY_LIBRARY_QR)?.let { qr ->
            store.libraryQrContent = qr.optString("content").takeIf { it.isNotBlank() }
            store.libraryFakeSignedIn = qr.optBoolean("fakeLoggedIn", true)
        }

        val cache = deps.dataCache()

        root.optJSONArray(KEY_COURSES)?.let { array ->
            val fixture = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseCourse(it, lang) }
            }
            // The fixture replaces the term outright, manual rows included.
            //
            // Carrying them through was meant to protect courses the user had
            // typed in, but `isManual` does not mean that on a synced device:
            // CourseSyncReconciler stamps it on every row it merges down from
            // another device, so on a phone with cloud sync on nearly every
            // "manual" course is a real enrolment. Keeping them put the real
            // timetable in the store screenshot next to the made-up one,
            // which is the one thing this fixture exists to prevent.
            //
            // Manual rows live in a durable file no fetch can rebuild, so they
            // are moved aside rather than dropped, and CLEAR_FIXTURE puts them
            // back. Fetched rows are not stashed: a refresh rebuilds those,
            // which is what made them safe to overwrite in the first place.
            stashRealCoursesOnce(context, cache)
            cache.saveCourses(fixture)
            Log.i(TAG, "loaded ${fixture.size} fixture course(s); CLEAR_FIXTURE restores the real ones")
        }

        root.optJSONArray(KEY_ASSIGNMENTS)?.let { array ->
            val assignments = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseAssignment(it, lang) }
            }
            cache.saveAssignments(assignments)
            Log.i(TAG, "loaded ${assignments.size} fixture assignment(s)")
        }

        root.optJSONArray(KEY_BULLETINS)?.let { array ->
            val bulletins = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseBulletin(it, i) }
            }
            deps.bulletinCache().save(bulletins)
            Log.i(TAG, "loaded ${bulletins.size} fixture bulletin(s)")
        }

        root.optJSONArray(KEY_CALENDAR)?.let { array ->
            val events = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseCalendarEvent(it, i) }
            }
            cache.saveCalendarEvents(events)
            Log.i(TAG, "loaded ${events.size} fixture calendar event(s)")
        }

        Log.i(TAG, "fixture applied")
    }

    /**
     * Move the real hand-added courses aside, once.
     *
     * Written only when there is no stash yet, so loading a second fixture
     * over the first cannot overwrite the real courses with fixture rows —
     * by then [DataCache] holds the first fixture, not the roster we want to
     * be able to give back.
     *
     * Both this and [restoreStashedCourses] go through the no-arg
     * [DataCache] aliases, so both resolve the same semester as the fixture
     * itself. Move the debug clock across a term boundary between LOAD and
     * CLEAR and the restore lands in the term the clock now names; that is
     * the only way the two can disagree, and it costs a pull-to-refresh.
     */
    private suspend fun stashRealCoursesOnce(context: Context, cache: DataCache) {
        val stash = stashFile(context)
        // An *empty* stash must not block a later real one. Reloading a
        // fixture over a fixture reads back no manual courses, so the
        // write-once rule is what stops that emptiness overwriting the real
        // roster; but a first load on a device that genuinely had none also
        // writes empty, and by the next load a sync may have brought real
        // ones back. Only a stash with something in it is worth keeping.
        if (stash.exists() && stash.length() > EMPTY_JSON_ARRAY_LENGTH) return
        val manual = cache.loadCourses().filter { it.isManual }
        if (manual.isEmpty() && stash.exists()) return
        runCatching { stash.writeText(Gson().toJson(manual)) }
            .onFailure { Log.e(TAG, "could not stash real courses — not replacing them", it); throw it }
        Log.i(TAG, "stashed ${manual.size} real course(s) — CLEAR_FIXTURE puts them back")
    }

    /** Put back whatever [stashRealCoursesOnce] moved aside, then forget it. */
    private suspend fun restoreStashedCourses(context: Context, deps: Deps) {
        val stash = stashFile(context)
        if (!stash.exists()) return
        val type = object : TypeToken<List<Course>>() {}.type
        val courses = runCatching { Gson().fromJson<List<Course>>(stash.readText(), type) }
            .getOrElse {
                Log.e(TAG, "stash at ${stash.path} is unreadable — leaving it in place", it)
                return
            } ?: emptyList()
        deps.dataCache().saveCourses(courses)
        stash.delete()
        Log.i(TAG, "restored ${courses.size} real course(s); pull to refresh for the fetched ones")
    }

    private fun stashFile(context: Context) = File(context.filesDir, STASH_FILE)

    /**
     * Built through [Course.fromSchedule] rather than the constructor so the
     * weekday/period map is encoded to `scheduleJson` exactly the way the
     * network layer encodes it.
     */
    /**
     * Which language variant to read out of the fixture.
     *
     * Resolved once, here, because the fixture is written into the *cache*:
     * what lands in `courses_<semester>.json` is a plain string, so switching
     * the app's language afterwards cannot retranslate it. Reload the fixture
     * after a language switch. `--es lang zh|en` overrides the app's current
     * locale, which is what the screenshot script passes when it walks both
     * languages in one sitting.
     */
    private fun resolveLang(context: Context, intent: Intent): String {
        intent.getStringExtra(EXTRA_LANG)?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        return if (context.resources.configuration.locales[0].language == "zh") LANG_ZH else LANG_EN
    }

    /**
     * Reads a field that is either a plain string or a `{"zh": …, "en": …}`
     * pair, so a fixture only needs the two-language shape where it actually
     * has two versions of the text.
     *
     * Missing the requested language falls back to the other one rather than
     * to an empty string: a blank course name in a screenshot is worse than
     * one in the wrong language, and the wrong language is obvious on sight
     * while a blank cell just looks like a bug in the app.
     */
    private fun localized(json: JSONObject, key: String, lang: String): String {
        val pair = json.optJSONObject(key) ?: return json.optString(key)
        pair.optString(lang).takeIf { it.isNotBlank() }?.let { return it }
        return pair.optString(if (lang == LANG_ZH) LANG_EN else LANG_ZH)
    }

    private fun parseCourse(json: JSONObject, lang: String): Course? {
        val courseNo = json.optString("courseNo").takeIf { it.isNotBlank() } ?: run {
            Log.e(TAG, "skipping course with no courseNo: $json")
            return null
        }
        val schedule = mutableMapOf<Int, List<String>>()
        json.optJSONObject("schedule")?.let { obj ->
            for (key in obj.keys()) {
                val weekday = key.toIntOrNull() ?: continue
                val periods = obj.optJSONArray(key) ?: continue
                schedule[weekday] = (0 until periods.length()).map { periods.optString(it) }
            }
        }
        val classroomMap = mutableMapOf<String, String>()
        json.optJSONObject("classroomMap")?.let { obj ->
            for (key in obj.keys()) classroomMap[key] = obj.optString(key)
        }
        val course = Course.fromSchedule(
            courseNo = courseNo,
            courseName = localized(json, "name", lang),
            instructor = localized(json, "instructor", lang),
            credits = json.optInt("credits", 0),
            classroom = json.optString("classroom"),
            schedule = schedule,
            classroomMap = classroomMap,
        )
        return course.copy(
            customColorHex = json.optString("colorHex").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Assignment text runs through [localized] as well, so a fixture *may*
     * carry both languages — but the shipped example is English-only, which
     * is what the store listing wants.
     */
    private fun parseAssignment(json: JSONObject, lang: String): Assignment? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: run {
            Log.e(TAG, "skipping assignment with no id: $json")
            return null
        }
        val due = parseLocal(json.optString("due")) ?: run {
            Log.e(TAG, "skipping assignment $id: 'due' is not $AT_FORMAT_HINT")
            return null
        }
        return Assignment(
            assignmentId = id,
            courseNo = json.optString("courseNo"),
            courseName = localized(json, "courseName", lang),
            title = localized(json, "title", lang),
            dueDate = due,
            isCompleted = json.optBoolean("completed", false),
        )
    }

    /**
     * The list shape only. A bulletin's detail page is fetched separately and
     * is not worth faking: with demo mode on that fetch fails, and the screen
     * that matters for the store listing is the list.
     *
     * [index] backs the id when the fixture omits one, so hand-written entries
     * do not have to carry unique numbers to avoid colliding in the cache.
     */
    private fun parseBulletin(json: JSONObject, index: Int): BulletinSummary {
        val id = json.optInt("id", index + 1)
        return BulletinSummary(
            id = id,
            externalId = json.optString("externalId").takeIf { it.isNotBlank() } ?: "demo-$id",
            title = json.optString("title"),
            titleClean = json.optString("title").takeIf { it.isNotBlank() },
            canonicalOrg = json.optString("org").takeIf { it.isNotBlank() },
            contentTags = json.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).map { tags.optString(it) }
            } ?: emptyList(),
            importance = json.optString("importance").takeIf { it.isNotBlank() },
            summary = json.optString("summary").takeIf { it.isNotBlank() },
            sourceUrl = json.optString("sourceUrl").takeIf { it.isNotBlank() },
            postedAt = json.optString("postedAt").takeIf { it.isNotBlank() },
        )
    }

    private fun parseCalendarEvent(json: JSONObject, index: Int): CalendarEvent? {
        val date = parseLocal(json.optString("date")) ?: run {
            Log.e(TAG, "skipping calendar event ${index + 1}: 'date' is not $AT_FORMAT_HINT or a plain date")
            return null
        }
        return CalendarEvent(
            eventId = json.optString("id").takeIf { it.isNotBlank() } ?: "demo-cal-${index + 1}",
            title = json.optString("title"),
            date = date,
            // Anything else renders as a generic school event, which is the
            // right fallback for a typo in a hand-written fixture.
            sourceRaw = json.optString("source").takeIf { it.isNotBlank() } ?: "school",
        )
    }

    /** Same shapes [DebugClockReceiver] accepts, for the same reason. */
    private fun parseLocal(raw: String): Date? {
        val normalized = raw.trim().replace(' ', 'T')
        if (normalized.isEmpty()) return null
        val local = try {
            LocalDateTime.parse(normalized)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(normalized).atStartOfDay()
            } catch (_: DateTimeParseException) {
                return null
            }
        }
        return Date(local.atZone(ZONE).toInstant().toEpochMilli())
    }

    private companion object {
        const val TAG = "DebugFixture"
        const val ACTION_LOAD = "org.ntust.app.tigerduck.debug.LOAD_FIXTURE"
        const val ACTION_CLEAR = "org.ntust.app.tigerduck.debug.CLEAR_FIXTURE"
        const val EXTRA_FILE = "file"
        const val EXTRA_LANG = "lang"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"
        const val KEY_STUDENT_ID = "studentId"
        const val KEY_LIBRARY_QR = "libraryQr"
        const val KEY_COURSES = "courses"
        const val KEY_ASSIGNMENTS = "assignments"
        const val KEY_BULLETINS = "bulletins"
        const val KEY_CALENDAR = "calendar"
        const val KEY_DEMO_MODE = "demoMode"
        const val AT_FORMAT_HINT = "2026-09-15T23:59"

        /**
         * Debug-only, so deliberately outside `DataCache`'s directories and
         * outside `clearAllUserData`: a logout mid-screenshot-session must
         * not be what decides whether the real timetable comes back.
         */
        const val STASH_FILE = "debug_fixture_stashed_courses.json"

        /** `[]` — the length below which a stash is holding nothing. */
        const val EMPTY_JSON_ARRAY_LENGTH = 2L

        val ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    }
}
