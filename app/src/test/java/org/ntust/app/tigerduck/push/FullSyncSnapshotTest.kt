package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.util.testSignIn

/**
 * `GET /sync/full` read into a [BackendSyncResult]. The `/v3` snapshot (`sync/v3/full.json`,
 * written from the backend's `server/routes/sync/snapshot.py` as it answers an app that sends no
 * protocol header) pins every field the app reads today, so the parser can move and grow without
 * changing a value it gives the syncs. Course numbers and ids are made up.
 */
class FullSyncSnapshotTest {

    private val v3 = "http://127.0.0.1:9/v3"

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
    }
}
