package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * `GET /sync/full` read into a [BackendSyncResult]. The `/v3` snapshot (`sync/v3/full.json`,
 * written from the backend's `server/routes/sync/snapshot.py` as it answers an app that sends no
 * protocol header) pins every field the app reads today, so the parser can move and grow without
 * changing a value it gives the syncs. The `/v4` snapshots (`sync/v4/`, from the same route and
 * `docs/api-v4.md`) hold the same rows in protocol 2's shape, with its stamps, tombstone kinds and
 * resets. Course numbers and ids are made up.
 */
class FullSyncSnapshotTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val v4 = "http://127.0.0.1:9/v4"

    private fun v4(fixture: String, heard: (Long) -> Unit = {}) =
        parseFullSync(JSONObject(syncFixture("v4/$fixture")), protocol2 = true, heard = heard)

    /** [this] with every protocol 2 field null: what a `/v3` sync reads. */
    private fun BackendSyncResult.released() = copy(
        serverHlc = null,
        coursesResetHlc = null,
        termResets = null,
        marks = null,
        holidays = null,
        serverCourses = serverCourses.map { it.copy(addedHlc = null) },
        courseOverrides = courseOverrides.map { it.copy(courseKey = null, colorHexHlc = null, customNamesHlc = null, ignoreAssignmentsHlc = null) },
        tombstones = tombstones.map { it.copy(hlc = null, kind = null) },
    )

    @Test
    fun `a v3 snapshot reads every field as today`() = runTest {
        val backend = RecordingBackend().apply { answer("/v3/sync/full", 200, syncFixture("v3/full.json")) }
        val before = System.currentTimeMillis()

        val result = syncApiClientAt(v3, backend.client).fetchFullSync(testSignIn())

        assertTrue("fetched at ${result.fetchedAtMs}", result.fetchedAtMs in before..System.currentTimeMillis())
        assertEquals(V3_SNAPSHOT, result.copy(fetchedAtMs = 0L))
    }

    @Test
    fun `the parser reads it so on its own, the roster requests and fetch time being the fetch's`() {
        assertEquals(V3_SNAPSHOT.copy(rosterRequests = emptyList()), parseFullSync(JSONObject(syncFixture("v3/full.json"))))
    }

    /** `/v3` sends the stamps as well; read as `/v3`'s, they stay unread and unheard. */
    @Test
    fun `a v3 snapshot leaves every protocol 2 field null, and is heard by no clock`() {
        val heard = mutableListOf<Long>()

        val read = parseFullSync(JSONObject(syncFixture("v3/full.json")), protocol2 = false) { heard += it }

        assertEquals(V3_SNAPSHOT.copy(rosterRequests = emptyList()), read)
        assertEquals(emptyList<Long>(), heard)
    }

    @Test
    fun `a v4 snapshot fills every protocol 2 field`() {
        assertEquals(V4_SNAPSHOT, v4("full.json"))
    }

    @Test
    fun `a v4 snapshot's existing fields read as v3's do, the tombstones' authorship absent reading false`() {
        val released = V3_SNAPSHOT.copy(
            rosterRequests = emptyList(),
            tombstones = V3_SNAPSHOT.tombstones.map { it.copy(deletedByReset = false, deletedByThisDevice = false) },
        )

        assertEquals(released, v4("full.json").released())
        assertEquals(released, parseFullSync(JSONObject(syncFixture("v4/full.json"))))
    }

    @Test
    fun `a snapshot fetched from v4 reads its existing fields as today`() = runTest {
        val backend = RecordingBackend().apply { answer("/v4/sync/full", 200, syncFixture("v4/full.json")) }

        val result = syncApiClientAt(v4, backend.client).fetchFullSync(testSignIn())

        assertEquals(V4_SNAPSHOT.released().copy(rosterRequests = listOf("1151")), result.released().copy(fetchedAtMs = 0L))
        assertEquals(listOf("$v4/sync/full"), backend.sent.map { it.url })
    }

    /** A clock that has heard nothing, its wall clock in 2020: its next stamp shows what it hears. */
    private fun quietClock() = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", 0)), { BackendApi.V4 }, { EARLY_MS })

    @Test
    fun `a snapshot fetched from v4 fills its stamps, and the clock hears the largest`() = runTest {
        val backend = RecordingBackend().apply { answer("/v4/sync/full", 200, syncFixture("v4/full.json")) }
        val clock = quietClock()

        val result = syncApiClientAt(v4, backend.client, clock).fetchFullSync(testSignIn())

        assertEquals(V4_SNAPSHOT.copy(rosterRequests = listOf("1151")), result.copy(fetchedAtMs = 0L))
        // The mark stored ahead of the backend's clock, as in the parser's own test, plus one tick.
        assertEquals(1_792_900_000_004L + 1, clock.stampMs())
    }

    /** `/v3` sends the stamps as well; fetched from there, they stay unread and unheard. */
    @Test
    fun `a snapshot fetched from v3 leaves its stamps null, and is heard by no clock`() = runTest {
        val backend = RecordingBackend().apply { answer("/v3/sync/full", 200, syncFixture("v3/full.json")) }
        val clock = quietClock()

        val result = syncApiClientAt(v3, backend.client, clock).fetchFullSync(testSignIn())

        assertEquals(V3_SNAPSHOT, result.copy(fetchedAtMs = 0L))
        assertEquals(EARLY_MS + 1, clock.stampMs())
    }

    /** Protocol 2 keeps them all (LWW D11): none older than 30 days or beside a listed course is left out. */
    @Test
    fun `a v4 snapshot keeps every tombstone, and reads each one's kind`() {
        val tombstones = v4("tombstones.json").tombstones

        assertEquals(
            listOf(
                CourseTombstone("client:1142:XA0000010", "XA0000010", "1142", "2026-01-05T00:00:00+00:00", hlc = 1767571200000000, kind = TombstoneKind.HIDE),
                CourseTombstone("client:1151:XA0000011", "XA0000011", "1151", "2026-10-26T03:00:00+00:00", hlc = 1792983600000000, kind = TombstoneKind.REMOVE),
                CourseTombstone("client:1151:XA0000012", "XA0000012", "1151", "2026-10-27T03:00:00+00:00", hlc = 1793070000000000, kind = TombstoneKind.RESET),
                // A kind this build does not know reads as none, and the tombstone stays.
                CourseTombstone("client:1151:XA0000013", "XA0000013", "1151", "2026-10-28T03:00:00+00:00", hlc = 1793156400000000, kind = null),
                CourseTombstone("client:1151:XA0000014", null, "1151", "2026-10-29T03:00:00+00:00", hlc = 1793242800000000, kind = TombstoneKind.HIDE),
            ),
            tombstones,
        )
        assertTrue("authorship absent reads false", tombstones.none { it.deletedByReset || it.deletedByThisDevice })
    }

    @Test
    fun `every mark row is read with its stamp, none included, the later edit of an assignment kept`() {
        val read = v4("marks.json")

        assertEquals(
            mapOf(
                "9101" to StampedValue("none", 1792800000000200),
                "9102" to StampedValue("locally_completed", 1792800000000300),
                "9103" to StampedValue("archived", null),
                "9104" to StampedValue("none", 1792800000000400),
                // Neither stamped: the later row.
                "9105" to StampedValue("none", null),
            ),
            read.marks,
        )
        // The released sets read every row, as today.
        assertEquals(setOf("9101", "9102", "9103", "9105"), read.ignoredIds)
        assertEquals(setOf("9102"), read.completedIds)
    }

    @Test
    fun `a v4 snapshot without a section reads it as not sent`() {
        val read = parseFullSync(JSONObject("""{"current_revision": 3, "assignments": []}"""), protocol2 = true)

        assertEquals(listOf(null, null, null, null, null), listOf(read.serverHlc, read.coursesResetHlc, read.termResets, read.marks, read.holidays))
    }

    @Test
    fun `the parser hears the largest clock value it reads, once`() {
        val heard = mutableListOf<Long>()

        v4("full.json") { heard += it }

        // A mark stored ahead of the backend's clock as the snapshot began, as five seconds' skew allows.
        assertEquals(listOf(1792900000004000L), heard)
    }

    @Test
    fun `every stamp the parser reads can be the one it hears`() {
        val later = 1793000000000000L
        val stamps: List<(JSONObject) -> Unit> = listOf(
            { it.put("server_hlc", later) },
            { it.put("courses_reset_hlc", later) },
            { it.getJSONObject("term_resets").put("1151", later) },
            { it.getJSONArray("assignment_overrides").getJSONObject(0).put("local_status_hlc", later) },
            { it.getJSONArray("holiday_overrides").getJSONObject(1).put("hlc", later) },
            { it.getJSONArray("courses").getJSONObject(0).put("added_hlc", later) },
            { it.getJSONArray("course_overrides").getJSONObject(0).put("color_hex_hlc", later) },
            { it.getJSONArray("course_overrides").getJSONObject(0).getJSONObject("custom_names_hlc").put("zh", later) },
            { it.getJSONArray("course_overrides").getJSONObject(1).put("ignore_assignments_hlc", later) },
            { it.getJSONArray("course_tombstones").getJSONObject(2).put("hlc", later) },
        )
        for ((i, stamp) in stamps.withIndex()) {
            val json = JSONObject(syncFixture("v4/full.json")).also(stamp)
            val heard = mutableListOf<Long>()

            parseFullSync(json, protocol2 = true) { heard += it }

            assertEquals("stamp $i", listOf(later), heard)
        }
    }

    @Test
    fun `a snapshot with no clock value is heard by nothing`() {
        val heard = mutableListOf<Long>()

        parseFullSync(JSONObject("""{"current_revision": 3, "assignments": [], "course_tombstones": []}"""), protocol2 = true) { heard += it }

        assertEquals(emptyList<Long>(), heard)
    }

    @Test
    fun `a snapshot without assignments reads only its revision`() = runTest {
        val backend = RecordingBackend().apply {
            answer("/v3/sync/full", 200, """{"current_revision": 9, "courses": [{"course_no": "XA0000001"}]}""")
        }

        val result = syncApiClientAt(v3, backend.client).fetchFullSync(testSignIn())

        assertEquals(
            BackendSyncResult(emptySet(), emptySet(), emptyList(), emptySet(), emptyList(), currentRevision = 9),
            result.copy(fetchedAtMs = 0L),
        )
    }

    internal companion object {
        /** 2020-09-13, a wall clock behind every clock value the fixtures hold. */
        const val EARLY_MS = 1_600_000_000_000L

        /** What `sync/v3/full.json` reads as, but its fetch time. */
        val V3_SNAPSHOT = BackendSyncResult(
            ignoredIds = setOf("9001", "9004"),
            completedIds = setOf("9002"),
            courseOverrides = listOf(
                CourseOverrideResult(
                    moodleCourseId = "1151XA0000001",
                    courseNo = "XA0000001",
                    colorHex = "#FF0000",
                    customNames = mapOf("en" to "Renamed One", "zh" to ""),
                    semester = "1151",
                    ignoreAssignments = true,
                    ignoreAssignmentsUpdatedAt = "2026-10-23T03:00:00+00:00",
                    moodleNumericCourseId = 4321,
                    colorHexUpdatedAt = "2026-10-23T02:00:00+00:00",
                ),
                CourseOverrideResult(
                    moodleCourseId = "1151XA0000003",
                    courseNo = "XA0000003",
                    colorHex = null,
                    customNames = emptyMap(),
                    semester = "1151",
                    ignoreAssignments = null,
                    ignoreAssignmentsUpdatedAt = null,
                    moodleNumericCourseId = null,
                    colorHexUpdatedAt = null,
                ),
            ),
            serverCourseNos = setOf("XA0000001", "XA0000002", "XA0000007"),
            serverCourses = listOf(
                ServerCourse(
                    courseNo = "XA0000001",
                    courseName = "Fake Course One",
                    semester = "1151",
                    instructors = listOf("Teacher A", "Teacher B"),
                    credits = 3.0f,
                    classroom = "TR-313",
                    enrolledCount = 40,
                    maxCount = 60,
                    moodleId = "1151XA0000001",
                    scheduleJson = """{"1":[2,3]}""",
                    classroomMapJson = """{"TR-313":"T1"}""",
                    version = 3,
                    updatedAt = "2026-10-02T00:00:00+00:00",
                    id = 101L,
                    source = "roster",
                    enrollmentStatus = "enrolled",
                ),
                ServerCourse(
                    courseNo = "XA0000002",
                    courseName = "Fake Course Two",
                    semester = "1151",
                    instructors = emptyList(),
                    credits = 0f,
                    classroom = "",
                    enrolledCount = 0,
                    maxCount = 0,
                    moodleId = "1151XA0000002",
                    scheduleJson = "{}",
                    classroomMapJson = "{}",
                    version = 1,
                    updatedAt = null,
                    id = 102L,
                    source = "user_added",
                    enrollmentStatus = "dropped",
                ),
                ServerCourse(
                    courseNo = "XA0000007",
                    courseName = "XA0000007",
                    semester = "1142",
                    instructors = listOf("Teacher C"),
                    credits = 1.5f,
                    classroom = "IB-401",
                    enrolledCount = 12,
                    maxCount = 30,
                    moodleId = null,
                    scheduleJson = """{"3":[5]}""",
                    classroomMapJson = "{}",
                    version = 2,
                    updatedAt = null,
                    id = null,
                    source = null,
                    enrollmentStatus = null,
                ),
            ),
            currentRevision = 52,
            coursesResetAt = "2026-09-01T08:00:00+00:00",
            tombstones = listOf(
                CourseTombstone(
                    courseKey = "client:1151:XA0000004",
                    courseNo = "XA0000004",
                    semester = "1151",
                    deletedAt = "2026-10-20T02:00:00+00:00",
                    deletedByReset = false,
                    deletedByThisDevice = false,
                ),
                CourseTombstone(
                    courseKey = "client:1151:XA0000005",
                    courseNo = "XA0000005",
                    semester = "1151",
                    deletedAt = "2026-10-21T02:00:00+00:00",
                    deletedByReset = true,
                    deletedByThisDevice = false,
                ),
                CourseTombstone(
                    courseKey = "client:1151:XA0000006",
                    courseNo = null,
                    semester = "1151",
                    deletedAt = "2026-10-22T02:00:00+00:00",
                    deletedByReset = false,
                    deletedByThisDevice = true,
                ),
            ),
            fetchedAtMs = 0L,
            holidayOverrides = setOf(7),
            rosterRequests = listOf("1151"),
        )

        /** What `sync/v4/full.json`, the same rows in protocol 2's shape, reads as with its stamps. */
        val V4_SNAPSHOT = V3_SNAPSHOT.copy(
            rosterRequests = emptyList(),
            courseOverrides = listOf(
                V3_SNAPSHOT.courseOverrides[0].copy(
                    courseKey = "client:1151:XA0000001",
                    colorHexHlc = 1792723600000000,
                    customNamesHlc = mapOf("en" to 1792720000000000, "zh" to 1792720000000001),
                    ignoreAssignmentsHlc = 1792727200000000,
                ),
                V3_SNAPSHOT.courseOverrides[1].copy(courseKey = "client:1151:XA0000003", customNamesHlc = emptyMap()),
            ),
            serverCourses = V3_SNAPSHOT.serverCourses.map { if (it.courseNo == "XA0000002") it.copy(addedHlc = 1792700000000000) else it },
            tombstones = listOf(
                CourseTombstone("client:1151:XA0000004", "XA0000004", "1151", "2026-10-20T02:00:00+00:00", hlc = 1792461600000000, kind = TombstoneKind.HIDE),
                CourseTombstone("client:1151:XA0000005", "XA0000005", "1151", "2026-10-21T02:00:00+00:00", hlc = 1792548000000000, kind = TombstoneKind.RESET),
                CourseTombstone("client:1151:XA0000006", null, "1151", "2026-10-22T02:00:00+00:00", hlc = 1792634400000000, kind = TombstoneKind.REMOVE),
            ),
            serverHlc = 1792900000000000,
            coursesResetHlc = 1788249600000000,
            termResets = mapOf("1151" to 1789000000000000),
            marks = mapOf(
                "9001" to StampedValue("ignored", 1792800000000100),
                "9002" to StampedValue("locally_completed", 1792800000000200),
                "9004" to StampedValue("archived", 1792800000000300),
                "9005" to StampedValue("none", 1792900000004000),
            ),
            holidays = mapOf(7 to StampedValue(true, 1792800000000000), 8 to StampedValue(false, 1792800000000001)),
        )
    }
}
