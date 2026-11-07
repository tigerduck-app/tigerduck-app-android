package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.util.testSignIn

/**
 * `GET /sync/full` read into a [BackendSyncResult]: the snapshots in `sync/v4/` (written from the
 * backend's `server/routes/sync/snapshot.py` and `docs/api-v4.md`) pin every field the app reads,
 * in protocol 2's shape, with its stamps, tombstone kinds and resets, so the parser can move and
 * grow without changing a value it gives the syncs. Course numbers and ids are made up.
 */
class FullSyncSnapshotTest {

    private val v4 = "http://127.0.0.1:9/v4"

    private fun v4(fixture: String) = parseFullSync(JSONObject(syncFixture("v4/$fixture")))

    @Test
    fun `a v4 snapshot fills every protocol 2 field`() {
        assertEquals(V4_SNAPSHOT, v4("full.json"))
    }

    @Test
    fun `a snapshot fetched reads every field, its roster requests and its fetch time`() = runTest {
        val backend = RecordingBackend().apply { answer("/v4/sync/full", 200, syncFixture("v4/full.json")) }
        val before = System.currentTimeMillis()

        val result = syncApiClientAt(v4, backend.client).fetchFullSync(testSignIn())

        assertTrue("fetched at ${result.fetchedAtMs}", result.fetchedAtMs in before..System.currentTimeMillis())
        assertEquals(V4_SNAPSHOT.copy(rosterRequests = listOf("1151")), result.copy(fetchedAtMs = 0L))
        assertEquals(listOf("$v4/sync/full"), backend.sent.map { it.url })
    }

    @Test
    fun `a snapshot fetched from v4 fills its stamps`() = runTest {
        val backend = RecordingBackend().apply { answer("/v4/sync/full", 200, syncFixture("v4/full.json")) }

        val result = syncApiClientAt(v4, backend.client).fetchFullSync(testSignIn())

        assertEquals(V4_SNAPSHOT.copy(rosterRequests = listOf("1151")), result.copy(fetchedAtMs = 0L))
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
        val read = parseFullSync(JSONObject("""{"current_revision": 3, "assignments": []}"""))

        assertEquals(listOf(null, null, null, null, null), listOf(read.serverHlc, read.coursesResetHlc, read.termResets, read.marks, read.holidays))
    }

    @Test
    fun `a snapshot without assignments reads only its revision`() = runTest {
        val backend = RecordingBackend().apply {
            answer("/v4/sync/full", 200, """{"current_revision": 9, "courses": [{"course_no": "XA0000001"}]}""")
        }

        val result = syncApiClientAt(v4, backend.client).fetchFullSync(testSignIn())

        assertEquals(
            BackendSyncResult(emptySet(), emptySet(), emptyList(), emptySet(), emptyList(), currentRevision = 9),
            result.copy(fetchedAtMs = 0L),
        )
    }

    internal companion object {
        /** The rows `sync/v4/full.json` holds, as every field the app read before protocol 2 reads them. */
        private val ROWS = BackendSyncResult(
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
                ),
                CourseTombstone(
                    courseKey = "client:1151:XA0000005",
                    courseNo = "XA0000005",
                    semester = "1151",
                    deletedAt = "2026-10-21T02:00:00+00:00",
                ),
                CourseTombstone(
                    courseKey = "client:1151:XA0000006",
                    courseNo = null,
                    semester = "1151",
                    deletedAt = "2026-10-22T02:00:00+00:00",
                ),
            ),
            fetchedAtMs = 0L,
            holidayOverrides = setOf(7),
            rosterRequests = listOf("1151"),
        )

        /** What `sync/v4/full.json`, those rows in protocol 2's shape, reads as with its stamps. */
        val V4_SNAPSHOT = ROWS.copy(
            rosterRequests = emptyList(),
            courseOverrides = listOf(
                ROWS.courseOverrides[0].copy(
                    courseKey = "client:1151:XA0000001",
                    colorHexHlc = 1792723600000000,
                    customNamesHlc = mapOf("en" to 1792720000000000, "zh" to 1792720000000001),
                    ignoreAssignmentsHlc = 1792727200000000,
                ),
                ROWS.courseOverrides[1].copy(courseKey = "client:1151:XA0000003", customNamesHlc = emptyMap()),
            ),
            serverCourses = ROWS.serverCourses.map { if (it.courseNo == "XA0000002") it.copy(addedHlc = 1792700000000000) else it },
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
