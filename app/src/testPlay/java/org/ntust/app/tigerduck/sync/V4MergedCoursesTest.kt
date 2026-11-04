package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import android.os.Looper
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
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableCourseMerge
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The courses a `/v4` snapshot merged down, as the device check on PR V found them: a freshly
 * signed-in phone takes each term's courses from the snapshot before the school's roster answers,
 * and keeps each as a manual row. Such a copy is no course added by hand. Its delete goes with no
 * kind, so the backend decides from its row and hides a school course rather than take back a
 * hand-add it never had; and a term's upload names it in its adds only when its row was added by
 * hand, on whichever device. Over the app's own clients, queues and synchronizer ([appSyncRig]),
 * the class table's delete and add driven through the calls it makes (`ClassTableWiringTest`).
 * Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V4MergedCoursesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val rules = V4WriteRules()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private val afterSnapshot = AtomicReference<(() -> Unit)?>(null)

    private lateinit var w: AccountWorld
    private lateinit var rig: AppSyncRig
    private lateinit var signIn: SignIn

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
        AppClock.setOverride(ClockOverride(IN_TERM.toInstant(ZoneOffset.ofHours(8)).toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
        val prefs = AppPreferences(context)
        prefs.announcementApiBaseUrlOverride = STORED
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        context.getSharedPreferences("academic_calendar", Context.MODE_PRIVATE).edit()
            .putString("payload", """{"revision":1,"semesters":[{"code":"$TERM","start":"2026-09-01","end":"2027-01-31"}],"holidays":[]}""")
            .commit()
        val clock = SyncClock(SyncClockStore(context), prefs)
        // The rules, then whatever a test runs once the snapshot's answer is in, before the pull reads it.
        val client = appBackendClient(prefs, clock, backend.client) { chain ->
            rules.intercept(chain).also { if (chain.request().url.encodedPath == "/v4/sync/full") afterSnapshot.getAndSet(null)?.invoke() }
        }
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client, backendLogin = client)
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS))
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE, issuedBy = STORED, studentId = STUDENT)
        }
        signIn = w.sessions.school.signIns.current
        assertEquals(TERM, w.dataCache.currentSemesterCode())
        w.appPrefs.semesterCatalogTerms = listOf(TERM)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        AppClock.setOverride(null)
    }

    private fun row(no: String, source: String = "ntust_portal", addedHlc: Long? = null) = JSONObject()
        .put("id", no.hashCode().toLong() and 0xFFFF).put("semester", TERM).put("course_key", key(no)).put("course_no", no)
        .put("course_name", "Course $no").put("moodle_id", "$TERM$no").put("source", source).put("enrollment_status", "enrolled")
        .put("added_hlc", addedHlc ?: JSONObject.NULL)

    /** A `/v4` snapshot holding [rows] of [TERM] and nothing else. */
    private fun snapshot(vararg rows: JSONObject) = JSONObject()
        .put("current_revision", 7)
        .put("server_hlc", SERVER_HLC)
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put("courses", JSONArray().apply { rows.forEach(::put) })
        .put("course_overrides", JSONArray())
        .put("course_tombstones", JSONArray())
        .put("term_resets", JSONObject())
        .put("holiday_overrides", JSONArray())
        .toString()

    private fun key(no: String) = "client:$TERM:$no"

    /** The pull of a freshly signed-in phone, which takes the term's courses from [rows]. */
    private suspend fun pullFresh(vararg rows: JSONObject) {
        backend.answer("GET /v4/sync/full", 200, snapshot(*rows))
        rig.home.sync.pull(state, signIn)
        assertEquals("merged from the snapshot", rows.map { it.getString("course_no") }.sorted(), w.dataCache.loadCourses(TERM).map { it.courseNo }.sorted())
    }

    private fun uploadTaken(vararg nos: String) = backend.answer(
        "POST /v4/sync/courses/upload", 200,
        """{"upserted":${nos.size},"skipped_tombstoned":0,"accepted_keys":[${nos.joinToString(",") { "\"${key(it)}\"" }}]}""",
    )

    /**
     * The class table's delete of [courseNo] with its tombstone, through the calls
     * `ClassTableViewModel.deleteCourse` makes, in its order: the delete kept, its hide marked, the
     * term saved without it, the delete sent, and the term's upload.
     */
    private suspend fun deleteOnTheClassTable(courseNo: String) {
        val courses = w.dataCache.loadCourses(TERM)
        val deleted = courses.firstOrNull { it.courseNo == courseNo }
        val updated = courses.filter { it.courseNo != courseNo }
        backend.answer("DELETE /v4/sync/courses/${key(courseNo)}", 200, """{"deleted":1,"applied":true,"hlc":${SERVER_HLC + 1_000}}""")
        uploadTaken(*updated.map { it.courseNo }.toTypedArray())
        val queuedAt = rig.home.deletes.stamp()
        assertTrue(rig.home.deletes.keep(TERM, courseNo, queuedAt, signIn))
        w.dataCache.hideDeletedCourse(courseNo, TERM, deleted, signIn)
        assertTrue(w.dataCache.saveCourses(updated, TERM, signIn))
        rig.home.deletes.sendKept(TERM, courseNo, signIn)
        rig.pushApi.uploadCourses(updated, TERM, signIn)
    }

    /** The class table's add of [course] by hand, through the calls `ClassTableViewModel.addCourse` makes. */
    private suspend fun addOnTheClassTable(course: Course) {
        val updated = w.dataCache.loadCourses(TERM) + course.copy(isManual = true)
        backend.answer(
            "POST /v4/sync/courses/upload", 200,
            """{"upserted":1,"skipped_tombstoned":0,"accepted_keys":["${key(course.courseNo)}"],""" +
                """"adds":[{"course_key":"${key(course.courseNo)}","applied":true,"hlc":${SERVER_HLC + 2_000}}]}""",
        )
        val addedAt = rig.home.deletes.stamp()
        w.dataCache.addCourseBack(course.courseNo, TERM, signIn)
        assertTrue(w.dataCache.saveCourses(updated, TERM, signIn))
        rig.home.deletes.forget(TERM, course.courseNo, addedAt, signIn)
        rig.home.adds.send(updated, TERM, course.courseNo, addedAt = addedAt, signIn = signIn)
        w.dataCache.courseAddSent(course.courseNo, TERM)
    }

    private fun uploads(): List<JSONObject> = backend.received("POST /v4/sync/courses/upload").map { JSONObject(it.body) }

    private fun JSONObject.courseNos(): List<String> = getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_no") } }

    private fun JSONObject.adds(): List<String> = optJSONArray("adds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_key") } }.orEmpty()

    private fun deletes(): List<String> = backend.endpoints().filter { it.startsWith("DELETE /v4/sync/courses/") }

    @Test
    fun `a school course merged from the snapshot of a fresh sign-in goes with no kind once deleted`() = runBlocking {
        pullFresh(row("XA0000001"), row("XA0000002"))

        deleteOnTheClassTable("XA0000001")

        assertEquals(listOf("DELETE /v4/sync/courses/${key("XA0000001")}"), deletes())
        val query = backend.received("DELETE /v4/sync/courses/${key("XA0000001")}").single().query
        assertTrue("the delete goes stamped: $query", Regex("""^hlc=\d+$""").matches(query))
        assertEquals(emptyList<String>(), rules.refused)
    }

    @Test
    fun `the term's upload after a delete names no school course merged from the snapshot, and still names one added here`() =
        runBlocking {
            pullFresh(row("XA0000001"), row("XA0000002"), row("XA0000003"))
            addOnTheClassTable(Course(courseNo = "XH0000009", courseName = "Added here"))

            deleteOnTheClassTable("XA0000001")

            val (added, afterDelete) = uploads()
            assertEquals(listOf("XH0000009"), added.courseNos())
            assertEquals(listOf(key("XH0000009")), added.adds())
            assertEquals(listOf("XA0000002", "XA0000003", "XH0000009"), afterDelete.courseNos().sorted())
            assertEquals(listOf(key("XH0000009")), afterDelete.adds())
            assertEquals(emptyList<String>(), rules.refused)
        }

    @Test
    fun `a course another device added by hand, merged from the snapshot, is still named in the term's upload`() = runBlocking {
        pullFresh(row("XA0000001"), row("XA0000002"), row("XE0000005", source = "user_added", addedHlc = SERVER_HLC - 5_000))

        deleteOnTheClassTable("XA0000001")

        val upload = uploads().single()
        assertEquals(listOf("XA0000002", "XE0000005"), upload.courseNos().sorted())
        assertEquals(listOf(key("XE0000005")), upload.adds())
    }

    /**
     * A phone updated from the released build holds copies it merged on `/v3`, which kept nothing
     * of their rows, and [pull]s. The first `/v4` pull that lists one tells it apart: the copy of
     * a row another device added by hand is named in the term's upload, a school course's is not.
     */
    private fun olderBuildsCopiesAfter(pull: suspend () -> Unit) = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XA0000002"), olderCopy("XE0000005")), TERM, signIn))
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XA0000002"), row("XE0000005", source = "user_added", addedHlc = SERVER_HLC - 5_000)))
        pull()
        uploadTaken("XA0000002", "XE0000005")

        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)

        val upload = uploads().last()
        assertEquals(listOf("XA0000002", "XE0000005"), upload.courseNos().sorted())
        assertEquals(listOf(key("XE0000005")), upload.adds())
        assertEquals(emptyList<String>(), rules.refused)
    }

    /** A copy merged down as an older build kept it: manual, with a Moodle id, and nothing of its row. */
    private fun olderCopy(no: String, term: String = TERM) = Course(courseNo = no, courseName = "Course $no", moodleIdNumber = "$term$no", isManual = true)

    /** What each of [term]'s courses on the phone says of its row. */
    private suspend fun provenance(term: String) = w.dataCache.loadCourses(term).associate { it.courseNo to it.isUserAddedOnServer }

    /** The course uploads of [term]. */
    private fun uploadsOf(term: String) = uploads().filter { it.getJSONArray("courses").getJSONObject(0).getString("semester") == term }

    /**
     * Codex on round 5 (P2 #2): a full snapshot that holds neither a row nor a tombstone of a copy no
     * pull has told about, in a term the pull covers, tells the copy it is the student's own: the
     * backend holds nothing of it, and the school's fetch has not listed it, which would have made it
     * the school's. It then goes up named, an add at 0 that creates its `user_added` row, in the
     * pull's own upload of a term the server holds nothing of too. Home's pull covers every term.
     */
    @Test
    fun `a copy the snapshot holds nothing of goes up as added by hand after Home's pull`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        w.appPrefs.semesterCatalogTerms = listOf(TERM, PAST)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XA0000002"), olderCopy("XU0000007")), TERM, signIn))
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XU0000008", PAST)), PAST, signIn))
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XA0000002")))

        rig.home.sync.pull(state, signIn)

        assertEquals(mapOf("XA0000002" to false, "XU0000007" to true), provenance(TERM))
        assertEquals(mapOf("XU0000008" to true), provenance(PAST))
        val past = uploadsOf(PAST).single()
        assertEquals(listOf("XU0000008"), past.courseNos())
        assertEquals(listOf("client:$PAST:XU0000008"), past.adds())

        uploadTaken("XA0000002", "XU0000007")
        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)

        val term = uploadsOf(TERM).single()
        assertEquals(listOf("XA0000002", "XU0000007"), term.courseNos().sorted())
        assertEquals(listOf(key("XU0000007")), term.adds())
        assertEquals(emptyList<String>(), rules.refused)
    }

    /**
     * A school fetch's commit of [semester], which lists [courseNo]: the copy is the school's. Over
     * the term as it stands, under the courses' lock, as `commitFetchedTerm` commits a fetch.
     */
    private suspend fun schoolFetchLists(courseNo: String, semester: String = TERM) {
        val fetched = listOf(Course(courseNo = courseNo, courseName = "School $courseNo", moodleIdNumber = "$semester$courseNo"))
        assertTrue(
            w.dataCache.updateCourses(semester, signIn) { current, hidden -> ClassTableCourseMerge.mergeFetched(fetched, current, emptyMap(), "zh", hidden) },
        )
    }

    /**
     * V's parked race (Codex on round 7): a whole list saved from a read made before a school fetch
     * told a copy it is the school's, as the colors' reset saves one, lands during a later sync. It
     * says nothing new of the copy's row, so it puts back nothing the fetch told: the sync's pull
     * tells the copy from its snapshot, and no upload names a school course, an add the student
     * never made, which no roster would ever drop.
     */
    @Test
    fun `a whole list saved from a read before a school fetch, landing during a later pull, puts back no word of its row`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XA0000002"), olderCopy("XU0000007")), TERM, signIn))
        // A pull whose snapshot holds nothing of XU0000007 tells it it was added by hand.
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XA0000002")))
        rig.home.sync.pull(state, signIn)
        assertEquals(true, provenance(TERM)["XU0000007"])
        // The colors' reset reads the term, and its save comes late.
        val read = w.dataCache.loadCourses(TERM)
        // The school's fetch lists the course, which its roster upload sends as a school row.
        schoolFetchLists("XU0000007")
        assertEquals(false, provenance(TERM)["XU0000007"])

        // The next pull's snapshot lists it as the school's; the late save lands while it is out.
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XA0000002"), row("XU0000007")))
        afterSnapshot.set { runBlocking { assertTrue(w.dataCache.saveCourses(read.map { it.copy(customColorHex = "#ABCDEF") }, TERM, signIn)) } }
        rig.home.sync.pull(state, signIn)

        assertEquals(mapOf("XA0000002" to false, "XU0000007" to false), provenance(TERM))
        uploadTaken("XA0000002", "XU0000007")
        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)
        assertEquals(listOf("XA0000002", "XU0000007"), uploads().last().courseNos().sorted())
        assertEquals(emptyList<String>(), uploads().last().adds())
    }

    /**
     * Greptile on PR #687 (P1): a pull's snapshot lists a merged course as added by hand, and a
     * school fetch then saves it as the school's, as its roster upload sends it. The pull's step
     * does not put the older snapshot's word back over the fetch's: a provenance write applies only
     * over what it read (compare-and-set), and a write since the sync began is newer than its
     * snapshot. So no later upload names the course, an add the student never made.
     */
    @Test
    fun `a school fetch's word on a copy, saved while the pull's snapshot was out, stands over the snapshot's`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XE0000005")), TERM, signIn))
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XE0000005", source = "user_added", addedHlc = SERVER_HLC - 5_000)))
        afterSnapshot.set { runBlocking { schoolFetchLists("XE0000005") } }

        rig.home.sync.pull(state, signIn)

        assertEquals(mapOf<String, Boolean?>("XE0000005" to false), provenance(TERM))
        uploadTaken("XE0000005")
        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)
        assertEquals(listOf("XE0000005"), uploads().single().courseNos())
        assertEquals(emptyList<String>(), uploads().single().adds())
    }

    /**
     * Codex on round 6 (P2): Home's upload of a term the snapshot holds nothing of goes as the term
     * is committed, read under the lock a school fetch commits and uploads under, not as the pull
     * read it before: a copy the fetch made the school's meanwhile goes unnamed. Here the fetch holds
     * that lock while the pull tells the copy, and commits once the pull has.
     */
    @Test
    fun `Home's upload of a term the snapshot holds nothing of goes as a school fetch committed it meanwhile`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XU0000007")), TERM, signIn))
        backend.answer("GET /v4/sync/full", 200, snapshot())
        val held = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        val fetch = launch(Dispatchers.IO) {
            rig.home.adds.uploading {
                held.complete(Unit)
                commit.await()
                schoolFetchLists("XU0000007")
            }
        }
        held.await()

        val pull = launch(Dispatchers.IO) { rig.home.sync.pull(state, signIn) }
        withTimeout(10_000) { while (!pull.isCompleted && provenance(TERM)["XU0000007"] != true) delay(10) }
        commit.complete(Unit)
        fetch.join()
        pull.join()

        val upload = uploadsOf(TERM).single()
        assertEquals(listOf("XU0000007"), upload.courseNos())
        assertEquals(emptyList<String>(), upload.adds())
    }

    /** The background's pull covers its own term alone: another term's untold copy stays untold. */
    @Test
    fun `a background pull tells a copy the snapshot holds nothing of in its own term alone`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        w.appPrefs.semesterCatalogTerms = listOf(TERM, PAST)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XA0000002"), olderCopy("XU0000007")), TERM, signIn))
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XU0000008", PAST)), PAST, signIn))
        backend.answer("GET /v4/sync/full", 200, snapshot(row("XA0000002")))

        assertEquals(SyncOutcome.SYNCED, backgroundPass())

        assertEquals(mapOf("XA0000002" to false, "XU0000007" to true), provenance(TERM))
        assertEquals(mapOf<String, Boolean?>("XU0000008" to null), provenance(PAST))
    }

    @Test
    fun `a copy an older build merged of a row added by hand elsewhere is named once Home's pull lists it`() =
        olderBuildsCopiesAfter { rig.home.sync.pull(state, signIn) }

    @Test
    fun `a copy an older build merged of a row added by hand elsewhere is named once a background pull lists it`() =
        olderBuildsCopiesAfter { assertEquals(SyncOutcome.SYNCED, backgroundPass()) }

    /**
     * Codex on rounds 3 and 4 (P2): a copy cached before this build has not been told what its row
     * is, and goes in no upload until a `/v4` pull tells it. Named, a school row would take an add
     * that keeps every roster from dropping it; listed unnamed, a `user_added` row would turn into a
     * school row. Left out, the backend changes nothing. The class table's delete and its upload go
     * here before any pull; the pull then tells each copy what its row is, and the next upload, which
     * the ledger does not take for the last one, lists both copies and names the hand-added row's.
     */
    @Test
    fun `a copy no v4 pull has told about goes in no upload until one tells it what its row is`() = runBlocking {
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
        val addedHere = Course(courseNo = "XH0000009", courseName = "Added here", isManual = true)
        assertTrue(w.dataCache.saveCourses(listOf(olderCopy("XA0000001"), olderCopy("XA0000002"), olderCopy("XE0000005"), addedHere), TERM, signIn))

        deleteOnTheClassTable("XA0000001")

        val afterDelete = uploads().single()
        assertEquals(listOf("XH0000009"), afterDelete.courseNos())
        assertEquals(listOf(key("XH0000009")), afterDelete.adds())

        val byHand = SERVER_HLC - 5_000
        backend.answer(
            "GET /v4/sync/full", 200,
            snapshot(row("XA0000002"), row("XE0000005", source = "user_added", addedHlc = byHand), row("XH0000009", source = "user_added", addedHlc = byHand)),
        )
        rig.home.sync.pull(state, signIn)
        uploadTaken("XA0000002", "XE0000005", "XH0000009")
        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)

        assertEquals(2, uploads().size)
        val told = uploads().last()
        assertEquals(listOf("XA0000002", "XE0000005", "XH0000009"), told.courseNos().sorted())
        assertEquals(listOf(key("XE0000005"), key("XH0000009")), told.adds().sorted())
        assertEquals(emptyList<String>(), rules.refused)
    }

    /**
     * A copy the school's fetch lists is the school's course from then on, whatever a pull told it:
     * the fetch's merge says so ([ClassTableCourseMerge.mergeFetched], which every fetch folds
     * through), and the term's upload lists it unnamed. A copy the school does not list, which no
     * pull has told about, goes in no upload, a fetch's with no roster included.
     */
    @Test
    fun `a copy the school's fetch lists goes as the school's, and one it does not list goes in no upload`() = runBlocking {
        val cached = listOf(olderCopy("XA0000002"), olderCopy("XE0000005"))
        assertTrue(w.dataCache.saveCourses(cached, TERM, signIn))
        val fetched = listOf(Course(courseNo = "XA0000002", courseName = "School XA0000002", moodleIdNumber = "${TERM}XA0000002"))
        assertTrue(w.dataCache.updateCourses(TERM, signIn) { current, hidden -> ClassTableCourseMerge.mergeFetched(fetched, current, emptyMap(), "zh", hidden) })
        uploadTaken("XA0000002")

        rig.pushApi.uploadCourses(w.dataCache.loadCourses(TERM), TERM, signIn)

        val upload = uploads().single()
        assertEquals(listOf("XA0000002"), upload.courseNos())
        assertEquals(emptyList<String>(), upload.adds())
    }

    /**
     * A roster upload lists no copy no pull has told about either. The school's fetch tells every
     * copy it lists, so such a copy is one the school does not list: a school row the roster drops,
     * or a hand-added one roster pruning skips.
     */
    @Test
    fun `a roster upload leaves out a copy no v4 pull has told about`() = runBlocking {
        val school = Course(courseNo = "XA0000001", courseName = "School XA0000001", moodleIdNumber = "${TERM}XA0000001")
        val courses = listOf(school, olderCopy("XA0000002"))
        assertTrue(w.dataCache.saveCourses(courses, TERM, signIn))
        uploadTaken("XA0000001")

        rig.pushApi.uploadCourses(courses, TERM, signIn, rosterTerms = listOf(TERM))

        val roster = uploads().single()
        assertEquals(listOf("XA0000001"), roster.courseNos())
        assertEquals(emptyList<String>(), roster.adds())
        assertEquals(TERM, roster.getJSONArray("roster_terms").getString(0))
    }

    /** The worker's pass, off the main thread, whose looper runs meanwhile, as `SynchronizerBackgroundTest` runs it. */
    private fun backgroundPass(): SyncOutcome {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val run = thread.submit(Callable { runBlocking { rig.home.synchronizer.sync(SyncMode.BACKGROUND, signIn) } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!run.isDone) {
                check(System.nanoTime() < deadline) { "the background pass never finished" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            return run.get()
        } finally {
            thread.shutdownNow()
        }
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val STORED = "http://127.0.0.1:9/v3"
        const val TERM = "1151"
        const val PAST = "1141"
        const val SERVER_HLC = 1_793_000_000_000_000L
        val IN_TERM: LocalDateTime = LocalDateTime.of(2026, 10, 15, 10, 0)
        const val FIRST_RUN_MS = 1_760_000_000_000L
    }
}
