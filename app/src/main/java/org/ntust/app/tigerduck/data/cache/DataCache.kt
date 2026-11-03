package org.ntust.app.tigerduck.data.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.RecordedSelection
import org.ntust.app.tigerduck.data.SelectionAnswer
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.shared.clock.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.model.SyncConflict
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists network-fetched data to JSON files in cache dir.
 * Survives app restarts, cleared when system needs space.
 */
@Singleton
class DataCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val academicCalendar: AcademicCalendarStore,
) : org.ntust.app.tigerduck.auth.session.LateAccountScoped {

    private val cacheDir: File = File(context.cacheDir, "TigerDuckCache").also { it.mkdirs() }

    // User-generated state that has no remote source — stored in filesDir so the OS never evicts it.
    private val userDataDir: File = File(context.filesDir, "TigerDuckData").also { it.mkdirs() }
    private val cacheMutex = Mutex()

    private val _syncConflict = MutableStateFlow<SyncConflict?>(null)
    val syncConflict: StateFlow<SyncConflict?> = _syncConflict.asStateFlow()

    fun setSyncConflict(conflict: SyncConflict?) { _syncConflict.value = conflict }

    private val _backgroundSyncVersion = MutableStateFlow(0)
    val backgroundSyncVersion: StateFlow<Int> = _backgroundSyncVersion.asStateFlow()
    fun notifyBackgroundSyncComplete() { _backgroundSyncVersion.update { it + 1 } }

    private val _coursesVersion = MutableStateFlow(0)

    /**
     * Bumped after every write of a term's courses, for a reader of terms other than the one on
     * screen, such as the import scanner's share button, to read them again as other terms load.
     */
    val coursesVersion: StateFlow<Int> = _coursesVersion.asStateFlow()

    private val _assignmentsVersion = MutableStateFlow(0)

    /**
     * Bumped after every write of the cached assignments, for a screen that shows one of them
     * beside Home, such as an assignment's page, to read it again. Home itself follows
     * [backgroundSyncVersion], which a round's writer bumps once its whole round is in.
     */
    val assignmentsVersion: StateFlow<Int> = _assignmentsVersion.asStateFlow()

    private val _assignmentRecordsVersion = MutableStateFlow(0)

    /** Bumped after every write of [saveAssignmentRecords]. */
    val assignmentRecordsVersion: StateFlow<Int> = _assignmentRecordsVersion.asStateFlow()

    private val _assignmentMarksVersion = MutableStateFlow(0)

    /**
     * Bumped after every write of the ignored, marked-done and ignored-course sets, so a screen that only reads them reads them again.
     */
    val assignmentMarksVersion: StateFlow<Int> = _assignmentMarksVersion.asStateFlow()

    private val _marksAdoptedVersion = MutableStateFlow(0)

    /**
     * Bumped after [adoptAssignmentMarks], for Home to read the marks again before its next swipe writes the ones it holds back over them.
     */
    val marksAdoptedVersion: StateFlow<Int> = _marksAdoptedVersion.asStateFlow()

    /**
     * Replaces the ignored and marked-done sets with the ones a re-enable answer took (Use Server), and
     * says so on [marksAdoptedVersion]. Not for Home's own writes: reading those back could undo a newer swipe.
     */
    suspend fun adoptAssignmentMarks(ignored: Set<String>, completed: Set<String>, signIn: SignIn) {
        val ignoredKept = saveIgnoredAssignments(ignored, signIn)
        if (saveMarkedCompletedAssignments(completed, signIn) or ignoredKept) _marksAdoptedVersion.update { it + 1 }
    }

    suspend fun replaceIgnoredAssignments(ids: Set<String>, signIn: SignIn) = saveIgnoredAssignments(ids, signIn)

    suspend fun replaceMarkedCompletedAssignments(ids: Set<String>, signIn: SignIn) =
        saveMarkedCompletedAssignments(ids, signIn)

    /**
     * Writes the ignored and marked-done sets as [change] makes them of the sets as stored and the marks
     * waiting to go up, read in one step under the folder's lock every save of each takes, so a tap's
     * write and a pull's never undo each other's. [change] runs on the caller's thread. The marks as
     * they stand after, or null, with nothing written, once [signIn] has ended.
     */
    suspend fun updateAssignmentMarks(signIn: SignIn, change: (AssignmentMarks, List<UnsentMark>) -> AssignmentMarks): AssignmentMarks? {
        val ids = object : TypeToken<List<String>>() {}.type
        val ignoredFile = File(userDataDir, "ignored_assignments.json")
        val completedFile = File(userDataDir, "marked_completed_assignments.json")
        val waitingFile = File(userDataDir, QueueFile.UNSENT_MARKS.fileName)
        val (marks, wrote) = userDataMutex.withLock {
            if (!signIn.isCurrent) return@withLock null
            val (stored, waiting) = withContext(Dispatchers.IO) {
                AssignmentMarks(readJson<List<String>>(ignoredFile, ids)?.toSet().orEmpty(), readJson<List<String>>(completedFile, ids)?.toSet().orEmpty()) to
                    readJson<List<UnsentMark?>>(waitingFile, object : TypeToken<List<UnsentMark?>>() {}.type).orEmpty()
                        .filterNotNull().filter { it.assignmentId != null && it.status != null }
            }
            val next = change(stored, waiting)
            withContext(Dispatchers.IO) {
                if (next.ignored != stored.ignored) atomicWrite(ignoredFile, gson.toJson(next.ignored.toList()))
                if (next.completed != stored.completed) atomicWrite(completedFile, gson.toJson(next.completed.toList()))
            }
            next to (next != stored)
        } ?: return null
        if (wrote) _assignmentMarksVersion.update { it + 1 }
        return marks
    }
    private val userDataMutex = Mutex()
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()

    // One-shot legacy cache migration is deferred to the first course op so the file I/O never lands on
    // the main thread via Hilt-driven singleton construction (would trip StrictMode, risk ANR on slow storage).
    @Volatile
    private var legacyCourseCacheAbsorbed = false

    @Volatile
    private var onCoursesSaved: (() -> Unit)? = null

    fun setOnCoursesSavedListener(listener: () -> Unit) {
        onCoursesSaved = listener
    }

    // --- Courses (semester-scoped) ---

    /**
     * Save courses for a specific semester. Splits the list: remote-fetched courses go to
     * `cacheDir/courses_<semester>.json` (evictable), manual courses go to
     * `userDataDir/manual_courses_<semester>.json` (durable). Manual courses have no remote source:
     * if the cache is evicted they can only be recovered from userDataDir.
     */
    suspend fun saveCourses(courses: List<Course>, semester: String, signIn: SignIn) =
        coursesMutex.withLock { writeCourses(courses, semester, signIn) }

    /**
     * [semester]'s courses as [change] makes them of the list as it stands and of the course numbers
     * hidden in the term now, with no other save of a term between the read and the write, for a sync
     * that commits only what it changed: a delete or a fetch saved while it ran stays as that left it.
     * A delete writes its hide before its list, so a course deleted before this read is in the hides,
     * and one deleted after it is taken off by the delete's own save. What else [change] reads is
     * read with no save of a term meanwhile either.
     */
    suspend fun updateCourses(semester: String, signIn: SignIn, change: suspend (List<Course>, Set<String>) -> List<Course>) =
        coursesMutex.withLock {
            val hidden = CourseTombstoneKeys.hiddenIn(semester, loadDeletedCourseNos())
            writeCourses(change(loadCourses(semester), hidden), semester, signIn)
        }

    /**
     * [semester]'s courses as [change] makes them of the list as it stands, with no other save
     * of a term between the read and the write, as [updateCourses]; but nothing is written when
     * [change] hands back the list it read. For a pass that may change nothing, such as the
     * background sync's names and colours: a course added while it runs stays, and a pass that
     * changed nothing makes no write. Returns the list written, or null when there was none.
     */
    suspend fun updateCoursesIfChanged(semester: String, signIn: SignIn, change: suspend (List<Course>) -> List<Course>): List<Course>? =
        coursesMutex.withLock {
            val current = loadCourses(semester)
            val updated = change(current)
            if (updated == current || !writeCourses(updated, semester, signIn)) return@withLock null
            updated
        }

    // Every write of a term's courses, one at a time; see updateCourses.
    private val coursesMutex = Mutex()

    // In memory, by term: the sign-in and school roster a fetch last committed it as; a reset lets go of it.
    private val committedRosters = java.util.concurrent.ConcurrentHashMap<String, Pair<SignIn, Collection<String>?>>()

    /** Inside [updateCourses]' change: a fetch commits [semester] as [roster], or as none, stored with the write. */
    fun committedWith(semester: String, roster: Collection<String>?, signIn: SignIn) { committedRosters[semester] = signIn to roster }

    /** [semester]'s courses as stored and the fetch commit they last had (see [committedWith]), read as one. */
    suspend fun termAndCommit(semester: String): Pair<List<Course>, Pair<SignIn, Collection<String>?>?> =
        coursesMutex.withLock { loadCourses(semester) to committedRosters[semester] }

    // Both files or, with [signIn] ended, neither that counts: the wipe that follows takes what landed.
    private suspend fun writeCourses(courses: List<Course>, semester: String, signIn: SignIn): Boolean {
        absorbLegacyCourseCache()
        val (manual, remote) = courses.partition { it.isManual }
        val written = save(remote, coursesFilename(semester), signIn) &&
            saveToUserData(manual, manualCoursesFilename(semester), signIn)
        if (!written) return false
        _coursesVersion.update { it + 1 }
        onCoursesSaved?.invoke()
        return true
    }

    /**
     * Load courses for a specific semester. Merges remote-fetched (cacheDir) and manual (userDataDir)
     * files; manual courses win on courseNo collision so the user's isManual flag is preserved even if
     * the remote list was evicted and a re-fetch has not yet repopulated it.
     */
    suspend fun loadCourses(semester: String): List<Course> {
        absorbLegacyCourseCache()
        migrateManualCoursesToUserData(semester)
        val type = object : TypeToken<List<Course>>() {}.type
        // requireContent: v1.4.0 shipped without a keep rule for the moved shared.Course, so cache
        // files written by that build have R8-obfuscated keys. Deserializing them returns Course
        // objects with null-typed-as-non-null Strings — the first caller that reads `courseNo` /
        // `displayName` NPEs. DataMigration sweeps these files on first injection of AppState, but
        // Application.onCreate launches wearBridge.publish() on Dispatchers.IO and can race ahead of
        // MainActivity-triggered migration. Guard at every read.
        val remote = load<List<Course>>(
            type,
            coursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        )?.let(::usableRows) ?: emptyList()
        val manual = loadFromUserData<List<Course>>(
            type,
            manualCoursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        )?.let(::usableRows) ?: emptyList()
        if (manual.isEmpty()) return remote
        val manualNos = manual.map { it.courseNo }.toSet()
        return remote.filter { it.courseNo !in manualNos } + manual
    }

    /**
     * Whether [semester]'s portal rows are cached: `courses_<semester>.json`
     * lives in the evictable cacheDir, and once the system evicts it,
     * [loadCourses] returns the hand-typed courses alone.
     */
    suspend fun hasPortalCourses(semester: String): Boolean {
        absorbLegacyCourseCache()
        val type = object : TypeToken<List<Course>>() {}.type
        return load<List<Course>>(type, coursesFilename(semester), requireContent = COURSE_NO_TOKEN)
            ?.let(::usableRows)
            ?.any { !it.isManual } == true
    }

    private fun manualCoursesFilename(semester: String): String = "manual_courses_$semester.json"

    /**
     * One-shot migration for installs that were already writing manual
     * courses to cacheDir. If the cache still contains isManual entries and
     * the durable file is empty, copy them across.
     *
     * Note: bare file-existence is insufficient — saveCourses always writes
     * the user-data file (even with `[]` for users with zero manual courses
     * at the time), which would otherwise mask a later legacy import that
     * does contain manual entries.
     *
     * A read, so it holds no sign-in. It needs none: from the cached file's read to the durable
     * write it holds the cache's lock, which the wipe takes before it deletes either folder, so
     * the wipe finds the copy made or finds nothing to copy.
     */
    private suspend fun migrateManualCoursesToUserData(semester: String) {
        val type = object : TypeToken<List<Course>>() {}.type
        val existing = loadFromUserData<List<Course>>(
            type,
            manualCoursesFilename(semester),
            requireContent = COURSE_NO_TOKEN,
        )
        if (!existing.isNullOrEmpty()) return
        cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                val cached = readJson<List<Course>>(File(cacheDir, coursesFilename(semester)), type, COURSE_NO_TOKEN)
                val manual = cached.orEmpty().filter { it.isManual }
                if (manual.isEmpty()) return@withContext
                val target = File(userDataDir, manualCoursesFilename(semester))
                userDataMutex.withLock { atomicWrite(target, gson.toJson(manual)) }
            }
        }
    }

    // --- Courses (current-semester aliases) ---
    // Home, BackgroundSyncWorker, LiveActivity, etc. operate on "whatever
    // the user is studying right now" so we keep a no-arg convenience that
    // always maps to the actual current semester.

    suspend fun saveCourses(courses: List<Course>, signIn: SignIn) =
        saveCourses(courses, currentSemesterCode(), signIn)

    suspend fun loadCourses(): List<Course> =
        loadCourses(currentSemesterCode())

    private fun coursesFilename(semester: String): String = "courses_$semester.json"

    /**
     * Pre-semester-scoped format stored at `courses.json`. Move it into the
     * current-semester file on first launch so existing users don't lose
     * their timetable when they upgrade; matches iOS's one-shot migration.
     */
    private suspend fun absorbLegacyCourseCache() {
        if (legacyCourseCacheAbsorbed) return
        cacheMutex.withLock {
            if (legacyCourseCacheAbsorbed) return@withLock
            withContext(Dispatchers.IO) {
                val legacy = File(cacheDir, "courses.json")
                if (legacy.exists()) {
                    val target = File(cacheDir, coursesFilename(currentSemesterCode()))
                    runCatching {
                        if (!target.exists()) legacy.copyTo(target, overwrite = false)
                        legacy.delete()
                    }
                }
                legacyCourseCacheAbsorbed = true
            }
        }
    }

    /**
     * Must stay identical to [org.ntust.app.tigerduck.network.CourseService.currentSemesterCode]
     * — the no-arg course aliases above key cache files off this, so any drift
     * between the two would file "today's" courses under a semester nothing
     * else reads. Duplicated rather than injected so DataCache keeps no
     * dependency on the network layer.
     */
    /**
     * The term whose cache file the app reads.
     *
     * Comes from the school's published calendar; before this feature it was
     * a constant that needed a store release every semester. Falls back to
     * the month heuristic while the calendar is unknown — a device that has
     * never reached the backend still has to open on something.
     */
    internal fun currentSemesterCode(): String =
        SemesterCodes.termOn(academicCalendar.current(), AppClock.localDateTime().toLocalDate())

    // --- Assignments ---

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveAssignments(assignments: List<Assignment>, signIn: SignIn): Boolean {
        if (!save(assignments, "assignments.json", signIn)) return false
        _assignmentsVersion.update { it + 1 }
        return true
    }

    suspend fun loadAssignments(): List<Assignment> = storedAssignments() ?: emptyList()

    /** The assignments as stored; null when their file is missing or unreadable, as once the system clears the cache. */
    suspend fun storedAssignments(): List<Assignment>? = load(object : TypeToken<List<Assignment>>() {}.type, "assignments.json")

    /**
     * Stores what [transform] makes of the cached assignments, read and
     * written under one hold of the cache's lock: a save landing between a
     * separate load and save would be undone by that save. The list as
     * written, for the caller to show, upload and arm reminders from; null,
     * and nothing written, once [signIn] has ended.
     *
     * [transform] runs only after that check and under the same lock, so a
     * sign-out cannot wipe the data while it runs.
     */
    suspend fun updateAssignments(
        signIn: SignIn,
        transform: (List<Assignment>) -> List<Assignment>,
    ): List<Assignment>? {
        val type = object : TypeToken<List<Assignment>>() {}.type
        val written = cacheMutex.withLock {
            if (!signIn.isCurrent) return@withLock null
            withContext(Dispatchers.IO) {
                val file = File(cacheDir, "assignments.json")
                val current = readJson<List<Assignment>>(file, type) ?: emptyList()
                transform(current).also { atomicWrite(file, gson.toJson(it)) }
            }
        }
        if (written != null) _assignmentsVersion.update { it + 1 }
        return written
    }

    // --- The assignment records of the last round ---
    // What `mod_assign_get_assignments` said of every assignment of the term,
    // for an assignment's page and a course's list; see MoodleAssignmentRecord.
    // In the Moodle cache's envelope, under its schema number, as the records
    // hold the same classes: a bump of MOODLE_CACHE_SCHEMA drops them too.

    /**
     * Keeps [records] as the round's answer; false, and nothing written, once [signIn] has ended,
     * as after a sign-out the round outlived.
     */
    suspend fun saveAssignmentRecords(records: List<MoodleAssignmentRecord>, signIn: SignIn): Boolean {
        val written = cacheMutex.withLock {
            if (!signIn.isCurrent) return@withLock false
            withContext(Dispatchers.IO) {
                writeMoodleEnvelope(gson, File(cacheDir, ASSIGNMENT_RECORDS_FILE), records, System.currentTimeMillis())
            }
            true
        }
        if (written) _assignmentRecordsVersion.update { it + 1 }
        return written
    }

    suspend fun loadAssignmentRecords(): List<MoodleAssignmentRecord> {
        val type = object : TypeToken<List<MoodleAssignmentRecord>>() {}.type
        return cacheMutex.withLock {
            withContext(Dispatchers.IO) {
                readMoodleEnvelope<List<MoodleAssignmentRecord>>(gson, File(cacheDir, ASSIGNMENT_RECORDS_FILE), type)?.value
            }
        }.orEmpty()
    }

    /**
     * The cached assignments [OutstandingAssignments] keeps, counting those of
     * an ignored course as ignored: what reminders and the Live Update act on.
     */
    suspend fun loadOutstandingAssignments(): List<Assignment> {
        val all = loadAssignments()
        val ignored = loadIgnoredAssignments() +
            CourseIgnores.assignmentIds(all, loadIgnoredCourses(), currentSemesterCode())
        return OutstandingAssignments.of(all, ignored, loadMarkedCompletedAssignments())
    }

    // --- Skipped Dates (courseNo -> list of ISO date strings "yyyy-MM-dd") ---
    // Stored in filesDir — never cleared by the OS, unlike cacheDir.
    //
    // 翹課 is parked until after the add-friend feature, so nothing reads this
    // file today: every caller passes emptyMap() instead, and the reads are
    // commented out beside them. Read/write stay here on purpose — the file is
    // NOT deleted, so a user who marked classes before v2.0.1 gets their marks
    // back the day the feature is switched on.
    //
    // Do not "reconnect" a caller in isolation. Skipping suppresses class-prep
    // notifications and Live Activity slots, and the UI that undoes a skip
    // (the left-swipe on the Home tile) is commented out — so a live read with
    // a dead toggle means classes silently vanish with no way to get them back.
    // Turn the UI on first. See HomeViewModel._skippedDates.

    suspend fun saveSkippedDates(data: Map<String, List<String>>, signIn: SignIn) =
        saveToUserData(data, "skipped_dates.json", signIn)

    suspend fun loadSkippedDates(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        return loadFromUserData(type, "skipped_dates.json") ?: emptyMap()
    }

    // --- Course Custom Names (per-locale overrides) ---
    // courseNo → locale → name. Stored in filesDir so user renames survive
    // cache eviction. The ClassTableViewModel resolves the current locale's
    // entry into Course.customCourseName at load/display time.

    suspend fun saveCourseCustomNames(names: Map<String, Map<String, String>>, signIn: SignIn) =
        courseNamesLock.withLock { saveToUserData(names, COURSE_CUSTOM_NAMES_FILE, signIn) }

    // Held by every save of the custom names, and from the read to the write of every update.
    // The background sync takes it under the courses' lock, so a change made under it must
    // never save courses.
    private val courseNamesLock = Mutex()

    /**
     * The custom names as [change] makes them of the names stored now, read and written under
     * one lock that every save of them takes too. A rename and a sync merging the server's
     * names each change the names as they stand, so neither is undone by the other's older
     * copy. Nothing is written when [change] hands back what it read. Returns the names as
     * they stand after.
     */
    suspend fun updateCourseCustomNames(
        signIn: SignIn,
        change: suspend (Map<String, Map<String, String>>) -> Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> = courseNamesLock.withLock {
        val before = loadCourseCustomNames()
        val after = change(before)
        if (after != before) saveToUserData(after, COURSE_CUSTOM_NAMES_FILE, signIn)
        after
    }

    suspend fun loadCourseCustomNames(): Map<String, Map<String, String>> {
        val type = object : TypeToken<Map<String, Map<String, String>>>() {}.type
        return loadFromUserData(type, COURSE_CUSTOM_NAMES_FILE) ?: emptyMap()
    }

    // --- Courses 選課 stopped listing (semester → courseNos) ---
    // Written when a successful, non-empty 選課 answer no longer names a
    // course this device holds — a 加退選 drop. Read by the sync reconcile,
    // which would otherwise merge the row the backend still carries straight
    // back onto the timetable, since an upload never prunes and only an
    // explicit DELETE writes a tombstone.
    //
    // Deliberately NOT the deleted_courses.json tombstone set: that one is
    // driven by the server ("absent there → hide, present there → un-hide"),
    // so a 選課-driven entry would be un-hidden by the next sync. This one
    // answers a different question and only 選課 may add to or clear it.
    // Stored in filesDir so a drop survives cache eviction.

    /**
     * Fold one successful 選課 answer for [semester] into the dropped set,
     * and keep it as the term's latest answer, which lifts the marked hides
     * of the deleted hand-added courses it lists; see [hideDeletedCourse].
     * Kept with when it arrived, and its courses are no longer dropped on
     * the server here ([selectionAnswerIn], [recordServerDrops]).
     *
     * Must be called *before* the fetch overwrites the course cache: the
     * courses on disk right now are what the answer is diffed against, and
     * once they are replaced the drop is no longer visible to anyone. And
     * before the fetch's merge reads the hides, so a course whose hide this
     * lifts is shown, saved and uploaded with the roster.
     *
     * Lives here rather than in the three fetch paths that call it — Home,
     * the class table and the background worker — because those three have
     * already grown one duplicated roster merge between them, and this is
     * load and save either side of one line of rule.
     *
     * Nothing is written once [signIn], the fetch's, has ended: the answer is
     * the account's that left.
     *
     * Returns [RecordedSelection.Kept] with the answer as kept, the fetch's
     * version of the term, which [isLatestAnswer] checks before the fetch
     * commits or uploads it; or [RecordedSelection.Refused], an answer older
     * than the one kept or one the wipe overtook, where the fetch stops. Null
     * for an empty roster, which is no answer.
     */
    suspend fun recordSelectionRoster(
        semester: String,
        roster: List<String>,
        signIn: SignIn,
        // Taken as the answer lands, before any wait for the lock, which would make it read newer.
        answeredAt: Long = System.currentTimeMillis(),
    ): RecordedSelection? {
        if (roster.isEmpty()) return null
        // The answer, the hides it lifts and the drops it records under one hold of the lock: let go
        // of between them, a newer answer could land in the gap, and this one's drops after it
        // would drop what the newer lists. An answer older than the one kept, come to the lock
        // later, is no answer at all.
        return selectionDropsMutex.withLock {
            val keptAt = loadSelectionAnsweredAt()[semester]
            if (keptAt != null && keptAt > answeredAt) return@withLock RecordedSelection.Refused
            val kept = saveToUserData(loadSelectionRosters() + (semester to roster), SELECTION_ROSTERS_FILE, signIn) &&
                recordAnswerLocked(semester, roster, answeredAt, signIn)
            if (!kept) return@withLock RecordedSelection.Refused
            liftHandAddedHides(semester, roster, signIn)
            recordDropsLocked(semester, roster, alsoListed = emptyList(), isFinal = false, signIn)
            RecordedSelection.Kept(SelectionAnswer(roster.toSet(), answeredAt))
        }
    }

    /**
     * Whether [answer], which a fetch of [semester] recorded, is still the
     * term's latest. One a newer answer replaced is no roster to commit or
     * send: the newer answer has recorded its drops, and its fetch saves and
     * uploads the term. By its time and its courses, so two answers kept in
     * the same millisecond differ unless they list the same. Takes only the
     * user data's lock, as [fetchDropsIn], so a commit under the courses'
     * lock may read it.
     */
    suspend fun isLatestAnswer(semester: String, answer: SelectionAnswer): Boolean = selectionAnswerIn(semester) == answer

    /**
     * Fold an ended term's roster, read off its transcript, into the dropped
     * set: what the term held here and [alsoListed], the courses it may hold
     * on the server without this phone having fetched them, are dropped but
     * the [roster]. Counts even when empty, as the transcript of an ended
     * term is final. Called before the rebuild saves the term, as
     * [recordSelectionRoster] is.
     *
     * Not a course selection answer: kept as none, and lifts no hide of a
     * course added by hand, which only the school's enrolment brings back.
     *
     * As iOS `DataCache.recordSelectionRoster(isFinal: true)` (tigerduck-app#890).
     *
     * Nothing is written once [signIn] has ended, as [recordSelectionRoster].
     */
    suspend fun recordFinalRoster(
        semester: String,
        roster: List<String>,
        alsoListed: Collection<String>,
        signIn: SignIn,
    ) = recordDrops(semester, roster, alsoListed, isFinal = true, signIn)

    // Every read, change and write of the drop list and the rosters, one at a time: a past term's
    // rebuild and another term's course selection answer each rewrite the whole file, and the later
    // write would erase the other term's drops. The wipes hold it too, so none reads a file a wipe
    // then deletes. Taken before the hides', the cache's and the user data's own locks, never while
    // holding one of them or the courses' lock.
    private val selectionDropsMutex = Mutex()

    // A course added by hand here is a manual row, never in the portal rows diffed against.
    private suspend fun recordDrops(
        semester: String,
        roster: List<String>,
        alsoListed: Collection<String>,
        isFinal: Boolean,
        signIn: SignIn,
    ) = selectionDropsMutex.withLock { recordDropsLocked(semester, roster, alsoListed, isFinal, signIn) }

    private suspend fun recordDropsLocked(
        semester: String,
        roster: List<String>,
        alsoListed: Collection<String>,
        isFinal: Boolean,
        signIn: SignIn,
    ) {
        val stored = loadSelectionDroppedNos()
        val previous = stored[semester].orEmpty().toSet()
        val updated = CourseRosterMerge.selectionDrops(
            previous = previous,
            localPortalNos = loadCourses(semester).filterNot { it.isManual }.map { it.courseNo } + alsoListed,
            roster = roster,
            isFinal = isFinal,
        )
        if (updated == previous) return
        saveSelectionDroppedNos(
            if (updated.isEmpty()) stored - semester else stored + (semester to updated.toList()),
            signIn,
        )
    }

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveSelectionDroppedNos(dropped: Map<String, List<String>>, signIn: SignIn): Boolean =
        saveToUserData(dropped, "selection_dropped.json", signIn)

    // --- Server-Known Course Nos ---
    // Per semester, every course number a `/sync/full` snapshot has carried.
    //
    // A manual course is exempt from "absent from the server means deleted
    // elsewhere" only until the server has actually seen it — before that,
    // absence means our upload has not landed. This is the record of what it
    // has seen. Grows only; a number stays known after the course is deleted,
    // which is what keeps the deletion from being undone on the next sync.
    //
    // In filesDir rather than cacheDir for the same reason as the tombstones:
    // if eviction could drop it, an evicted device would re-arm immunity for
    // every course it holds and upload a term someone else had reset.

    suspend fun saveServerKnownNos(known: Map<String, List<String>>, signIn: SignIn) =
        saveToUserData(known, "server_known_courses.json", signIn)

    // Held from the read to the write of every update below.
    private val serverKnownLock = Mutex()

    /**
     * The server-known numbers changed by [change], read and written under one lock, so writers
     * that each add to them -- a sync's snapshot, an add the backend answered -- never undo one
     * another's. Nothing is written once [signIn] has ended; says whether it wrote, or had nothing
     * to write.
     */
    suspend fun updateServerKnownNos(
        signIn: SignIn,
        change: (Map<String, List<String>>) -> Map<String, List<String>>,
    ): Boolean = serverKnownLock.withLock {
        val before = loadServerKnownNos()
        val after = change(before)
        after == before || saveServerKnownNos(after, signIn)
    }

    suspend fun loadServerKnownNos(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        return loadFromUserData(type, "server_known_courses.json") ?: emptyMap()
    }

    suspend fun loadSelectionDroppedNos(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        return loadFromUserData(type, "selection_dropped.json") ?: emptyMap()
    }

    // --- Semester Reset ---
    // A reset is: backend DELETE, then wipe the term locally, then refetch
    // and upload the fresh roster. Two things guard the syncs that can run
    // in the middle (the 10-second revision poll, the hourly worker):
    //
    // [resettingSemesters] holds the term from before the DELETE goes out
    // until the local wipe is done. A snapshot reconciled in that window
    // sees the server either still full or just emptied and the cache
    // either still full or just emptied, and every combination but the
    // right one puts the old roster somewhere it gets uploaded from. The
    // reconcile skips the term. In memory: it only has to outlive one
    // round trip.
    //
    // `semester_reset_at.json` records, per term, when the DELETE landed.
    // A snapshot is fetched, then reconciled some time later — after the
    // assignment overrides and their PATCHes — so one fetched before the
    // DELETE can be reconciled after the latch is gone, still carrying the
    // pre-reset roster. The reconcile leaves a term alone when the snapshot
    // predates its reset (CourseSyncReconciler.termsResetAfter), and drops
    // the stamp once a snapshot clearly newer has been reconciled. In
    // filesDir so eviction cannot lose it.

    private val resetting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Latches [semester] as mid-reset; false when it already is. */
    fun beginReset(semester: String): Boolean = resetting.add(semester)

    fun endReset(semester: String) { resetting.remove(semester) }

    fun resettingSemesters(): Set<String> = resetting.toSet()

    suspend fun saveSemesterResetAt(semester: String, atMs: Long, signIn: SignIn) =
        saveToUserData(loadSemesterResetAt() + (semester to atMs), "semester_reset_at.json", signIn).also { committedRosters.remove(semester) }

    /** Drops stamps a newer snapshot has outlived — see [CourseSyncReconciler.resetStampsOutlived]. */
    suspend fun clearSemesterResetAt(semesters: Set<String>, signIn: SignIn) {
        if (semesters.isEmpty()) return
        saveToUserData(loadSemesterResetAt() - semesters, "semester_reset_at.json", signIn)
    }

    suspend fun loadSemesterResetAt(): Map<String, Long> {
        val type = object : TypeToken<Map<String, Long>>() {}.type
        return loadFromUserData(type, "semester_reset_at.json") ?: emptyMap()
    }

    // --- Deleted Course Nos (hidden by user or server) ---
    // Stored in filesDir so courses hidden via sync or the delete gesture
    // stay gone even when Moodle/NTUST re-fetches re-add them.

    suspend fun saveDeletedCourseNos(nos: Set<String>, signIn: SignIn) =
        courseHidesMutex.withLock { writeDeletedCourseNos(nos, signIn) }

    suspend fun loadDeletedCourseNos(): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return loadFromUserData<List<String>>(type, "deleted_courses.json")?.toSet()
            ?: emptySet()
    }

    /**
     * A sync's reconcile of [before], the hides it read, into [after],
     * applied to the hides as they are now: only what the reconcile changed
     * is written, so a hide lifted or written while it ran, by a course
     * selection answer or a delete, is not undone by its older read.
     */
    suspend fun updateDeletedCourseNos(before: Set<String>, after: Set<String>, signIn: SignIn) = courseHidesMutex.withLock {
        val now = loadDeletedCourseNos()
        val updated = now + (after - before) - (before - after)
        if (updated != now) writeDeletedCourseNos(updated, signIn)
    }

    /**
     * Pins the legacy bare hides to the terms whose courses [rosters] lists carry them
     * ([CourseTombstoneKeys.migrateLegacyEntries]), under [courseHidesMutex] and from the hides
     * as they are now: a bare hide lifted since [read], the sync's read of them, by adding the
     * course back for one, is pinned to no term, where pinning the read hid the course in its
     * term again. Returns [read] pinned the same way, less such a hide: what the sync goes on to
     * reconcile and its commit is measured against. A hide written since, by a delete, is no part
     * of it, as it was not of the read: taken for the sync's own, it would be lifted by a snapshot
     * fetched before the delete that still lists the course.
     */
    suspend fun pinLegacyCourseHides(read: Set<String>, rosters: Map<String, Set<String>>, signIn: SignIn): Set<String> = courseHidesMutex.withLock {
        val now = loadDeletedCourseNos()
        val pinned = CourseTombstoneKeys.migrateLegacyEntries(now, rosters)
        if (pinned != now) writeDeletedCourseNos(pinned, signIn)
        // A bare hide gone from the store was lifted, unless another sync pinned it meanwhile.
        val stands = read.filterTo(mutableSetOf()) { entry ->
            ':' in entry || entry in now || CourseTombstoneKeys.migrateLegacyEntries(setOf(entry), rosters).any { it in now }
        }
        CourseTombstoneKeys.migrateLegacyEntries(stands, rosters)
    }

    /**
     * [nos] written as the hides, under [courseHidesMutex], with the marks and
     * the courses being brought back kept in step; see [hideDeletedCourse]. A
     * mark goes once its hide does, and so does one whose hide was gone
     * before this write, as a damaged file leaves it; a course being brought
     * back goes once it is hidden again. Nothing, once [signIn] has ended.
     */
    private suspend fun writeDeletedCourseNos(nos: Set<String>, signIn: SignIn) {
        val before = loadDeletedCourseNos()
        if (!saveToUserData(nos.toList(), "deleted_courses.json", signIn)) return
        val marks = loadHandAddedHides()
        val keptMarks = marks.filterTo(mutableSetOf()) { it in nos && it in before }
        if (keptMarks != marks) saveToUserData(keptMarks.toList(), HAND_ADDED_HIDES_FILE, signIn)
        val restored = loadRestoredCourses()
        val keptRestored = restored.filterKeys { it !in nos }
        if (keptRestored != restored) saveToUserData(keptRestored, RESTORED_COURSES_FILE, signIn)
    }

    // --- Hides of deleted hand-added courses (scoped keys) ---
    // A course the student added by hand and deleted again hides like any
    // other delete. But when course selection's latest answer for the term
    // did not list it, its hide is marked here, and once an answer lists it
    // the student has really enrolled: the hide is lifted before the fetch's
    // merge, so the class table shows the course and the roster upload names
    // it. That upload is what lifts the backend's tombstone of a removed
    // hand-added course (tigerduck-backend 3.3.6), and the other devices
    // un-hide it through the sync's reconcile once the server lists it
    // again. Every other hide binds as before: a deleted enrolled course, a
    // course merged from another device's row, a delete made before any
    // answer was kept, and a hide another device's tombstone names.
    //
    // Until a snapshot fetched since lists a course brought back, its
    // absence there is no delete elsewhere: Home's fetch uploads nothing, and
    // the class table's upload can fail. restored_courses.json holds it
    // meanwhile, with when it was brought back, and the reconcile leaves it
    // be (CourseSyncReconciler.reconcileSemester's restoredNos).
    // selection_rosters.json keeps each term's latest course selection
    // answer, which a delete is judged by.
    //
    // A mark only names a hide in deleted_courses.json and lives no longer
    // than it (writeDeletedCourseNos), so a hide lifted, by adding the course
    // back for one, and written again later for another device's tombstone,
    // is not marked. Every change to the three goes under [courseHidesMutex],
    // and a sync commits only what its reconcile changed
    // ([commitSyncHides]). All three are new files: an older install
    // has none, and the hides it kept bind as they did.

    private val courseHidesMutex = Mutex()

    /**
     * A full sync's course reconcile, from [beginCourseSync] before its fetch to [endCourseSync]
     * after its commit. It collects the courses put back in a term since it began, by
     * `<semester>:<courseNo>` key: those added back by hand ([addCourseBack]) and those a course
     * selection answer brought back ([liftHandAddedHides]). Their rows are newer than the sync's
     * read, so its commit does not hide or prune them for it, but for a course brought back that
     * another device's tombstone names ([commitSyncHides]).
     */
    class CourseSync internal constructor(internal val startedAtMs: Long, private val addsBackSoFar: () -> Long) {
        // By key, the latest add back's place in [addCourseBack]'s order.
        private val noted = java.util.concurrent.ConcurrentHashMap<String, Long>()
        internal val broughtBack: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        @Volatile
        private var fetchedAfter = Long.MAX_VALUE

        @Volatile
        private var supersededUpTo = Long.MIN_VALUE

        internal fun noteAddBack(key: String, order: Long) {
            noted.merge(key, order, ::maxOf)
        }

        /** The courses added back since it began whose adds no course reset elsewhere took. */
        internal fun addedBack(): Set<String> = noted.filterValues { it > supersededUpTo }.keys

        /** Call it right before the fetch: an add back made from here on is newer than the snapshot. */
        fun fetching() {
            fetchedAfter = addsBackSoFar()
        }

        /**
         * Its snapshot reads a course reset made on another device, which takes the hand-added
         * courses and every add of them, waiting or under way (HomeBackendSync.applyCourseReset):
         * no add back from before the fetch ([fetching]) keeps a course from its commit then, or
         * its upload would put the course back on every device. One made since is newer than the
         * reset, and stays.
         */
        fun supersedeAddsBack() {
            supersededUpTo = fetchedAfter
        }
    }

    // The syncs under way, in this process only: none outlives it.
    private val courseSyncs = java.util.concurrent.ConcurrentHashMap.newKeySet<CourseSync>()

    // Every add back's place in the order they began, for [CourseSync.supersedeAddsBack].
    private val addsBack = java.util.concurrent.atomic.AtomicLong()

    // The adds back under way, from [addCourseBack] to [courseAddSent], by key: how many, and the
    // latest one's place in the order.
    private val addsUnderWay = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * Begins a full sync's course reconcile; call it before the fetch, and [endCourseSync] however
     * the sync ends. Until then a course added back or brought back is noted for its commit, and
     * the courses brought back that its snapshot may predate stay held ([restoredCoursesListed]).
     * An add back already under way counts too: its upload may not be kept or sent before this
     * sync reads what waits, and its snapshot may predate it.
     */
    fun beginCourseSync(): CourseSync = CourseSync(System.currentTimeMillis(), addsBack::get).also { sync ->
        courseSyncs += sync
        addsUnderWay.forEach { (key, underWay) -> sync.noteAddBack(key, underWay.second) }
    }

    fun endCourseSync(sync: CourseSync) {
        courseSyncs -= sync
    }

    /**
     * Lifts [semester]'s hide of [courseNo], which the class table's add puts back in the term,
     * and notes the add for every sync under way, and every one begun before [courseAddSent]: the
     * add is newer than anything such a sync read, and once it lands it forces its key past a
     * tombstone and the server's silence alike. The hide is lifted under [courseHidesMutex], so a
     * sync's commit comes wholly before it or finds the add noted. Call [courseAddSent] however
     * the add ends, once its upload is kept or sent (PendingCourseAdds.send).
     */
    suspend fun addCourseBack(courseNo: String, semester: String, signIn: SignIn) {
        val key = CourseTombstoneKeys.key(semester, courseNo)
        val order = addsBack.incrementAndGet()
        addsUnderWay.merge(key, 1 to order) { held, _ -> held.first + 1 to order }
        courseSyncs.forEach { it.noteAddBack(key, order) }
        courseHidesMutex.withLock {
            val hides = loadDeletedCourseNos()
            if (CourseTombstoneKeys.isHidden(courseNo, semester, hides)) {
                writeDeletedCourseNos(CourseTombstoneKeys.unhide(courseNo, semester, hides), signIn)
            }
        }
    }

    /** Ends [addCourseBack]'s add of [courseNo] in [semester] for the syncs that begin from now. */
    fun courseAddSent(courseNo: String, semester: String) {
        addsUnderWay.computeIfPresent(CourseTombstoneKeys.key(semester, courseNo)) { _, (count, order) ->
            (count - 1).takeIf { it > 0 }?.let { it to order }
        }
    }

    /**
     * Hides [courseNo] in [semester] for the class table's delete of
     * [course], the row the term held, and marks the hide for
     * [recordSelectionRoster] to lift when the course was added by hand on
     * this phone and the term's latest course selection answer did not list
     * it. Added by hand here is manual with no Moodle id: a row merged from
     * another device's carries the id the server holds, and the server need
     * not have it as a hand-added course's, and a hand-added course a roster
     * listed has taken the fetched row's. With no answer kept for the term,
     * nothing says the course was not enrolled, and the hide binds.
     *
     * The class table calls it once the delete's DELETE is kept
     * (PendingCourseDeletes.keep) and before it is sent: a hide lifted
     * before it is kept would go up with the roster, and the DELETE landing
     * after would take the course off the server again; marked after the
     * send, a process killed during it would leave a hide that binds.
     *
     * The hide and its mark in one write under [courseHidesMutex], and
     * neither for a course the term hides already: that hide is a sync's,
     * for a delete elsewhere, made after the class table last read the term,
     * and a mark written after the sync let go of the ones another device's
     * tombstone names ([commitSyncHides]) would let a course selection
     * answer lift it. A legacy bare entry hiding it still takes the term's
     * hide, unmarked, as it binds: a sync of another term lifts the bare
     * entry once that term lists the course.
     */
    suspend fun hideDeletedCourse(courseNo: String, semester: String, course: Course?, signIn: SignIn) = courseHidesMutex.withLock {
        val hides = loadDeletedCourseNos()
        val key = CourseTombstoneKeys.key(semester, courseNo)
        if (key in hides) return@withLock
        writeDeletedCourseNos(hides + key, signIn)
        if (courseNo in hides) return@withLock
        val addedHere = course != null && course.isManual && course.moodleIdNumber.isNullOrEmpty()
        val answer = loadSelectionRosters()[semester]
        if (addedHere && answer != null && courseNo !in answer) {
            saveToUserData((loadHandAddedHides() + key).toList(), HAND_ADDED_HIDES_FILE, signIn)
        }
    }

    /**
     * A sync's commit of [semester]'s hides: what its reconcile changed from
     * [before], the hides it read, to [after], applied to the hides as they
     * are now (see [updateDeletedCourseNos]), and the marks of the hides of
     * [tombstoned], the course numbers its snapshot's tombstones name, let
     * go of: the backend leaves the tombstone this device's own delete of a
     * hand-added course wrote out of its snapshots, so one there is another
     * device's, which binds. One write under [courseHidesMutex], so a course
     * selection answer cannot lift such a hide between the two.
     *
     * A hide of [tombstoned] the sync read and its reconcile kept that such
     * an answer lifted since is written again, as the reconcile writes it
     * for a course it reads as brought back. Returns the course numbers of
     * every hide this write added, those and the reconcile's own, for the
     * sync to take out of the term: an answer may have saved one back into
     * it since the sync read the term's courses.
     *
     * Nor does the reconcile hide a course put back in the term since [sync]
     * began, whose row is newer than its read (tigerduck-app-android#461):
     * one added back by hand, whatever the snapshot says, and one brought
     * back that [tombstoned] does not name, as the reconcile leaves a course
     * brought back to the server's silence but not to another device's
     * tombstone. Hidden, it would also go from the term.
     */
    suspend fun commitSyncHides(
        semester: String,
        before: Set<String>,
        after: Set<String>,
        tombstoned: Collection<String>,
        sync: CourseSync,
        signIn: SignIn,
    ): Set<String> = courseHidesMutex.withLock {
        val now = loadDeletedCourseNos()
        val keys = tombstoned.mapTo(mutableSetOf()) { CourseTombstoneKeys.key(semester, it) }
        val restored = loadRestoredCourses()
        val addedBack = sync.addedBack()
        val lifted = keys.filterTo(mutableSetOf()) {
            it in before && it in after && it !in now && it in restored && it !in addedBack
        }
        val putBack = addedBack + (sync.broughtBack - keys)
        val updated = now + (after - before - putBack) - (before - after) + lifted
        if (updated != now) writeDeletedCourseNos(updated, signIn)
        val marks = loadHandAddedHides()
        val kept = marks - keys
        if (kept != marks) saveToUserData(kept.toList(), HAND_ADDED_HIDES_FILE, signIn)
        val prefix = CourseTombstoneKeys.key(semester, "")
        (updated - now).filter { it.startsWith(prefix) }.mapTo(mutableSetOf()) { it.removePrefix(prefix) }
    }

    /**
     * [semester]'s courses a course selection answer brought back that the
     * snapshot fetched at [fetchedAtMs] has to leave be: none fetched before
     * it had listed them since; see [liftHandAddedHides].
     */
    suspend fun restoredCoursesIn(semester: String, fetchedAtMs: Long): Set<String> {
        val prefix = CourseTombstoneKeys.key(semester, "")
        return loadRestoredCourses()
            .filter { (key, course) -> key.startsWith(prefix) && (course.listedAt == null || fetchedAtMs < course.listedAt) }
            .keys.mapTo(mutableSetOf()) { it.removePrefix(prefix) }
    }

    /**
     * Notes the courses brought back in [semester] that [listed], the course
     * numbers of a snapshot fetched at [fetchedAtMs], holds, when it was
     * fetched after they came back: the upload that names them has landed,
     * and to that snapshot and every later one their absence is a delete
     * elsewhere again. A sync still reconciling an older one, however long
     * it has been held, leaves them be, so the hold stays until the course is
     * hidden again, the data goes with a sign-out, or no such sync is left
     * (below). An older snapshot may still list the row their delete took off.
     *
     * A hold a snapshot has listed goes, in every term, once no sync under
     * way ([beginCourseSync]) began before that snapshot was fetched: no
     * snapshot it could still mean something to is left, and every later
     * one is fetched after it. A process restart lets go of them all, as no
     * sync outlives one. The file would otherwise keep one per term and
     * course for good (tigerduck-app-android#461).
     */
    suspend fun restoredCoursesListed(semester: String, listed: Collection<String>, fetchedAtMs: Long, signIn: SignIn) =
        courseHidesMutex.withLock {
            val restored = loadRestoredCourses()
            val keys = listed.mapTo(mutableSetOf()) { CourseTombstoneKeys.key(semester, it) }
            val oldest = minOf(fetchedAtMs, courseSyncs.minOfOrNull { it.startedAtMs } ?: fetchedAtMs)
            val updated = restored.mapValues { (key, course) ->
                if (course.listedAt == null && key in keys && fetchedAtMs > course.restoredAt) {
                    course.copy(listedAt = fetchedAtMs)
                } else {
                    course
                }
            }.filterValues { it.listedAt == null || it.listedAt > oldest }
            if (updated != restored) saveToUserData(updated, RESTORED_COURSES_FILE, signIn)
        }

    /**
     * A course a course selection answer brought back: when (epoch millis),
     * and when the first snapshot fetched since that lists it was fetched.
     */
    data class RestoredCourse(val restoredAt: Long, val listedAt: Long?)

    /** The marked hides, as `<semester>:<courseNo>` keys; see [hideDeletedCourse]. */
    private suspend fun loadHandAddedHides(): Set<String> {
        val type = object : TypeToken<List<String?>>() {}.type
        return loadFromUserData<List<String?>>(type, HAND_ADDED_HIDES_FILE).orEmpty().filterNotNullTo(mutableSetOf())
    }

    /** The courses being brought back, by `<semester>:<courseNo>` key; see [restoredCoursesIn]. */
    private suspend fun loadRestoredCourses(): Map<String, RestoredCourse> {
        val type = object : TypeToken<Map<String?, RestoredCourse?>>() {}.type
        return loadFromUserData<Map<String?, RestoredCourse?>>(type, RESTORED_COURSES_FILE).orEmpty()
            .mapNotNull { (key, course) -> if (key == null || course == null) null else key to course }
            .toMap()
    }

    // --- When each course selection answer arrived, and the courses dropped on the server ---
    // selection_answered_at.json keeps, per term, when the answer selection_rosters.json holds
    // arrived (epoch millis). A file of its own, as the rosters' shape cannot take a field: an
    // install that kept its answers before has none, and reads them with no time, which exempts
    // no course from a server drop (SelectionAnswer.listsSince) until the next answer brings one.
    //
    // server_dropped.json keeps, per term, the courses the last full sync found a roster dropped
    // on the server, but those the term's answer listed since (tigerduck-app#914's
    // server_dropped.json). The snapshot's rows are not kept, and a fetch that only gains from a
    // running term's transcript, which may still list such a course, leaves them out when it
    // saves (tigerduck-app#918). Both go under [selectionDropsMutex], with the drop list.

    /** [semester]'s latest non-empty course selection answer, and when it arrived; null when none was kept. */
    suspend fun selectionAnswerIn(semester: String): SelectionAnswer? {
        val courseNos = loadSelectionRosters()[semester] ?: return null
        return SelectionAnswer(courseNos.toSet(), loadSelectionAnsweredAt()[semester])
    }

    /**
     * Records, for a full sync, [semester]'s courses a roster dropped on the server, each by
     * when ([droppedAt], null when not known), but those the term's answer as it stands now
     * listed since: this phone's roster enrolls them again. The term's earlier ones go: the
     * snapshot is newer. Nothing is written once [signIn] has ended.
     */
    suspend fun recordServerDrops(semester: String, droppedAt: Map<String, Long?>, signIn: SignIn) {
        selectionDropsMutex.withLock {
            val answer = selectionAnswerIn(semester)
            val dropped = droppedAt.filterNot { (courseNo, at) -> answer?.listsSince(courseNo, at) == true }.keys
            val stored = loadServerDropped()
            if (stored[semester].orEmpty().toSet() == dropped) return@withLock
            saveToUserData(
                if (dropped.isEmpty()) stored - semester else stored + (semester to dropped.sorted()),
                SERVER_DROPPED_FILE,
                signIn,
            )
        }
    }

    /** [semester]'s courses the last full sync found dropped on the server; see [recordServerDrops]. */
    suspend fun serverDroppedIn(semester: String): Set<String> = loadServerDropped()[semester].orEmpty().toSet()

    /**
     * What a fetch of [semester] that only gains leaves out: its drop list and its courses
     * dropped on the server. Takes only the user data's lock, never the drop list's, so a commit
     * under the courses' lock may read it.
     */
    suspend fun fetchDropsIn(semester: String): Set<String> =
        loadSelectionDroppedNos()[semester].orEmpty().toSet() + serverDroppedIn(semester)

    // The answer's time, after the answer it dates: one cut short between the two leaves the
    // newer answer with the older time, which exempts no more than before. The answer's courses
    // are no longer dropped on the server for this phone, whose roster enrolls them again.
    private suspend fun recordAnswerLocked(semester: String, roster: List<String>, answeredAt: Long, signIn: SignIn): Boolean {
        if (!saveToUserData(loadSelectionAnsweredAt() + (semester to answeredAt), SELECTION_ANSWERED_AT_FILE, signIn)) {
            return false
        }
        val stored = loadServerDropped()
        val dropped = stored[semester] ?: return true
        val still = dropped - roster.toSet()
        if (still.size == dropped.size) return true
        return saveToUserData(if (still.isEmpty()) stored - semester else stored + (semester to still), SERVER_DROPPED_FILE, signIn)
    }

    // An entry that is not a number reads as no time, the others as written.
    private suspend fun loadSelectionAnsweredAt(): Map<String, Long> {
        val type = object : TypeToken<Map<String?, JsonElement?>>() {}.type
        return loadFromUserData<Map<String?, JsonElement?>>(type, SELECTION_ANSWERED_AT_FILE).orEmpty()
            .mapNotNull { (semester, at) ->
                val ms = runCatching { at?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong }.getOrNull()
                if (semester == null || ms == null) null else semester to ms
            }
            .toMap()
    }

    private suspend fun loadServerDropped(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String?, List<String?>?>>() {}.type
        return loadFromUserData<Map<String?, List<String?>?>>(type, SERVER_DROPPED_FILE).orEmpty()
            .mapNotNull { (semester, nos) -> semester?.let { it to nos.orEmpty().filterNotNull() } }
            .toMap()
    }

    /** Each term's latest non-empty course selection answer, by [recordSelectionRoster]. */
    private suspend fun loadSelectionRosters(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String?, List<String?>?>>() {}.type
        return loadFromUserData<Map<String?, List<String?>?>>(type, SELECTION_ROSTERS_FILE).orEmpty()
            .mapNotNull { (semester, nos) -> semester?.let { it to nos.orEmpty().filterNotNull() } }
            .toMap()
    }

    /**
     * Lifts the marked hides of the courses [roster], one course selection
     * answer for [semester], lists, as the student has enrolled in them
     * since, and holds the courses as being brought back until a snapshot
     * fetched since lists them. Only a mark whose hide still stands, and not
     * one whose DELETE is still queued (PendingCourseDeletes): landing after
     * the roster upload, it would take the course off the server again. An
     * answer after it lands lifts that one. Noted for every sync under way,
     * whose read is older ([commitSyncHides]). Nothing, once [signIn] has ended.
     */
    private suspend fun liftHandAddedHides(semester: String, roster: List<String>, signIn: SignIn) = courseHidesMutex.withLock {
        if (!signIn.isCurrent) return@withLock
        val marks = loadHandAddedHides()
        if (marks.isEmpty()) return@withLock
        val hides = loadDeletedCourseNos()
        val deleting = loadPendingCourseDeletes()[semester].orEmpty().keys
        val lifted = roster.filter { it !in deleting }
            .map { CourseTombstoneKeys.key(semester, it) }
            .filterTo(mutableSetOf()) { it in marks && it in hides }
        if (lifted.isEmpty()) return@withLock
        courseSyncs.forEach { it.broughtBack += lifted }
        // Ahead of the hides: one held while its hide still stands is let go by the next write.
        val restoredAt = System.currentTimeMillis()
        val restored = loadRestoredCourses() + lifted.associateWith { RestoredCourse(restoredAt, listedAt = null) }
        if (!saveToUserData(restored, RESTORED_COURSES_FILE, signIn)) return@withLock
        writeDeletedCourseNos(hides - lifted, signIn)
    }

    /**
     * Every term's courses on this phone, fetched and added by hand, keyed by
     * term, for the sign-out's check of what it would delete that exists
     * nowhere else.
     */
    suspend fun loadEveryTermCourses(): Map<String, List<Course>> {
        fun termOf(name: String, prefix: String): String? =
            name.takeIf { it.startsWith(prefix) && it.endsWith(".json") }
                ?.removePrefix(prefix)?.removeSuffix(".json")
        val semesters = withContext(Dispatchers.IO) {
            cacheDir.list().orEmpty().mapNotNull { termOf(it, "courses_") } +
                userDataDir.list().orEmpty().mapNotNull { termOf(it, "manual_courses_") }
        }
        return semesters.toSortedSet().associateWith { loadCourses(it) }
    }

    /** The file [queue]'s save writes, for a reader that needs its modified time. */
    fun queueFile(queue: QueueFile): File = File(userDataDir, queue.fileName)

    // --- Ignored Assignments (set of assignmentIds) ---
    // Stored in filesDir so the user's ignore decisions survive OS cache eviction
    // and remote re-fetches, mirroring skipped_dates.json handling.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveIgnoredAssignments(ids: Set<String>, signIn: SignIn): Boolean {
        if (!saveToUserData(ids.toList(), "ignored_assignments.json", signIn)) return false
        _assignmentMarksVersion.update { it + 1 }
        return true
    }

    suspend fun loadIgnoredAssignments(): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return loadFromUserData<List<String>>(type, "ignored_assignments.json")?.toSet()
            ?: emptySet()
    }

    // --- Ignored Courses (set of CourseIgnores keys) ---
    // Every assignment of these courses counts as ignored. In filesDir with
    // the per-assignment list it extends.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveIgnoredCourses(keys: Set<String>, signIn: SignIn): Boolean {
        if (!saveToUserData(keys.sorted(), "ignored_courses.json", signIn)) return false
        _assignmentMarksVersion.update { it + 1 }
        return true
    }

    suspend fun loadIgnoredCourses(): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return loadFromUserData<List<String>>(type, "ignored_courses.json")?.toSet()
            ?: emptySet()
    }

    // Changes to the set above the backend has not confirmed; see
    // CourseIgnoreSync, the only writer of both files.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun savePendingCourseIgnores(changes: List<PendingCourseIgnore>, signIn: SignIn): Boolean =
        saveToUserData(changes, QueueFile.COURSE_IGNORES.fileName, signIn)

    /** Rows without a usable [PendingCourseIgnore.key] are left out. */
    suspend fun loadPendingCourseIgnores(): List<PendingCourseIgnore> {
        val type = object : TypeToken<List<PendingCourseIgnore>>() {}.type
        return loadFromUserData<List<PendingCourseIgnore?>>(type, QueueFile.COURSE_IGNORES.fileName)
            ?.filterNotNull()
            ?.filter { it.key != null }
            ?: emptyList()
    }

    // --- Course rows seen (course key -> server row id) ---
    // The rows the last full sync that took the course ignores listed; see
    // CourseIgnoreReconciler.recreated. CourseIgnoreSync is the only writer.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveCourseRowsSeen(rows: Map<String, Long>, signIn: SignIn): Boolean =
        saveToUserData(rows, "course_rows_seen.json", signIn)

    suspend fun loadCourseRowsSeen(): Map<String, Long> {
        val type = object : TypeToken<Map<String, Long?>>() {}.type
        return loadFromUserData<Map<String, Long?>>(type, "course_rows_seen.json")
            .orEmpty()
            .mapNotNull { (key, id) -> id?.let { key to it } }
            .toMap()
    }

    // --- Marked-Completed Assignments (set of assignmentIds) ---
    // Independent from Moodle's `isCompleted`: lets the user manually flag a
    // homework as done from the swipe gesture even when Moodle hasn't (or
    // won't) record a submission. Persisted in filesDir alongside the ignore
    // list so it survives remote re-fetches.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveMarkedCompletedAssignments(ids: Set<String>, signIn: SignIn): Boolean {
        if (!saveToUserData(ids.toList(), "marked_completed_assignments.json", signIn)) return false
        _assignmentMarksVersion.update { it + 1 }
        return true
    }

    suspend fun loadMarkedCompletedAssignments(): Set<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return loadFromUserData<List<String>>(type, "marked_completed_assignments.json")?.toSet()
            ?: emptySet()
    }

    // --- Unsent assignment marks (assignmentId → status) ---
    // Marks tapped here whose PATCH the backend has not confirmed; see
    // UnsentMarks, the only writer. In filesDir with the marks themselves.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveUnsentMarks(marks: List<UnsentMark>, signIn: SignIn): Boolean =
        saveToUserData(marks, QueueFile.UNSENT_MARKS.fileName, signIn)

    /** Rows without an id or a status are left out. */
    suspend fun loadUnsentMarks(): List<UnsentMark> {
        val type = object : TypeToken<List<UnsentMark?>>() {}.type
        return loadFromUserData<List<UnsentMark?>>(type, QueueFile.UNSENT_MARKS.fileName)
            ?.filterNotNull()
            ?.filter { it.assignmentId != null && it.status != null }
            ?: emptyList()
    }

    // --- Pending course adds (semester → course numbers) ---
    // Courses added by hand whose upload the backend has not taken; see
    // PendingCourseAdds, the only writer. A new file, so an older install
    // simply has none; read tolerantly all the same.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun savePendingCourseAdds(pending: Map<String, List<String>>, signIn: SignIn): Boolean =
        saveToUserData(pending, QueueFile.COURSE_ADDS.fileName, signIn)

    /** Null and blank entries are left out, and so is a term left with none. */
    suspend fun loadPendingCourseAdds(): Map<String, List<String>> {
        val type = object : TypeToken<Map<String, List<String?>?>>() {}.type
        return loadFromUserData<Map<String?, List<String?>?>>(type, QueueFile.COURSE_ADDS.fileName)
            .orEmpty()
            .mapNotNull { (semester, nos) ->
                val kept = nos.orEmpty().filterNotNull().filter(String::isNotBlank)
                if (semester.isNullOrBlank() || kept.isEmpty()) null else semester to kept
            }
            .toMap()
    }

    // --- When each pending course add was asked (semester → course number → stamp) ---
    // Beside pending_course_adds.json rather than in it, so that file keeps
    // the shape older installs wrote; an add with no time here, one an older
    // install queued, goes unstamped. See PendingCourseAdds, the only writer.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun savePendingCourseAddTimes(times: Map<String, Map<String, Long>>, signIn: SignIn): Boolean =
        saveToUserData(times, QueueFile.COURSE_ADD_TIMES.fileName, signIn)

    /** Null and blank entries are left out, and so is a term left with none. */
    suspend fun loadPendingCourseAddTimes(): Map<String, Map<String, Long>> {
        val type = object : TypeToken<Map<String?, Map<String?, Long?>?>>() {}.type
        return loadFromUserData<Map<String?, Map<String?, Long?>?>>(type, QueueFile.COURSE_ADD_TIMES.fileName)
            .orEmpty()
            .mapNotNull { (semester, entries) ->
                val kept = entries.orEmpty().mapNotNull { (courseNo, addedAt) ->
                    if (courseNo.isNullOrBlank() || addedAt == null) null else courseNo to addedAt
                }.toMap()
                if (semester.isNullOrBlank() || kept.isEmpty()) null else semester to kept
            }
            .toMap()
    }

    // --- Pending course deletes (semester → course number → when it was asked) ---
    // Courses deleted on the class table whose backend DELETE has not
    // landed, each with the moment it was asked (PendingCourseDeletes.stamp);
    // see PendingCourseDeletes, the only writer. A new file, so an older
    // install simply has none; read tolerantly all the same.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun savePendingCourseDeletes(pending: Map<String, Map<String, Long>>, signIn: SignIn): Boolean =
        saveToUserData(pending, QueueFile.COURSE_DELETES.fileName, signIn)

    /** Null and blank entries are left out, and so is a term left with none. */
    suspend fun loadPendingCourseDeletes(): Map<String, Map<String, Long>> {
        val type = object : TypeToken<Map<String?, Map<String?, Long?>?>>() {}.type
        return loadFromUserData<Map<String?, Map<String?, Long?>?>>(type, QueueFile.COURSE_DELETES.fileName)
            .orEmpty()
            .mapNotNull { (semester, entries) ->
                val kept = entries.orEmpty().mapNotNull { (courseNo, queuedAt) ->
                    if (courseNo.isNullOrBlank() || queuedAt == null) null else courseNo to queuedAt
                }.toMap()
                if (semester.isNullOrBlank() || kept.isEmpty()) null else semester to kept
            }
            .toMap()
    }

    // --- Pending course overrides (a colour, or a name in one locale) ---
    // Colours and names set on the class table whose PATCH the backend has
    // not confirmed; see PendingCourseOverrides, the only writer. A new file,
    // so an older install simply has none; read tolerantly all the same.

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun savePendingCourseOverrides(pending: List<PendingCourseOverride>, signIn: SignIn): Boolean =
        saveToUserData(pending, QueueFile.COURSE_OVERRIDES.fileName, signIn)

    /** Rows too damaged to send ([PendingCourseOverride.key] null) are left out. */
    suspend fun loadPendingCourseOverrides(): List<PendingCourseOverride> {
        val type = object : TypeToken<List<PendingCourseOverride?>>() {}.type
        return loadFromUserData<List<PendingCourseOverride?>>(type, QueueFile.COURSE_OVERRIDES.fileName)
            ?.filterNotNull()
            ?.filter { it.key != null }
            ?: emptyList()
    }

    // --- Score Report (per studentId) ---

    /**
     * Fields are nullable as Gson-Unsafe defense (see CLAUDE.md): this class
     * is deserialized from score_<id>.json across upgrades, and Gson bypasses
     * the constructor, so a cache file written before a future field/shape
     * change would leave non-null fields silently null. Readers must
     * null-check both fields.
     */
    data class ScoreReportSnapshot(val report: ScoreReport?, val cachedAt: Date?)

    /** False, and nothing written, once [signIn] has ended. */
    suspend fun saveScoreReport(report: ScoreReport, studentId: String, signIn: SignIn): Boolean =
        save(ScoreReportSnapshot(report, Date()), scoreReportFilename(studentId), signIn)

    suspend fun loadScoreReport(studentId: String): ScoreReportSnapshot? {
        val type = object : TypeToken<ScoreReportSnapshot>() {}.type
        return load(type, scoreReportFilename(studentId))
    }

    suspend fun invalidateScoreReport(studentId: String) = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { File(cacheDir, scoreReportFilename(studentId)).delete() }
            Unit
        }
    }

    private fun scoreReportFilename(studentId: String): String = "score_$studentId.json"

    // --- Course lookup (querycourse.ntust.edu.tw responses) ---
    // Course metadata (name, instructor, schedule, caps) is static within a
    // semester; only ChooseStudent drifts, and drifting by a few minutes is
    // tolerable for a pull-to-refresh UX. Caching the raw CourseSearchResult
    // lets a repeat "課表" or Home load skip the per-course fan-out entirely.

    data class CourseLookupEntry(
        val results: List<CourseSearchResult>,
        val cachedAt: Long,
    )

    suspend fun saveCourseLookups(map: Map<String, CourseLookupEntry>, signIn: SignIn) =
        save(map, "course_lookups.json", signIn)

    suspend fun loadCourseLookups(): Map<String, CourseLookupEntry> {
        val type = object : TypeToken<Map<String, CourseLookupEntry>>() {}.type
        return load<Map<String, CourseLookupEntry>>(type, "course_lookups.json") ?: emptyMap()
    }

    // --- Moodle course id map (idnumber → numeric id) ---
    // Powers the course-detail "Open in Moodle" deep link, which needs the
    // numeric id (Moodle's web endpoint won't accept the idnumber). The map
    // is harvested from `fetchEnrolledCourses` across ALL semesters so
    // historical terms keep working too. Cached so a transient Moodle
    // failure doesn't make the button vanish for the rest of the session;
    // wiped on logout via [clearAllUserData] so it can't survive an
    // account switch.

    suspend fun saveMoodleCourseIds(map: Map<String, Int>, signIn: SignIn) =
        save(map, "moodle_course_ids.json", signIn)

    suspend fun loadMoodleCourseIds(): Map<String, Int> {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return load<Map<String, Int>>(type, "moodle_course_ids.json") ?: emptyMap()
    }

    // --- A course's Moodle data, per account ---
    // What the course detail's Moodle screens show (forums, announcements,
    // grades, members, summary), kept so they draw at once and offline. One
    // file per kind and course under `moodle/<account>/`, each with the time
    // its data was fetched. Wiped with the rest on sign-out.
    //
    // Versioned as a whole by [MOODLE_CACHE_SCHEMA]: a file in another shape
    // reads as absent, and the screen fetches it again. Bump it with ANY
    // change to the classes in network/model/moodle — the Gson Unsafe path
    // would leave a field the old file lacks null behind a non-null type.

    /** A copy of a course's Moodle data, as [saveMoodleData] kept it. */
    data class MoodleCached<T>(val value: T, val fetchedAtMs: Long)

    suspend fun saveMoodleData(value: Any, fetchedAtMs: Long, name: String, account: String, signIn: SignIn) {
        cacheMutex.withLock {
            if (!signIn.isCurrent) return@withLock
            withContext(Dispatchers.IO) { writeMoodleEnvelope(gson, moodleFile(name, account), value, fetchedAtMs) }
        }
    }

    suspend fun <T> loadMoodleData(type: java.lang.reflect.Type, name: String, account: String): MoodleCached<T>? =
        cacheMutex.withLock {
            withContext(Dispatchers.IO) { readMoodleEnvelope(gson, moodleFile(name, account), type) }
        }

    /** Where files of [account]'s Moodle data that are not JSON, such as member pictures, go. */
    fun moodleFilesDir(account: String): File = File(moodleDir, moodleAccountFolder(account))

    private val moodleDir: File get() = File(cacheDir, MOODLE_DIR)

    private val folderNames = OpaqueFolderNames(context)

    /**
     * Where downloaded Moodle files are kept, a folder per account (see [moodleDownloadsDir]):
     * copies the app hands to another app through its FileProvider (`moodle_downloads` in
     * res/xml/file_provider_paths.xml), so that path exposes nothing else. In the files folder,
     * which the system does not clear for space: a downloaded file opens at once next time.
     * Settings measure and clear it on its own, and a sign-out deletes it.
     */
    fun moodleDownloadsRoot(): File = File(context.filesDir, MOODLE_DOWNLOADS_DIR)

    /** [account]'s downloaded Moodle files, in a folder whose name does not spell the account. */
    fun moodleDownloadsDir(account: String): File = File(moodleDownloadsRoot(), folderNames.folderFor(account))

    private fun moodleFile(name: String, account: String): File {
        // A name is the app's own, such as "courses/123/grades"; dot segments never come from it,
        // but nothing outside the account's folder may be written if one ever did.
        val safe = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
        return File(moodleFilesDir(account), "$safe.json")
    }

    private fun moodleAccountFolder(account: String): String =
        account.filter { it.isLetterOrDigit() }.ifEmpty { "_" }

    // --- Assignments closed unsubmitted (assignment id -> when Moodle said so, epoch ms) ---
    // Past their cutoff with nothing handed in, which cannot change, so a
    // round does not ask Moodle about them again; see
    // MoodleService.assignmentsToAsk. Moodle's own data, so in the cache.

    suspend fun saveClosedAssignments(closed: Map<Int, Long>, signIn: SignIn) =
        save(closed.mapKeys { it.key.toString() }, "closed_assignments.json", signIn)

    suspend fun loadClosedAssignments(): Map<Int, Long> {
        val type = object : TypeToken<Map<String, Long?>>() {}.type
        return load<Map<String, Long?>>(type, "closed_assignments.json")
            .orEmpty()
            .mapNotNull { (id, at) -> id.toIntOrNull()?.let { key -> at?.let { key to it } } }
            .toMap()
    }

    // --- Calendar Events ---

    suspend fun saveCalendarEvents(events: List<CalendarEvent>, signIn: SignIn) =
        save(events, "calendar_events.json", signIn)

    suspend fun loadCalendarEvents(): List<CalendarEvent> {
        val type = object : TypeToken<List<CalendarEvent>>() {}.type
        return load(type, "calendar_events.json") ?: emptyList()
    }

    // --- Logout cleanup ---

    /**
     * At the school's sign-out ([org.ntust.app.tigerduck.auth.session.SchoolStores]): wipe every
     * piece of user-scoped data so the next login does not inherit the previous user's courses,
     * assignments, calendar events, or skip marks on the UI. School-wide calendar events are
     * rebuilt on the next sync, so it is fine to drop them here too.
     */
    override suspend fun forgetAccount() = clearAllUserData()

    /**
     * Delete **every** file in both cache directories, user-scoped or not.
     *
     * [clearAllUserData] deliberately keeps device-wide caches (name abbreviations, the academic
     * calendar, bulletin snapshots) because a logout is an account change, not a factory reset. The
     * full-reset flow is the opposite: whatever is left behind is exactly what makes the "fresh
     * install" it promises not actually fresh, so this takes the directories wholesale rather than
     * naming files — a named list silently stops being complete the next time someone adds a cache.
     */
    suspend fun clearEverything() {
        // Outside the folders' locks, as a recording takes them: see selectionDropsMutex.
        selectionDropsMutex.withLock {
            cacheMutex.withLock {
                userDataMutex.withLock {
                    withContext(Dispatchers.IO) {
                        listOf(cacheDir, userDataDir).forEach { dir ->
                            dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                        }
                        runCatching { moodleDownloadsRoot().deleteRecursively() }
                        // It names the downloaded files' folders, which are gone, and a fresh install has none.
                        folderNames.forgetInstallSalt()
                    }
                }
            }
        }
    }

    suspend fun clearAllUserData() {
        // Outside the folders' locks, as a recording takes them: see selectionDropsMutex.
        selectionDropsMutex.withLock {
            // The sign-in ended before this began, and a write asks it under the lock of the folder
            // it writes to: one past that check is done before its folder is wiped, and no later
            // one passes it.
            cacheMutex.withLock {
                withContext(Dispatchers.IO) {
                    listOf(
                        "courses.json",
                        "assignments.json",
                        "calendar_events.json",
                        "course_lookups.json",
                        "moodle_course_ids.json",
                        "closed_assignments.json",
                        ASSIGNMENT_RECORDS_FILE,
                    ).forEach { name ->
                        runCatching { File(cacheDir, name).delete() }
                    }
                    // Drop every per-semester course bucket so historical data
                    // doesn't bleed across accounts on the same device.
                    runCatching {
                        cacheDir.listFiles { _, name -> name.startsWith("courses_") && name.endsWith(".json") }
                            ?.forEach { it.delete() }
                    }
                    runCatching {
                        cacheDir.listFiles { _, name -> name.startsWith("score_") && name.endsWith(".json") }
                            ?.forEach { it.delete() }
                    }
                    runCatching { deleteUnfinishedWrites(cacheDir) }
                    // Every account's Moodle copies, member pictures and downloaded files, and the
                    // opened copies earlier builds kept apart.
                    runCatching { moodleDir.deleteRecursively() }
                    runCatching { File(cacheDir, MOODLE_OPENED_DIR).deleteRecursively() }
                    runCatching { moodleDownloadsRoot().deleteRecursively() }
                }
            }
            userDataMutex.withLock {
                withContext(Dispatchers.IO) {
                    listOf(
                        "skipped_dates.json",
                        "ignored_assignments.json",
                        "ignored_courses.json",
                        QueueFile.COURSE_IGNORES.fileName,
                        "marked_completed_assignments.json",
                        QueueFile.UNSENT_MARKS.fileName,
                        QueueFile.COURSE_ADDS.fileName,
                        QueueFile.COURSE_ADD_TIMES.fileName,
                        QueueFile.COURSE_DELETES.fileName,
                        QueueFile.COURSE_OVERRIDES.fileName,
                        "deleted_courses.json",
                        HAND_ADDED_HIDES_FILE,
                        RESTORED_COURSES_FILE,
                        "course_custom_names.json",
                        "selection_dropped.json",
                        SELECTION_ROSTERS_FILE,
                        SELECTION_ANSWERED_AT_FILE,
                        SERVER_DROPPED_FILE,
                        "server_known_courses.json",
                        "semester_reset_at.json",
                        "course_rows_seen.json",
                    ).forEach { name ->
                        runCatching { File(userDataDir, name).delete() }
                    }
                    runCatching {
                        userDataDir.listFiles { _, name ->
                            name.startsWith("manual_courses_") && name.endsWith(
                                ".json"
                            )
                        }
                            ?.forEach { it.delete() }
                    }
                    runCatching { deleteUnfinishedWrites(userDataDir) }
                }
            }
        }
    }

    // --- Private helpers ---

    /**
     * The temporary files of [atomicWrite]s in [dir] a process death cut short before their
     * rename, which hold the data they were writing. Called with the folder's lock held.
     */
    private fun deleteUnfinishedWrites(dir: File) {
        dir.listFiles { _, name -> name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    // As saveToUserData, in the cache folder.
    private suspend fun <T> save(value: T, filename: String, signIn: SignIn): Boolean =
        cacheMutex.withLock {
            if (!signIn.isCurrent) return@withLock false
            withContext(Dispatchers.IO) { atomicWrite(File(cacheDir, filename), gson.toJson(value)) }
            true
        }

    private suspend fun <T> load(
        type: java.lang.reflect.Type,
        filename: String,
        requireContent: String? = null,
    ): T? =
        cacheMutex.withLock {
            withContext(Dispatchers.IO) { readJson<T>(File(cacheDir, filename), type, requireContent) }
        }

    // Checked under the folder's lock. A sign-out ends the sign-in before the wipe takes that lock,
    // so a write past this check is done before the wipe starts, and a later one cannot pass it.
    private suspend fun <T> saveToUserData(value: T, filename: String, signIn: SignIn): Boolean =
        userDataMutex.withLock {
            if (!signIn.isCurrent) return@withLock false
            withContext(Dispatchers.IO) { atomicWrite(File(userDataDir, filename), gson.toJson(value)) }
            true
        }

    private suspend fun <T> loadFromUserData(
        type: java.lang.reflect.Type,
        filename: String,
        requireContent: String? = null,
    ): T? =
        userDataMutex.withLock {
            withContext(Dispatchers.IO) { readJson<T>(File(userDataDir, filename), type, requireContent) }
        }

    // Takes no lock: the caller holds the one for the folder [file] is in.
    private fun <T> readJson(
        file: File,
        type: java.lang.reflect.Type,
        requireContent: String? = null,
    ): T? = try {
        if (file.exists()) parseJsonIfContains(file.readText(), requireContent, gson, type) else null
    } catch (_: Exception) {
        null
    }

    internal companion object {
        private const val TAG = "DataCache"

        private const val HAND_ADDED_HIDES_FILE = "hand_added_hides.json"
        private const val RESTORED_COURSES_FILE = "restored_courses.json"
        private const val SELECTION_ROSTERS_FILE = "selection_rosters.json"
        private const val SELECTION_ANSWERED_AT_FILE = "selection_answered_at.json"
        private const val SERVER_DROPPED_FILE = "server_dropped.json"
        private const val COURSE_CUSTOM_NAMES_FILE = "course_custom_names.json"

        /** What [atomicWrite] names its temporary file with, after the file it writes. */
        internal const val TEMP_SUFFIX = ".tmp"

        private const val MOODLE_DIR = "moodle"
        private const val ASSIGNMENT_RECORDS_FILE = "moodle_assignments.json"
        /** Where builds before the downloaded files kept opened copies, a day at a time. */
        private const val MOODLE_OPENED_DIR = "moodle_open"
        private const val MOODLE_DOWNLOADS_DIR = "moodle_files"

        /** See saveMoodleData: bump with any change to the classes in network/model/moodle. */
        internal const val MOODLE_CACHE_SCHEMA = 5

        // Sentinel literal present in any course JSON written by a build
        // with un-obfuscated field names. Used by loadCourses to reject
        // R8-obfuscated v1.4.0 cache files before they reach Gson.
        // Trailing `:` keeps this anchored to an object key — a payload
        // string value that contains the substring `courseNo` would not
        // include an unescaped quote+colon pair, so it can't satisfy the
        // check and slip past as if the keys were un-obfuscated.
        internal const val COURSE_NO_TOKEN = "\"courseNo\":"

        internal fun <T> parseJsonIfContains(
            text: String,
            requiredToken: String?,
            gson: Gson,
            type: java.lang.reflect.Type,
        ): T? {
            if (requiredToken != null && !text.contains(requiredToken)) return null
            return try {
                gson.fromJson(text, type)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Drops rows Gson left un-populated.
         *
         * [COURSE_NO_TOKEN] is necessary but not sufficient: it proves the
         * writer used un-obfuscated field names, but only that the *key* is
         * present — `{"courseNo":null}` satisfies it just as well. Gson's
         * `Unsafe.allocateInstance` path bypasses the Kotlin constructor, so an
         * absent or explicitly-null key leaves a non-null-declared `String`
         * holding null, with no intrinsic check at the read site to catch it.
         *
         * [Course.courseNo] is the identity every lookup keys on and
         * [Course.courseName] backs [Course.displayName], so a row missing
         * either is unusable — and it NPEs far from here, in
         * `TigerDuckTheme.courseHashIndex` (`courseNo.fold {}`) or
         * `WearScheduleBridge.toDto`, neither of which null-checks first.
         *
         * Deliberately additive to the sentinel, not a replacement for it —
         * the sentinel still rejects whole obfuscated files before Gson runs.
         */
        @Suppress("SENSELESS_COMPARISON")
        internal fun usableRows(courses: List<Course>): List<Course> =
            courses.filter { it.courseNo != null && it.courseName != null }
    }
}
