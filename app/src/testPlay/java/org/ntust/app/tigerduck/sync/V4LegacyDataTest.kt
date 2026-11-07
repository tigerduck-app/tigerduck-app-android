package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What an upgraded student's older data does at the first `/v4` syncs (PR V, Codex fix round 1):
 * data a build from before hides had terms, or before a change kept its term, left on the phone,
 * which only `/v4`'s rules read. Over the app's own clients, queues and synchronizer
 * ([appSyncRig]), the endpoint setting and the session as the released build left them, the phone
 * in term [TERM] with [PAST] cached before it. Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V4LegacyDataTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val rules = V4WriteRules()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

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
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // The two terms as the school's calendar publishes them.
        context.getSharedPreferences("academic_calendar", Context.MODE_PRIVATE).edit()
            .putString(
                "payload",
                """{"revision":1,"semesters":[{"code":"$PAST","start":"2025-09-01","end":"2026-01-31"},""" +
                    """{"code":"$TERM","start":"2026-09-01","end":"2027-01-31"}],"holidays":[]}""",
            )
            .commit()
        val clock = DeviceClocks.wallOnly { NOW_MS }
        val client = appBackendClient(prefs, backend.client, rules)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client, backendLogin = client)
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS))
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE, issuedBy = STORED, studentId = STUDENT)
        }
        signIn = w.sessions.school.signIns.current
        assertEquals(TERM, w.dataCache.currentSemesterCode())
        w.appPrefs.semesterCatalogTerms = listOf(TERM, PAST)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        AppClock.setOverride(null)
    }

    private fun course(courseNo: String, term: String = TERM) =
        Course(courseNo = courseNo, courseName = "Course $courseNo", moodleIdNumber = "$term$courseNo")

    private fun synced(vararg categories: SyncCategory) {
        w.appPrefs.school.syncedCategories.set(categories.map { it.stored }.toSet(), signIn)
    }

    /** Each request after the first [from] whose endpoint has [part]: method and path with its query, then its body. */
    private fun sent(from: Int, part: String): List<String> = backend.endpoints().indices.drop(from).mapNotNull { i ->
        val endpoint = backend.endpoints()[i]
        if (part !in endpoint) return@mapNotNull null
        val request = backend.received(endpoint)[backend.endpoints().subList(0, i).count { it == endpoint }]
        val query = request.query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        "$endpoint$query | ${request.body}"
    }

    /** A `/v4` snapshot that holds nothing of this phone's courses: no rows, no tombstones. */
    private fun emptySnapshot() = JSONObject()
        .put("current_revision", 7)
        .put("server_hlc", 1_793_000_000_000_000L)
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put("courses", JSONArray())
        .put("course_overrides", JSONArray())
        .put("course_tombstones", JSONArray())
        .put("holiday_overrides", JSONArray())
        .toString()

    // --- Codex P2 1: a legacy bare hide at the first course sync ---

    @Test
    fun `a hide from before hides had terms stays through the first course sync, and goes up as its term's delete as the oldest`() =
        runBlocking {
            // The course list never synced here; every other category has.
            synced(SyncCategory.ASSIGNMENTS, SyncCategory.COURSE_COLORS, SyncCategory.COURSE_NAMES)
            assertTrue(w.dataCache.saveCourses(listOf(course("CS1001"), course("CS1002")), TERM, signIn))
            // As a build from before hides had terms left them: CS1001's, and one of a number no term here holds.
            w.dataCache.saveDeletedCourseNos(setOf("CS1001", "ZZ9999"), signIn)
            backend.answer("GET /v4/sync/full", 200, emptySnapshot())
            backend.answer(
                "POST /v4/sync/courses/upload", 200,
                """{"upserted":1,"skipped_tombstoned":0,"accepted_keys":["client:$TERM:CS1002"]}""",
            )
            val from = backend.endpoints().size

            rig.home.sync.pull(state, signIn)

            // Still hidden, now by its term, and waiting as that term's delete; the one no term holds stays as it was.
            assertEquals(setOf("$TERM:CS1001", "ZZ9999"), w.dataCache.loadDeletedCourseNos())
            assertTrue(CourseTombstoneKeys.isHidden("CS1001", TERM, w.dataCache.loadDeletedCourseNos()))
            assertEquals(setOf("CS1001"), rig.home.deletes.waitingIn(TERM))
            // The hidden course leaves the term's list, as every hidden one does (CourseSyncReconciler.committedCourses);
            // the visible one stays, and is the only one the term's upload lists.
            assertEquals(listOf("CS1002"), w.dataCache.loadCourses(TERM).map { it.courseNo })
            assertEquals(
                listOf(listOf("CS1002")),
                sent(from, "/courses/upload").map { line ->
                    val courses = JSONObject(line.substringAfter(" | ")).getJSONArray("courses")
                    (0 until courses.length()).map { courses.getJSONObject(it).getString("course_no") }
                },
            )

            rig.outbox.drain(signIn)

            // As the oldest: as old as the wall clock reads, which the backend holds at a year.
            assertEquals(listOf("DELETE /v4/sync/courses/client:$TERM:CS1001?age_ms=$NOW_MS | "), sent(from, "DELETE "))
            assertEquals(emptyList<String>(), rules.refused)
        }

    // --- Codex P2 2: a color or name an older build queued with no term, after a term rollover ---

    /** The course overrides' queue file as an older build left it, [rows] written straight to it, last written at [OVERRIDES_FILE_MS]. */
    private fun queuedByAnOlderBuild(rows: String) {
        val file = java.io.File(java.io.File(context.filesDir, "TigerDuckData").apply { mkdirs() }, "pending_course_overrides.json")
        file.writeText(rows)
        assertTrue(file.setLastModified(OVERRIDES_FILE_MS))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/account/$name")) { "missing fixture $name" }.readText().trimEnd()

    private fun colorTaken(key: String, hex: String) = backend.answer(
        "PATCH /v4/sync/courses/$key/override", 200,
        """{"color_hex":"$hex","color_hex_hlc":${OVERRIDES_FILE_MS * 1_000},"applied":["color_hex"]}""",
    )

    @Test
    fun `an older build's color with no term goes to its course in a past term`() = runBlocking {
        synced(*SyncCategory.entries.toTypedArray())
        // The committed fixture: 1141CS1001's color, queued with no term and no time.
        queuedByAnOlderBuild(fixture("queues/pending_course_overrides.json"))
        assertTrue(w.dataCache.saveCourses(listOf(course("CS1001", PAST)), PAST, signIn))
        assertTrue(w.dataCache.saveCourses(listOf(course("CS2001")), TERM, signIn))
        colorTaken("client:$PAST:CS1001", "#336699")
        val from = backend.endpoints().size

        rig.outbox.drain(signIn)

        assertEquals(
            listOf("""PATCH /v4/sync/courses/client:$PAST:CS1001/override | {"color_hex":"#336699","age_ms":${NOW_MS - OVERRIDES_FILE_MS}}"""),
            sent(from, "/override"),
        )
        assertEquals(emptyList<Any>(), w.dataCache.loadPendingCourseOverrides())
    }

    @Test
    fun `an older build's change whose Moodle id two past terms' courses share goes to the newer term's`() = runBlocking {
        synced(*SyncCategory.entries.toTypedArray())
        queuedByAnOlderBuild("""[{"moodleId":"SHARED01","colorHex":"#123456","changedAtMs":1700000002000,"attempts":0,"seq":0}]""")
        val shared = { courseNo: String -> Course(courseNo = courseNo, courseName = "Shared", moodleIdNumber = "SHARED01") }
        assertTrue(w.dataCache.saveCourses(listOf(shared("XA0000001")), OLDER, signIn))
        assertTrue(w.dataCache.saveCourses(listOf(shared("XB0000002")), PAST, signIn))
        assertTrue(w.dataCache.saveCourses(listOf(course("CS2001")), TERM, signIn))
        colorTaken("client:$PAST:XB0000002", "#123456")
        val from = backend.endpoints().size

        rig.outbox.drain(signIn)

        assertEquals(
            listOf("""PATCH /v4/sync/courses/client:$PAST:XB0000002/override | {"color_hex":"#123456","age_ms":${NOW_MS - OVERRIDES_FILE_MS}}"""),
            sent(from, "/override"),
        )
    }

    /**
     * iOS's fallback (`OutboxSender.termAndNumber`): no term here holds the course, and the Moodle id
     * reads as a catalogue term, one this phone never cached, followed by a course number.
     */
    @Test
    fun `an older build's change whose course no term here holds goes by the term and number its id names`() = runBlocking {
        synced(*SyncCategory.entries.toTypedArray())
        w.appPrefs.semesterCatalogTerms = listOf(TERM, PAST, OLDER)
        queuedByAnOlderBuild("""[{"moodleId":"${OLDER}CS1009","colorHex":"#445566","changedAtMs":1700000002000,"attempts":0,"seq":0}]""")
        assertTrue(w.dataCache.saveCourses(listOf(course("CS2001")), TERM, signIn))
        colorTaken("client:$OLDER:CS1009", "#445566")
        val from = backend.endpoints().size

        rig.outbox.drain(signIn)

        assertEquals(
            listOf("""PATCH /v4/sync/courses/client:$OLDER:CS1009/override | {"color_hex":"#445566","age_ms":${NOW_MS - OVERRIDES_FILE_MS}}"""),
            sent(from, "/override"),
        )
        assertEquals(emptyList<Any>(), w.dataCache.loadPendingCourseOverrides())
    }

    /**
     * Greptile on PR V (P1): the pull holds a waiting change by the course the send resolves it to,
     * the term and number its id names included. Its PATCH fails, and the next pull holds no
     * override of it: its name stays on this phone while the rename waits.
     */
    @Test
    fun `an older build's rename that goes by the term and number its id names keeps its name through a pull while it waits`() =
        runBlocking {
            synced(*SyncCategory.entries.toTypedArray())
            w.appPrefs.semesterCatalogTerms = listOf(TERM, PAST, OLDER)
            queuedByAnOlderBuild(
                """[{"moodleId":"${OLDER}CS1009","customName":"Mine","locale":"en","changedAtMs":1700000002000,"attempts":0,"seq":0}]""",
            )
            assertTrue(w.dataCache.saveCourseCustomNames(mapOf("CS1009" to mapOf("en" to "Mine")), signIn))
            assertTrue(w.dataCache.saveCourses(listOf(course("CS2001")), TERM, signIn))
            // Home shows its term, so the pull merges the names.
            state.allCourses.value = listOf(course("CS2001"))
            backend.answer("PATCH /v4/sync/courses/client:$OLDER:CS1009/override", 500)
            backend.answer("GET /v4/sync/full", 200, emptySnapshot())
            backend.answer(
                "POST /v4/sync/courses/upload", 200,
                """{"upserted":1,"skipped_tombstoned":0,"accepted_keys":["client:$TERM:CS2001"]}""",
            )
            val from = backend.endpoints().size

            rig.home.sync.pull(state, signIn)

            assertEquals(listOf("PATCH /v4/sync/courses/client:$OLDER:CS1009/override"), sent(from, "/override").map { it.substringBefore(" |") })
            assertEquals(mapOf("CS1009" to mapOf("en" to "Mine")), w.dataCache.loadCourseCustomNames())
            assertEquals(listOf("${OLDER}CS1009"), w.dataCache.loadPendingCourseOverrides().map { it.moodleId })
        }

    /**
     * One nothing resolves still parks: no term here holds its course, and its id begins with no
     * term of the catalogue's. Kept for an upload that brings its course. Each try is
     * the lookup alone, on this phone's files: the key is built before the request, so a try costs
     * no request and no session.
     */
    @Test
    fun `an older build's change nothing resolves parks, and its tries send nothing`() = runBlocking {
        synced(*SyncCategory.entries.toTypedArray())
        queuedByAnOlderBuild("""[{"moodleId":"1132ZZ0001","colorHex":"#112233","changedAtMs":1700000002000,"attempts":0,"seq":0}]""")
        assertTrue(w.dataCache.saveCourses(listOf(course("CS2001")), TERM, signIn))
        val from = backend.endpoints().size

        rig.outbox.drain(signIn)
        // Woken by a course upload that lands, as a parked change is, and drained again.
        rig.outbox.courseUploadLanded(signIn)
        rig.outbox.drain(signIn)

        assertEquals(emptyList<String>(), backend.endpoints().drop(from))
        assertEquals(listOf("1132ZZ0001"), w.dataCache.loadPendingCourseOverrides().map { it.moodleId })
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val STORED = "http://127.0.0.1:9/v3"
        const val TERM = "1151"
        const val PAST = "1141"
        const val OLDER = "1132"
        const val OVERRIDES_FILE_MS = 1_700_000_200_000L
        val IN_TERM: LocalDateTime = LocalDateTime.of(2026, 10, 15, 10, 0)
        const val FIRST_RUN_MS = 1_760_000_000_000L

        // The wall clock as the sync runs, ten minutes after this build's first run.
        const val NOW_MS = 1_760_000_600_000L
    }
}
