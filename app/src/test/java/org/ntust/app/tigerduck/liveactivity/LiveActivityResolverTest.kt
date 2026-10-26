package org.ntust.app.tigerduck.liveactivity

import java.time.ZonedDateTime
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The "assignment due soon" Live Update over what LiveActivityManager hands
 * the resolver: the outstanding assignments, not every cached one; which
 * scenario wins when several apply; and what a tap on each opens.
 */
class LiveActivityResolverTest {

    private val now = Date(1_700_000_000_000)
    private lateinit var preferences: LiveActivityPreferences

    @Before
    fun setUp() {
        preferences = LiveActivityPreferences(JvmContext())
    }

    private fun dueInHours(id: String, hours: Long) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(now.time + hours * 3_600_000),
    )

    private fun resolve(assignments: List<Assignment>) = LiveActivityResolver().resolve(
        courses = emptyList(),
        assignments = assignments,
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = now,
    )

    @Test
    fun `an ignored assignment due soon starts nothing`() {
        val all = listOf(dueInHours("ignored", 1))

        val snapshot = resolve(OutstandingAssignments.of(all, setOf("ignored"), emptySet()))

        assertNull(snapshot)
    }

    @Test
    fun `the next outstanding assignment is shown, not an earlier one marked done`() {
        val all = listOf(dueInHours("marked", 1), dueInHours("open", 2))

        val snapshot = resolve(OutstandingAssignments.of(all, emptySet(), setOf("marked")))

        assertEquals("assignment-open", snapshot?.sourceId)
    }

    // A course meeting twice on Mondays, in a different room each time.
    private val twice = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        classroom = "TR-313, TR-412",
        schedule = mapOf(1 to listOf("3", "4", "7", "8")),
        classroomMap = mapOf(
            "1-3" to "TR-313", "1-4" to "TR-313",
            "1-7" to "TR-412", "1-8" to "TR-412",
        ),
    )

    // 2026-10-12 is a Monday.
    private fun mondayAt(hour: Int, minute: Int) = Date.from(
        ZonedDateTime.of(2026, 10, 12, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE).toInstant()
    )

    private fun resolveClass(at: Date) = LiveActivityResolver().resolve(
        courses = listOf(twice),
        assignments = emptyList(),
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = at,
    )

    @Test
    fun `the morning block of a course meeting twice shows its own room`() {
        val snapshot = resolveClass(mondayAt(10, 30))!!

        assertEquals("10:20–12:10", snapshot.subtitle)
        assertEquals("TR-313", snapshot.locationText)
        assertEquals("class-CS2001301-3", snapshot.sourceId)
    }

    @Test
    fun `the afternoon block of a course meeting twice is a block of its own`() {
        val snapshot = resolveClass(mondayAt(15, 0))!!

        assertEquals("14:20–16:20", snapshot.subtitle)
        assertEquals("TR-412", snapshot.locationText)
        assertEquals("class-CS2001301-7", snapshot.sourceId)
    }

    // Wednesday 16 September 2026 in Taipei; the course meets periods 3–4,
    // 10:20–12:10.
    private fun taipei(hour: Int, minute: Int): Date = Date.from(
        ZonedDateTime.of(2026, 9, 16, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE).toInstant()
    )

    private val wednesdayCourse = Course(
        courseNo = "CS3005301",
        courseName = "Data Structures",
        scheduleJson = """{"3":["3","4"]}""",
    )

    private fun dueAt(id: String, due: Date) = Assignment(
        assignmentId = id,
        courseNo = "CS3204301",
        courseName = "Operating Systems",
        title = "Lab $id",
        dueDate = due,
    )

    private fun resolveAt(at: Date, assignments: List<Assignment>) = LiveActivityResolver().resolve(
        courses = listOf(wednesdayCourse),
        assignments = assignments,
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = at,
    )

    @Test
    fun `a due-soon assignment shows during class, as on the iPhone`() {
        val at = taipei(10, 30)

        val snapshot = resolveAt(at, listOf(dueAt("900002", taipei(14, 0))))

        assertEquals(LiveActivityScenario.ASSIGNMENT_URGENT, snapshot?.scenario)
        assertEquals("assignment-900002", snapshot?.sourceId)
    }

    @Test
    fun `a due-soon assignment shows while a class is about to start`() {
        val at = taipei(10, 10)

        val snapshot = resolveAt(at, listOf(dueAt("900002", taipei(14, 0))))

        assertEquals(LiveActivityScenario.ASSIGNMENT_URGENT, snapshot?.scenario)
    }

    @Test
    fun `a deadline knows whether a class is going on under it`() {
        val due = listOf(dueAt("900002", taipei(14, 0)))

        val during = resolveAt(taipei(11, 0), due)
        val justAfter = resolveAt(Date(taipei(12, 10).time + 1_000), due)
        val before = resolveAt(taipei(10, 10), due)

        assertEquals(LiveActivityScenario.ASSIGNMENT_URGENT, justAfter?.scenario)
        assertEquals(true, during?.duringClass)
        assertEquals(false, justAfter?.duringClass)
        assertEquals(false, before?.duringClass)
    }

    @Test
    fun `the class itself is going on, and a class about to start is not`() {
        preferences.showAssignment = false

        assertEquals(true, resolveAt(taipei(11, 0), emptyList())?.duringClass)
        assertEquals(false, resolveAt(taipei(10, 10), emptyList())?.duringClass)
    }

    @Test
    fun `with the assignment scenario off, the class shows`() {
        preferences.showAssignment = false

        val inClass = resolveAt(taipei(10, 30), listOf(dueAt("900002", taipei(14, 0))))
        val preparing = resolveAt(taipei(10, 10), listOf(dueAt("900002", taipei(14, 0))))

        assertEquals(LiveActivityScenario.IN_CLASS, inClass?.scenario)
        assertEquals(LiveActivityScenario.CLASS_PREPARING, preparing?.scenario)
    }

    @Test
    fun `an assignment due beyond the lead time leaves the class showing`() {
        val snapshot = resolveAt(taipei(10, 30), listOf(dueAt("900002", taipei(23, 59))))

        assertEquals(LiveActivityScenario.IN_CLASS, snapshot?.scenario)
    }

    @Test
    fun `the due-soon Live Update opens its assignment, and a class the class table`() {
        val assignment = resolveAt(taipei(10, 30), listOf(dueAt("900002", taipei(14, 0))))
        preferences.showAssignment = false
        val inClass = resolveAt(taipei(10, 30), emptyList())
        val preparing = resolveAt(taipei(10, 10), emptyList())

        assertEquals(AppLink.Assignment("900002"), assignment?.link)
        assertEquals(AppLink.ClassTable, inClass?.link)
        assertEquals(AppLink.ClassTable, preparing?.link)
    }

    @Test
    fun `an extended assignment counts down to its extension`() {
        val extended = dueInHours("extended", -1).copy(extensionDueDate = Date(now.time + 2 * 3_600_000))

        val snapshot = resolve(listOf(extended))

        assertEquals("assignment-extended", snapshot?.sourceId)
        assertEquals(extended.extensionDueDate, snapshot?.countdownTarget)
    }
}
