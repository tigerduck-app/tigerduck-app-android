package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse

class CourseIgnoreReconcilerTest {

    private fun row(semester: String, courseNo: String) =
        ServerCourse(courseNo = courseNo, courseName = courseNo, semester = semester)

    /** [setAt] is when a device set the course's setting: null on a row made for a colour. */
    private fun override(
        semester: String?,
        courseNo: String?,
        ignore: Boolean?,
        setAt: String? = ignore?.let { "2026-10-13T00:00:00Z" },
    ) = CourseOverrideResult(
        moodleCourseId = "$semester$courseNo",
        courseNo = courseNo,
        colorHex = null,
        semester = semester,
        ignoreAssignments = ignore,
        ignoreAssignmentsUpdatedAt = setAt,
    )

    private val rows = listOf(row("1141", "AAA"), row("1141", "BBB"), row("1141", "CCC"))

    @Test
    fun `an older backend, which sends no ignore field, leaves the set alone`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = null)),
            rows,
        )

        assertNull(server)
        assertNull(CourseIgnoreReconciler.afterSync(setOf("1141:AAA"), server, emptySet(), applies = true))
    }

    @Test
    fun `with assignment sync off or a re-enable conflict open nothing is applied`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "BBB", ignore = true)),
            rows,
        )

        assertNull(CourseIgnoreReconciler.afterSync(setOf("1141:AAA"), server, emptySet(), applies = false))
    }

    @Test
    fun `in a term the snapshot covers the backend decides`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false), override("1141", "BBB", ignore = true)),
            rows,
        )

        val after = CourseIgnoreReconciler.afterSync(
            setOf("1141:AAA", "1141:DDD"), server, emptySet(), applies = true,
        )

        assertEquals(setOf("1141:BBB"), after)
    }

    @Test
    fun `a listed course the backend holds no setting for keeps this device's and is sent again`() {
        // CCC has no override; BBB's was made for a colour.
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false), override("1141", "BBB", ignore = false, setAt = null)),
            rows,
        )!!
        val local = setOf("1141:AAA", "1141:BBB", "1141:CCC")

        assertEquals(setOf("1141:BBB", "1141:CCC"), CourseIgnoreReconciler.toResend(local, server, emptySet()))
        assertEquals(setOf("1141:CCC"), CourseIgnoreReconciler.toResend(local, server, setOf("1141:BBB")))
        assertEquals(
            setOf("1141:BBB", "1141:CCC"),
            CourseIgnoreReconciler.afterSync(local, server, emptySet(), applies = true),
        )
    }

    @Test
    fun `no overrides at all, as after the backend dropped them with the course rows, is no setting`() {
        val server = CourseIgnoreReconciler.serverIgnores(emptyList(), rows)!!

        assertEquals(setOf("1141:AAA"), CourseIgnoreReconciler.toResend(setOf("1141:AAA"), server, emptySet()))
        assertEquals(
            setOf("1141:AAA"),
            CourseIgnoreReconciler.afterSync(setOf("1141:AAA"), server, emptySet(), applies = true),
        )
    }

    @Test
    fun `terms reset since the snapshot was fetched are left out of it`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = true), override("1132", "OLD", ignore = true)),
            rows + row("1132", "OLD"),
            resetSemesters = setOf("1132"),
        )!!

        assertEquals(setOf("1141:AAA"), server.keys)
        assertEquals(setOf("1141:AAA", "1141:BBB", "1141:CCC"), server.listed)
        assertEquals(
            setOf("1132:OLD", "1141:AAA"),
            CourseIgnoreReconciler.afterSync(setOf("1132:OLD"), server, emptySet(), applies = true),
        )
    }

    @Test
    fun `taking the backend's set drops what it holds no setting for`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "BBB", ignore = true)),
            rows,
        )

        val adopted = CourseIgnoreReconciler.adopt(setOf("1141:AAA", "1132:OLD"), server)

        assertEquals(setOf("1132:OLD", "1141:BBB"), adopted)
        assertNull(CourseIgnoreReconciler.adopt(setOf("1141:AAA"), null))
    }

    @Test
    fun `a change not yet confirmed keeps this device's value either way`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false), override("1141", "BBB", ignore = true)),
            rows,
        )

        val after = CourseIgnoreReconciler.afterSync(
            local = setOf("1141:AAA"),
            server = server,
            protectedKeys = setOf("1141:AAA", "1141:BBB"),
            applies = true,
        )

        assertEquals(setOf("1141:AAA"), after)
    }

    @Test
    fun `a term without course rows in the snapshot is left alone`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false)),
            rows,
        )

        val after = CourseIgnoreReconciler.afterSync(
            setOf("1132:OLD", "1141:AAA"), server, emptySet(), applies = true,
        )

        assertEquals(setOf("1132:OLD"), after)
    }

    @Test
    fun `an override with no term or course number names no course`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("", "AAA", ignore = true), override("1141", null, ignore = true)),
            rows,
        )

        assertEquals(emptySet<String>(), server?.keys)
    }

    @Test
    fun `the re-enable check compares only the terms the snapshot covers`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = true)),
            rows,
        )

        assertTrue(CourseIgnoreReconciler.agrees(setOf("1141:AAA", "1132:OLD"), server))
        assertFalse(CourseIgnoreReconciler.agrees(setOf("1141:BBB"), server))
        assertTrue(CourseIgnoreReconciler.agrees(setOf("1141:BBB"), null))
        // BBB the backend holds no setting for, so a sync keeps it and sends it again.
        assertTrue(CourseIgnoreReconciler.agrees(setOf("1141:AAA", "1141:BBB"), server))
    }

    // ---- a course another device deleted and added back (tigerduck-app-android#182) ----

    private fun row(semester: String, courseNo: String, id: Long) =
        ServerCourse(courseNo = courseNo, courseName = courseNo, semester = semester, id = id)

    @Test
    fun `listed rows are keyed by term and course, and a row without an id is left out`() {
        assertEquals(
            mapOf("1151:CS1" to 9L),
            CourseIgnoreReconciler.listedRows(
                listOf(row("1151", "CS1", 9L), row("1151", "EE2"), ServerCourse("GE3", "GE3", "", id = 4L)),
            ),
        )
    }

    @Test
    fun `a course back with a new row while another row of its term kept its id was re-added`() {
        val seen = mapOf("1151:CS1" to 1L, "1151:EE2" to 2L, "1142:GE3" to 3L)

        assertEquals(setOf("1151:CS1"), CourseIgnoreReconciler.recreated(mapOf("1151:CS1" to 9L, "1151:EE2" to 2L), seen))
        assertEquals(
            "a term reset, by Use Local among others, beside a term that kept its rows was not re-added",
            emptySet<String>(),
            CourseIgnoreReconciler.recreated(mapOf("1151:CS1" to 9L, "1151:EE2" to 10L, "1142:GE3" to 3L), seen),
        )
    }

    @Test
    fun `no row kept its id, as after the backend dropped them all, so nothing counts as re-added`() {
        val seen = mapOf("1151:CS1" to 1L, "1151:EE2" to 2L)

        assertEquals(emptySet<String>(), CourseIgnoreReconciler.recreated(mapOf("1151:CS1" to 9L, "1151:EE2" to 10L), seen))
        assertEquals(
            "a course never recorded is not re-added",
            emptySet<String>(),
            CourseIgnoreReconciler.recreated(mapOf("1151:CS1" to 1L, "1151:PE4" to 12L), seen),
        )
        assertEquals(emptySet<String>(), CourseIgnoreReconciler.recreated(mapOf("1151:CS1" to 9L), emptyMap()))
    }

    @Test
    fun `a re-added course takes the server's setting instead of having this device's sent again`() {
        val listed = listOf(row("1151", "CS1", 9L), row("1151", "EE2", 2L))
        val server = CourseIgnoreReconciler.serverIgnores(
            overrides = emptyList(),
            serverCourses = listed,
            recreated = setOf("1151:CS1"),
        )!!
        val local = setOf("1151:CS1", "1151:EE2")

        assertEquals(setOf("1151:EE2"), CourseIgnoreReconciler.toResend(local, server, emptySet()))
        assertEquals(setOf("1151:EE2"), CourseIgnoreReconciler.afterSync(local, server, emptySet(), applies = true))
    }
}
