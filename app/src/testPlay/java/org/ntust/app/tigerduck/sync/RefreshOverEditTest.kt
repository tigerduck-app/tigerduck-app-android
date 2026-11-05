package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.parseFullSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableCourseMerge
import org.ntust.app.tigerduck.ui.screen.classtable.saveClassTableEdit
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Race 5 (refactor-v4 D14, R5 to R8; spec §5.3): a write computed from what was read before an
 * `await` lands over what changed meanwhile. A school fetch and its lookups against the edits made
 * during them, and the class table's edits against a pull or a fetch that writes the term while
 * the edit's save waits, over the real stores and queues. The class table's view model cannot be
 * built in a JVM test, so its saves are driven through the calls it makes (`ClassTableWiringTest`
 * pins that it makes them, in this order). Writers run on [Dispatchers.Default] and the files on
 * IO, so each lands whenever it does; every assertion follows the writers' end. Made-up accounts
 * and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RefreshOverEditTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var signIn: SignIn
    private lateinit var term: String
    private lateinit var policies: SyncPolicies
    private lateinit var overrides: PendingCourseOverrides
    private lateinit var outbox: Outbox

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v4"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.dataCache.currentSemesterCode()
        policies = SyncPolicies(w.appPrefs)
        overrides = PendingCourseOverrides(w.dataCache, w.pushApi, w.appPrefs, policies)
        outbox = Outbox(setOf(overrides))
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private val locale get() = AppLanguageManager.resolvedCourseApiLanguage(w.appPrefs.appLanguage)

    /** [courses] as the term, on disk and on Home, whose overrides step runs over a term it shows. */
    private suspend fun show(vararg courses: Course) {
        w.dataCache.saveCourses(courses.toList(), term, signIn)
        state.allCourses.value = courses.toList()
    }

    private suspend fun stored(): List<Course> = w.dataCache.loadCourses(term)

    // --- A school fetch over the edits made during its lookups (R5, R7) ---

    /** Course selection lists [CS1] and [CS2]; [duringLookups] runs once, as the first lookup goes out. */
    private fun termSync(courseLocale: () -> String = { locale }, duringLookups: suspend () -> Unit): TermCourseSync {
        var once = false
        val sources = object : TermCourseSync.Sources {
            override fun servesSelection(semester: String) = semester == term
            override suspend fun selection(studentId: String, password: String, fresh: Boolean) = listOf(CS1, CS2)
            override suspend fun moodle(): List<MoodleEnrolledCourse> = emptyList()
            override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course {
                if (!once) {
                    once = true
                    duringLookups()
                }
                return Course(courseNo = courseNo, courseName = "School $courseNo", moodleIdNumber = "$semester$courseNo")
            }
        }
        return TermCourseSync(
            w.dataCache, sources,
            uploader = { _, _, _, _, _ -> },
            waitingAdds = { emptySet() },
            uploading = { block -> block() },
            courseLocale = courseLocale,
            uploadFailed = {},
        )
    }

    /** R5: the fetch folds onto the term as it stands when it commits, not as its lookups found it. */
    @Test
    fun `a fetch keeps a course added and a course deleted while its lookups ran`() = runBlocking {
        show(Course(courseNo = CS1, courseName = "School $CS1", moodleIdNumber = "$term$CS1"), Course(courseNo = CS2, courseName = "School $CS2", moodleIdNumber = "$term$CS2"))
        val sync = termSync {
            // The class table's add, then its delete, as they write: the hide first, then the list.
            val hand = Course(courseNo = HAND, courseName = "Added here", isManual = true)
            w.dataCache.addCourseBack(HAND, term, signIn)
            w.dataCache.saveCourses(stored() + hand, term, signIn)
            val deleted = stored().single { it.courseNo == CS2 }
            w.dataCache.hideDeletedCourse(CS2, term, deleted, signIn)
            w.dataCache.saveCourses(stored().filter { it.courseNo != CS2 }, term, signIn)
        }

        sync.sync(STUDENT, "made-up-password", term, signIn = signIn)

        assertEquals(setOf(CS1, HAND), stored().mapTo(mutableSetOf()) { it.courseNo })
    }

    /** R7: the names a fetch commits are those of the language set as it commits. */
    @Test
    fun `a fetch saves the custom names in the language set when it commits, not when it began`() = runBlocking {
        w.dataCache.saveCourseCustomNames(mapOf(CS1 to mapOf("zh" to "中文名", "en" to "English Name")), signIn)
        var language = "zh"
        val sync = termSync(courseLocale = { language }) { language = "en" }

        sync.sync(STUDENT, "made-up-password", term, signIn = signIn)

        assertEquals("English Name", stored().single { it.courseNo == CS1 }.customCourseName)
    }

    // --- The class table's edits over a pull or a fetch that writes the term meanwhile (R6, R8, race 5) ---

    private fun snapshot(vararg rows: JSONObject): BackendSyncResult = parseFullSync(
        JSONObject()
            .put("current_revision", 1)
            .put("server_hlc", 1_793_000_000_000_000L)
            .put("assignments", JSONArray())
            .put("courses", JSONArray())
            .put("course_overrides", JSONArray().apply { rows.forEach { put(it) } }),
    )

    private fun colorRow(no: String, hex: String) = JSONObject()
        .put("moodle_id", "$term$no").put("course_no", no).put("semester", term).put("course_key", SyncField.courseKey(term, no))
        .put("color_hex", hex).put("color_hex_hlc", 1_792_000_000_000_000L).put("custom_names", JSONObject())

    private fun pass(result: BackendSyncResult) = SyncPass(
        result = result,
        signIn = signIn,
        mode = SyncMode.FOREGROUND,
        policy = policies.current,
        state = state,
        courseSync = DataCache.CourseSync(0L) { 0L },
        beforeFetch = 0L,
        syncedBefore = SyncCategory.entries.toSet(),
    )

    private fun reconciler() = OverridesReconciler(w.dataCache, w.widgetUpdater, overrides, policies, w.appPrefs)

    /**
     * Holds the names' lock until the returned gate is let go: a `/v4` pull's overrides step,
     * started after this, then waits inside the courses' lock it took, as when the names are busy.
     */
    private suspend fun CoroutineScope.holdNames(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            w.dataCache.updateCourseCustomNames(signIn) { names ->
                holding.complete(Unit)
                gate.await()
                names
            }
        }
        withTimeout(10_000) { holding.await() }
        return gate
    }

    /**
     * Holds a write of the term, [change] of it, under the courses' lock until the returned gate is
     * let go: a pull's or a fetch's commit, under way as the class table's edit goes to save.
     */
    private suspend fun CoroutineScope.holdTermWrite(change: (List<Course>) -> List<Course>): Pair<CompletableDeferred<Unit>, Job> {
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            w.dataCache.updateCourses(term, signIn) { courses, _ ->
                holding.complete(Unit)
                gate.await()
                change(courses)
            }
        }
        withTimeout(10_000) { holding.await() }
        return gate to job
    }

    /**
     * Codex on PR L, round 2, finding 1: the class table's color pick, built at the tap of the list
     * on screen and saved after waiting behind a `/v4` pull's overrides step, wrote back course B as
     * the tap found it, over the snapshot's color the step gave B.
     */
    @Test
    fun `a color picked while a pull's step runs keeps the color the step gave another course`() = runBlocking {
        show(
            Course(courseNo = "A1", courseName = "A", moodleIdNumber = "${term}A1", customColorHex = "#111111"),
            Course(courseNo = "B1", courseName = "B", moodleIdNumber = "${term}B1"),
        )
        val names = holdNames()
        val pull = launch(Dispatchers.Default) { reconciler().reconcile(pass(snapshot(colorRow("B1", "#222222")))) }
        delay(300)
        // The class table's pick, as it makes it: the list on screen at the tap, then its save, and the change kept to go up.
        val before = stored()
        val picked = before.map { if (it.courseNo == "A1") it.copy(customColorHex = "#333333") else it }
        val save = launch(Dispatchers.Default) { w.dataCache.saveClassTableEdit(term, signIn, before, picked) }
        val keep = launch(Dispatchers.Default) {
            overrides.queue(
                listOf(PendingCourseOverrides.Change("${term}A1", colorHex = "#333333", semester = term, courseNo = "A1")),
                overrides.reserveVersion(),
                signIn,
            )
        }
        delay(300)
        names.complete(Unit)
        withTimeout(10_000) {
            pull.join()
            save.join()
            keep.join()
        }

        assertEquals(mapOf("A1" to "#333333", "B1" to "#222222"), stored().associate { it.courseNo to it.customColorHex })
    }

    /** R8: a course another device added, merged in by a pull while an edit's save waited, stays. */
    @Test
    fun `an edit saved while a pull merges in another device's course keeps that course`() = runBlocking {
        show(Course(courseNo = "A1", courseName = "A", moodleIdNumber = "${term}A1"))
        val before = stored()
        val (gate, pull) = holdTermWrite { it + Course(courseNo = "X9", courseName = "Added elsewhere", isManual = true) }
        val renamed = before.map { it.copy(customCourseName = "Renamed A") }
        val save = launch(Dispatchers.Default) { w.dataCache.saveClassTableEdit(term, signIn, before, renamed) }
        delay(300)
        gate.complete(Unit)
        withTimeout(10_000) {
            pull.join()
            save.join()
        }

        assertEquals(mapOf("A1" to "Renamed A", "X9" to null), stored().associate { it.courseNo to it.customCourseName })
    }

    /**
     * Holds [change] of the term, a pull's write, while [edit] of the list on screen, made at the
     * tap, goes to save behind it; the term as both leave it.
     */
    private suspend fun CoroutineScope.editBehindWrite(change: (Course) -> Course, edit: (Course) -> Course): Course {
        val before = stored()
        val (gate, pull) = holdTermWrite { courses -> courses.map(change) }
        val save = launch(Dispatchers.Default) { w.dataCache.saveClassTableEdit(term, signIn, before, before.map(edit)) }
        delay(300)
        gate.complete(Unit)
        withTimeout(10_000) {
            pull.join()
            save.join()
        }
        return stored().single()
    }

    private fun roomed(room: String) = Course(
        courseNo = "A1", courseName = "A", moodleIdNumber = "${term}A1", customColorHex = "#111111",
        classroom = room, scheduleJson = "{\"2\":[\"5\"]}", classroomMapJson = "{\"2-5\":\"$room\"}",
    )

    /** Greptile on PR #686, P1: a rename of A saved behind a pull's color for A writes the name alone. */
    @Test
    fun `a rename saved while a pull gives the same course a color keeps that color`() = runBlocking {
        show(roomed("IB-202"))
        val a = editBehindWrite(change = { it.copy(customColorHex = "#222222") }, edit = { it.copy(customCourseName = "Renamed A") })
        assertEquals("Renamed A" to "#222222", a.customCourseName to a.customColorHex)
    }

    /** Greptile on PR #686, P1: a rename of A saved behind a pull's new rooms for A keeps those rooms. */
    @Test
    fun `a rename saved while a pull gives the same course new rooms keeps those rooms`() = runBlocking {
        show(roomed("IB-202"))
        val moved = roomed("TR-313")
        val a = editBehindWrite(
            change = { it.copy(classroom = moved.classroom, classroomMapJson = moved.classroomMapJson) },
            edit = { it.copy(customCourseName = "Renamed A") },
        )
        assertEquals("Renamed A", a.customCourseName)
        assertEquals(moved.classroom to moved.classroomMapJson, a.classroom to a.classroomMapJson)
    }

    /** Greptile on PR #686, P1: a color picked for A saved behind a pull's room change of A keeps the new rooms. */
    @Test
    fun `a color picked while a pull gives the same course new rooms keeps those rooms`() = runBlocking {
        show(roomed("IB-202"))
        val moved = roomed("TR-313")
        val a = editBehindWrite(
            change = { it.copy(classroom = moved.classroom, classroomMapJson = moved.classroomMapJson) },
            edit = { it.copy(customColorHex = "#333333") },
        )
        assertEquals("#333333", a.customColorHex)
        assertEquals(moved.classroom to moved.classroomMapJson, a.classroom to a.classroomMapJson)
    }

    /** R6: the rooms found for the list on screen go onto a newer roster a fetch wrote meanwhile. */
    @Test
    fun `rooms found for the list on screen are saved onto a newer roster, which they do not undo`() = runBlocking {
        show(
            Course(courseNo = "A1", courseName = "A", moodleIdNumber = "${term}A1"),
            Course(courseNo = "B1", courseName = "B", moodleIdNumber = "${term}B1", classroom = ""),
        )
        val before = stored()
        // The newer roster: B1 dropped, C1 enrolled.
        val (gate, fetch) = holdTermWrite { courses ->
            courses.filter { it.courseNo != "B1" } + Course(courseNo = "C1", courseName = "C", moodleIdNumber = "${term}C1")
        }
        val found = mapOf(
            "A1" to Course(courseNo = "A1", courseName = "A", classroom = "IB-202"),
            "B1" to Course(courseNo = "B1", courseName = "B", classroom = "TR-313"),
        )
        val withRooms = ClassTableCourseMerge.withRoomsFound(before, found) ?: before
        val save = launch(Dispatchers.Default) { w.dataCache.saveClassTableEdit(term, signIn, before, withRooms) }
        delay(300)
        gate.complete(Unit)
        withTimeout(10_000) {
            fetch.join()
            save.join()
        }

        assertEquals(listOf("A1", "C1"), stored().map { it.courseNo })
        assertEquals("IB-202", stored().single { it.courseNo == "A1" }.classroom)
    }

    /**
     * The class table's rename, as it writes: the course first, onto the term as it stands, then
     * the name once its change is [kept] (`ClassTableViewModel.setCustomCourseName`).
     */
    private suspend fun rename(before: List<Course>, after: List<Course>, courseNo: String, name: String, kept: CompletableDeferred<Unit>) {
        w.dataCache.saveClassTableEdit(term, signIn, before, after)
        kept.await()
        w.dataCache.updateCourseCustomNames(signIn) { it + (courseNo to mapOf(locale to name)) }
    }

    /**
     * PR L's gap: a name given to a course added by hand whose own save still waits, here behind a
     * fetch's commit with a `/v4` pull's overrides step queued before it. The pull's step, reading
     * every course and the names in one step, must not find the name without its course: it would
     * take it away as a name no course here holds.
     */
    @Test
    fun `a name given to a course whose add still waits behind a pull's step stays`() = runBlocking {
        show(Course(courseNo = "C1", courseName = "C", moodleIdNumber = "${term}C1"))
        val names = holdNames()
        val (commit, committing) = holdTermWrite { it }
        val pull = launch(Dispatchers.Default) { reconciler().reconcile(pass(snapshot())) }
        delay(300)
        // The add, made of the list on screen, its save queued behind the pull's step.
        val before = stored()
        val added = before + Course(courseNo = "ADD2", courseName = "Added", isManual = true)
        val add = launch(Dispatchers.Default) { w.dataCache.saveClassTableEdit(term, signIn, before, added) }
        delay(300)
        // Then its rename, nothing to keep for a course with no Moodle id.
        val renamed = added.map { if (it.courseNo == "ADD2") it.copy(customCourseName = "My Added") else it }
        val rename = launch(Dispatchers.Default) { rename(added, renamed, "ADD2", "My Added", CompletableDeferred(Unit)) }
        delay(300)
        names.complete(Unit)
        delay(300)
        commit.complete(Unit)
        withTimeout(10_000) {
            committing.join()
            pull.join()
            add.join()
            rename.join()
        }

        assertEquals(mapOf("ADD2" to mapOf(locale to "My Added")), w.dataCache.loadCourseCustomNames())
        assertEquals("My Added", stored().single { it.courseNo == "ADD2" }.customCourseName)
    }

    /**
     * PR L's gap: a rename writes its name and records its change in two steps. Its change is kept
     * before the name lands, and before the change is answered, so a `/v4` pull's step after the
     * name holds it however the snapshot reads.
     */
    @Test
    fun `a rename's name lands once its change is kept, so a pull's step after it keeps the name`() = runBlocking {
        show(Course(courseNo = "R1", courseName = "School R", moodleIdNumber = "${term}R1"))
        val path = "PATCH /v4/sync/courses/${SyncField.courseKey(term, "R1")}/override"
        val held = backend.hold(path)
        val kept = CompletableDeferred<Unit>()
        // Read as it is told, on the thread that tells it: what the queue holds at that moment.
        var heldWhenTold: List<String?>? = null
        kept.invokeOnCompletion { heldWhenTold = runBlocking { w.dataCache.loadPendingCourseOverrides().map { it.customName } } }
        val before = stored()
        val renamed = before.map { it.copy(customCourseName = "Renamed R") }
        val send = launch(Dispatchers.Default) {
            overrides.send("${term}R1", customName = "Renamed R", locale = locale, semester = term, courseNo = "R1", signIn = signIn, kept = kept)
        }
        withTimeout(10_000) { rename(before, renamed, "R1", "Renamed R", kept) }
        assertEquals("kept when told, before it is answered", listOf("Renamed R"), heldWhenTold)

        reconciler().reconcile(pass(snapshot()))

        assertEquals(mapOf("R1" to mapOf(locale to "Renamed R")), w.dataCache.loadCourseCustomNames())
        held.release(200)
        withTimeout(10_000) { send.join() }
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val CS1 = "XA0000001"
        const val CS2 = "XB0000002"
        const val HAND = "XH0000008"
    }
}
