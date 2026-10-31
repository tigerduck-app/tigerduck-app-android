package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.time.ZonedDateTime
import java.util.Date

/**
 * The "assignment due soon" Live Update over what LiveActivityManager hands
 * the resolver: the outstanding assignments, not every cached one; which
 * scenario wins when several apply; what a tap on each opens; and the
 * classes as the school's course changes leave them.
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

    // The school's course changes, over the iPhone suite's course: CC900 meets on Thursday
    // 2026-11-05, periods 2 to 4 (09:10–12:10) and 7 to 8 (14:20–16:20), in T4-501.

    private val morning = listOf("2", "3", "4")

    private fun thursdayAt(time: String): Date = Date.from(CourseChangeFixture.at(time))

    private fun resolveThursday(time: String, changes: CourseChanges) = LiveActivityResolver().resolve(
        courses = listOf(CourseChangeFixture.course()),
        assignments = emptyList(),
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = thursdayAt(time),
        changes = changes,
    )

    @Test
    fun `the Live Update passes over a cancelled class and shows the changed room and start`() {
        val changes = changes(
            row(1, "cancelled", periods = morning),
            row(2, "room", periods = listOf("7", "8"), values = mapOf("room" to "TR-313")),
            row(3, "start", periods = listOf("7", "8"), values = mapOf("start" to "14:00")),
        )

        assertEquals(LiveActivityScenario.IN_CLASS, resolveThursday("10:00", CourseChanges.EMPTY)?.scenario)
        assertNull("not preparing for the cancelled class", resolveThursday("09:00", changes))
        assertNull("not in the cancelled class", resolveThursday("10:00", changes))
        val preparing = resolveThursday("13:50", changes)!!
        assertEquals(LiveActivityScenario.CLASS_PREPARING, preparing.scenario)
        assertEquals("prep-CC900-7", preparing.sourceId)
        assertEquals("TR-313", preparing.locationText)
        assertEquals("14:00–16:20", preparing.subtitle)
        assertEquals(thursdayAt("14:00"), preparing.countdownTarget)
        assertEquals(LiveActivityScenario.IN_CLASS, resolveThursday("14:05", changes)?.scenario)
        assertEquals(LiveActivityScenario.CLASS_PREPARING, resolveThursday("14:05", CourseChanges.EMPTY)?.scenario)
    }

    @Test
    fun `the next class boundaries are the changed class's`() {
        val changes = changes(
            row(1, "cancelled", periods = morning),
            row(2, "start", periods = listOf("7", "8"), values = mapOf("start" to "14:00")),
        )

        val slots = LiveActivityResolver().todaySlotsAfter(
            listOf(CourseChangeFixture.course()), thursdayAt("07:00"), changes = changes,
        )

        assertEquals(listOf(thursdayAt("14:00") to thursdayAt("16:20")), slots.map { it.start to it.end })
    }

    @Test
    fun `a class is over once the cancelled later piece of its meeting begins`() {
        val changes = changes(row(1, "cancelled", periods = listOf("4")))

        val during = resolveThursday("10:30", changes)!!
        assertEquals("class-CC900-2", during.sourceId)
        assertEquals("its own piece ends at 11:10 (#905)", thursdayAt("11:10"), during.countdownTarget)
        assertNull(resolveThursday("11:30", changes))
        assertEquals("class-CC900-2", resolveThursday("11:30", CourseChanges.EMPTY)?.sourceId)
        val split = changes(row(2, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")))
        val moved = resolveThursday("11:30", split)!!
        assertEquals("class-CC900-4", moved.sourceId)
        assertEquals("TR-313", moved.locationText)
        assertEquals(thursdayAt("11:10"), resolveThursday("10:30", split)?.countdownTarget)
    }

    @Test
    fun `a class whose first piece a change dropped ends with the rest of its meeting`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "10:30")),
            row(2, "end", periods = morning, values = mapOf("end" to "11:00")),
            row(3, "room", periods = listOf("2"), values = mapOf("room" to "TR-313")),
        )

        val during = resolveThursday("10:45", changes)!!
        assertEquals(LiveActivityScenario.IN_CLASS, during.scenario)
        assertEquals("10:30–11:00", during.subtitle)
        assertEquals("T4-501", during.locationText)
        assertEquals(thursdayAt("11:00"), during.countdownTarget)
        assertNull("#904", resolveThursday("11:30", changes))
    }

    @Test
    fun `a class is going on in its last minute and over at its end minute`() {
        assertEquals(LiveActivityScenario.IN_CLASS, resolveThursday("12:09", CourseChanges.EMPTY)?.scenario)
        assertNull(resolveThursday("12:10", CourseChanges.EMPTY))
    }

    @Test
    fun `a class the school ended early is over at its changed end minute`() {
        val changes = changes(row(1, "end", periods = morning, values = mapOf("end" to "11:45")))

        assertEquals(LiveActivityScenario.IN_CLASS, resolveThursday("11:44", changes)?.scenario)
        assertNull(resolveThursday("11:45", changes))
    }
}
