package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException

/**
 * Use Local for courses ([keepLocalCourses]) against [FakeCourseServer],
 * which keeps the course rules of tigerduck-backend's
 * `server/routes/sync/courses.py` and `uploads.py`
 * (tigerduck-app/tigerduck-app-android#153). It used to reset every term at
 * once and upload the current term's cache: the reset erased every tombstone
 * and the next roster upload brought a course deleted here back everywhere.
 */
class KeepLocalCoursesTest {

    private inline fun <reified T : Throwable> assertThrown(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun roster(vararg courseNos: String) = courseNos.map { Course(courseNo = it, courseName = it) }

    /** What a refresh does after Use Local: upload the term's whole roster, hidden course included. */
    private suspend fun FakeCourseServer.uploadRoster(semester: String, vararg courseNos: String) =
        uploadCourses(roster(*courseNos), semester)

    @Test
    fun `a course deleted here stays deleted after Use Local and the next roster upload`() = runTest {
        // The delete never reached the server: course sync was off.
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))

        keepLocalCourses(listOf("1151" to roster("CS1")), hiding = setOf("1151:CS2"), backend = server)
        assertEquals(setOf("client:1151:CS1"), server.rows)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a course whose delete did reach the server stays deleted too`() = runTest {
        val server = FakeCourseServer(
            rows = setOf("client:1151:CS1"),
            tombstones = mutableMapOf("client:1151:CS2" to false),
        )

        keepLocalCourses(listOf("1151" to roster("CS1")), hiding = setOf("1151:CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a hidden course still in the cache stays deleted`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))

        keepLocalCourses(listOf("1151" to roster("CS1", "CS2")), hiding = setOf("1151:CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a hidden course in a term with nothing cached stays deleted, with no reset`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS2", "client:1142:CS9"))

        keepLocalCourses(listOf("1151" to emptyList()), hiding = setOf("1151:CS2"), backend = server)

        assertEquals(emptyList<String>(), server.resets)
        server.uploadRoster("1151", "CS2")
        assertEquals(setOf("client:1142:CS9"), server.rows)
    }

    @Test
    fun `a legacy entry, which hides its course in every term, keeps it deleted`() = runTest {
        val server = FakeCourseServer(
            rows = setOf("client:1151:CS1"),
            tombstones = mutableMapOf("client:1151:CS2" to false),
        )

        keepLocalCourses(listOf("1151" to roster("CS1")), hiding = setOf("CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `hand-typed courses and every term with courses go back up`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1142:CS9"))
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)

        keepLocalCourses(
            listOf("1151" to roster("CS1") + manual, "1142" to roster("CS9", "CS8")),
            hiding = emptySet(),
            backend = server,
        )

        assertEquals(
            setOf("client:1151:CS1", "client:1151:MY1", "client:1142:CS9", "client:1142:CS8"),
            server.rows,
        )
    }

    @Test
    fun `only the terms it uploads are reset, one at a time and never all at once`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1142:CS9"))

        keepLocalCourses(
            listOf("1151" to roster("CS1"), "1142" to emptyList()),
            hiding = emptySet(),
            backend = server,
        )

        assertEquals(listOf("1151"), server.resets)
        assertEquals(setOf("client:1151:CS1", "client:1142:CS9"), server.rows)
    }

    @Test
    fun `a failed request is thrown, and a retry finishes the job`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))
        val terms = listOf("1151" to roster("CS1", "CS2"))
        server.failingDeletes = 1

        assertThrown<IOException> { keepLocalCourses(terms, hiding = setOf("1151:CS2"), backend = server) }
        keepLocalCourses(terms, hiding = setOf("1151:CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a term whose portal courses are not cached here is not reset`() = runTest {
        // The cache evicted the term's portal rows; only a hand-typed course is left.
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)

        keepLocalCourses(
            listOf("1151" to listOf(manual)),
            hiding = setOf("1151:CS2"),
            backend = server,
            portalCached = { false },
        )

        assertEquals(emptyList<String>(), server.resets)
        assertEquals(setOf("client:1151:CS1", "client:1151:MY1"), server.rows)
    }

    @Test
    fun `custom names a term reset wiped go back up`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))
        server.names["client:1151:CS1"] = mutableMapOf("zh" to "微積分")
        val withId = Course(courseNo = "CS2", courseName = "CS2", moodleIdNumber = "1151CS2X")

        keepLocalCourses(
            listOf("1151" to roster("CS1") + withId),
            hiding = emptySet(),
            backend = server,
            customNames = mapOf(
                "CS1" to mapOf("zh" to "微積分", "en" to "Calc"),
                "CS2" to mapOf("en" to "OS"),
                "CS9" to mapOf("en" to "Not on the timetable"),
            ),
        )

        assertEquals(mapOf("zh" to "微積分", "en" to "Calc"), server.names["client:1151:CS1"])
        assertEquals(mapOf("en" to "OS"), server.names["client:1151:CS2"])
    }

    @Test
    fun `a name that fails to go up still leaves the hidden courses deleted`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))
        server.failingNames = true

        val thrown = runCatching {
            keepLocalCourses(
                listOf("1151" to roster("CS1", "CS2")),
                hiding = setOf("1151:CS2"),
                backend = server,
                customNames = mapOf("CS1" to mapOf("en" to "Calc")),
            )
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is IOException)
        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a hidden course's name is not sent`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))

        keepLocalCourses(
            listOf("1151" to roster("CS1", "CS2")),
            hiding = setOf("1151:CS2"),
            backend = server,
            customNames = mapOf("CS2" to mapOf("en" to "Gone")),
        )

        assertEquals(emptyMap<String, Map<String, String>>(), server.names)
    }

    @Test
    fun `nothing more is sent once the sign-in it was answered under is gone`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1142:CS9"))
        var checks = 0

        assertThrown<CancellationException> {
            keepLocalCourses(
                listOf("1151" to roster("CS1"), "1142" to roster("CS9")),
                hiding = emptySet(),
                backend = server,
                // Signed out once the first request has gone.
                isCurrent = { ++checks == 1 },
            )
        }

        assertEquals(listOf("1151"), server.resets)
        assertEquals(setOf("client:1142:CS9"), server.rows)
    }
}

/**
 * What Use Local does around its requests ([useLocalCourses]): its terms are
 * latched and stamped as a class-table reset's are, so no sync reconciles a
 * term half-way through, and a failure asks the question again.
 */
class UseLocalCoursesTest {

    private val events = mutableListOf<String>()
    private val held = mutableSetOf<String>()
    private var current = true

    private val host = object : UseLocalHost {
        override fun beginReset(semester: String): Boolean = held.add(semester).also { if (it) events += "latch $semester" }
        override fun endReset(semester: String) {
            held.remove(semester)
            events += "release $semester"
        }
        override suspend fun stampReset(semester: String, atMs: Long) {
            events += "stamp $semester"
        }
        override fun askAgain() {
            events += "ask again"
        }
    }

    private fun roster(vararg courseNos: String) = courseNos.map { Course(courseNo = it, courseName = it) }

    private suspend fun run(server: CourseSyncBackend, vararg terms: Pair<String, List<Course>>) = useLocalCourses(
        terms = terms.toList(),
        hiding = emptySet(),
        backend = server,
        customNames = emptyMap(),
        portalCached = { true },
        latch = UseLocalLatch(terms.map { it.first }, host),
        host = host,
        now = { 42L },
        isCurrent = { current },
    )

    private class Failing : CourseSyncBackend {
        override suspend fun deleteAllCourses(semester: String) = throw IOException("offline")
        override suspend fun uploadCourses(courses: List<Course>, semester: String) = Unit
        override suspend fun deleteCourse(courseKey: String) = Unit
        override suspend fun patchCustomName(moodleId: String, name: String, locale: String) = Unit
    }

    private class SwitchedOff : CourseSyncBackend {
        override suspend fun deleteAllCourses(semester: String) = throw CourseSyncOffException()
        override suspend fun uploadCourses(courses: List<Course>, semester: String) = throw CourseSyncOffException()
        override suspend fun deleteCourse(courseKey: String) = throw CourseSyncOffException()
        override suspend fun patchCustomName(moodleId: String, name: String, locale: String) =
            throw CourseSyncOffException()
    }

    private class Recording(private val onRequest: () -> Unit = {}) : CourseSyncBackend {
        override suspend fun deleteAllCourses(semester: String) = onRequest()
        override suspend fun uploadCourses(courses: List<Course>, semester: String) = onRequest()
        override suspend fun deleteCourse(courseKey: String) = onRequest()
        override suspend fun patchCustomName(moodleId: String, name: String, locale: String) = onRequest()
    }

    @Test
    fun `it reports the terms it reset once their upload landed, a failure's included`() = runTest {
        // 1151 is reset and uploaded; 1142's reset lands, its upload fails;
        // 1132 has nothing cached and is not reset. The course ignores follow
        // the reset terms (tigerduck-app-android#307).
        var uploads = 0
        val server = object : CourseSyncBackend {
            override suspend fun deleteAllCourses(semester: String) = Unit
            override suspend fun uploadCourses(courses: List<Course>, semester: String) {
                if (++uploads == 2) throw IOException("connection lost")
            }
            override suspend fun deleteCourse(courseKey: String) = Unit
            override suspend fun patchCustomName(moodleId: String, name: String, locale: String) = Unit
        }

        val reset = run(server, "1151" to roster("CS1"), "1142" to roster("CS9"), "1132" to emptyList())

        assertEquals(setOf("1151"), reset)
        assertEquals("ask again", events.first { it == "ask again" })
    }

    @Test
    fun `its terms are latched for the requests, stamped, and only then released`() = runTest {
        run(Recording { events += "request" }, "1151" to roster("CS1"))

        assertEquals(listOf("latch 1151", "request", "request", "stamp 1151", "release 1151"), events)
    }

    @Test
    fun `a failed request asks the question again, and still releases its terms`() = runTest {
        run(Failing(), "1151" to roster("CS1"))

        assertEquals(listOf("latch 1151", "ask again", "stamp 1151", "release 1151"), events)
        assertEquals(emptySet<String>(), held)
    }

    @Test
    fun `course sync switched off midway asks nothing, since turning it back on asks anyway`() = runTest {
        // Asked now, the question would come back with course sync off.
        run(SwitchedOff(), "1151" to roster("CS1"))

        assertEquals(listOf("latch 1151", "stamp 1151", "release 1151"), events)
    }

    @Test
    fun `a sign-out midway asks nothing of the next account and stamps nothing, but releases its terms`() = runTest {
        val thrown = runCatching {
            run(Recording { current = false }, "1151" to roster("CS1"), "1142" to roster("CS9"))
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is CancellationException)
        assertEquals(listOf("latch 1151", "latch 1142", "release 1151", "release 1142"), events)
    }

    @Test
    fun `a term a class-table reset holds is left for that reset to release`() = runTest {
        held += "1142"

        run(Recording(), "1151" to roster("CS1"), "1142" to roster("CS9"))

        assertEquals(setOf("1142"), held)
        assertTrue("release 1142" !in events)
    }
}

/**
 * Use Local takes its terms' latches before the courses re-enable check lets
 * the course list go ([handOverCourseHold]), and lets them go once
 * ([UseLocalLatch]). Taken later, a sync in between un-hid the course
 * deleted here before Use Local could send its delete (#153 again).
 */
class UseLocalLatchTest {

    private val events = mutableListOf<String>()
    private val held = mutableSetOf<String>()

    private val host = object : UseLocalHost {
        override fun beginReset(semester: String): Boolean = held.add(semester).also { if (it) events += "latch $semester" }
        override fun endReset(semester: String) {
            held.remove(semester)
            events += "release $semester"
        }
        override suspend fun stampReset(semester: String, atMs: Long) = Unit
        override fun askAgain() = Unit
    }

    @Test
    fun `the terms are latched before the course list's hold goes`() {
        val latch = handOverCourseHold(useLocal = true, semesters = { listOf("1151", "1142") }, host = host) {
            events += "hold released"
        }

        assertEquals(listOf("latch 1151", "latch 1142", "hold released"), events)
        assertEquals(listOf("1151", "1142"), latch?.held)
    }

    @Test
    fun `Use Server latches nothing`() {
        val latch = handOverCourseHold(useLocal = false, semesters = { listOf("1151") }, host = host) {
            events += "hold released"
        }

        assertEquals(null, latch)
        assertEquals(listOf("hold released"), events)
    }

    @Test
    fun `a latch lets its terms go once, never a later reset's`() {
        val latch = UseLocalLatch(listOf("1151"), host)
        latch.release()
        // A class-table reset of the term starts.
        host.beginReset("1151")

        latch.release()

        assertEquals(setOf("1151"), held)
    }
}

/**
 * One device's view of the backend: rows and tombstones by course key, every
 * tombstone written by this device. A tombstone's value is its
 * `deleted_by_reset`: a reset tombstone does not bind the device that wrote
 * it, so its next upload releases it; a single delete binds every device.
 */
private class FakeCourseServer(
    rows: Set<String>,
    val tombstones: MutableMap<String, Boolean> = mutableMapOf(),
) : CourseSyncBackend {
    val rows = rows.toMutableSet()

    /** The terms reset, in order. */
    val resets = mutableListOf<String>()

    /** How many of the next [deleteCourse] calls fail before reaching the server. */
    var failingDeletes = 0

    /**
     * Custom names by course key. The override row they live on goes with
     * its course row (`ON DELETE CASCADE` in the backend's
     * `server/sync/models/courses.py`).
     */
    val names = mutableMapOf<String, MutableMap<String, String>>()

    // The moodle_id each row was stored under, which the override PATCH
    // resolves: the uploaded one, or "{semester}{course_no}" without one.
    private val moodleIds = rows.associateWith { it.removePrefix("client:").replace(":", "") }.toMutableMap()

    override suspend fun deleteAllCourses(semester: String) {
        resets += semester
        val prefix = "client:$semester:"
        val removed = rows.filter { it.startsWith(prefix) }
        rows -= removed.toSet()
        removed.forEach(names::remove)
        for (key in tombstones.keys.filter { it.startsWith(prefix) }) tombstones[key] = true
        for (key in removed) tombstones[key] = true
    }

    override suspend fun uploadCourses(courses: List<Course>, semester: String) {
        for (course in courses) {
            val key = "client:$semester:${course.courseNo}"
            if (tombstones[key] == true) tombstones.remove(key)
            if (key in tombstones) continue
            rows += key
            moodleIds[key] = course.moodleIdNumber?.takeIf { it.isNotEmpty() } ?: "$semester${course.courseNo}"
        }
    }

    override suspend fun deleteCourse(courseKey: String) {
        if (failingDeletes > 0) {
            failingDeletes--
            throw IOException("connection lost")
        }
        if (!rows.remove(courseKey)) return
        names.remove(courseKey)
        tombstones.putIfAbsent(courseKey, false)
    }

    /** Whether every name PATCH fails before reaching the server. */
    var failingNames = false

    override suspend fun patchCustomName(moodleId: String, name: String, locale: String) {
        if (failingNames) throw IOException("connection lost")
        val key = rows.firstOrNull { moodleIds[it] == moodleId } ?: throw IOException("HTTP 404")
        names.getOrPut(key) { mutableMapOf() }[locale] = name
    }
}
