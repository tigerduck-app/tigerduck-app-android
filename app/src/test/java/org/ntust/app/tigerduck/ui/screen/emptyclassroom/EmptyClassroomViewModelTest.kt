package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.emptyclassroom.CampusUsage
import org.ntust.app.tigerduck.emptyclassroom.ClassroomPageFixtures as Pages
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.FakeCour01Transport
import org.ntust.app.tigerduck.emptyclassroom.RoomSlotUse
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ServerStatus
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The finder's screen state against made-up pages: where it opens, the days it never asks the
 * site about, and what a read leaves on screen.
 */
class EmptyClassroomViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeEnvironment(var now: LocalDateTime) : FinderEnvironment {
        var calendar = AcademicCalendar(
            revision = 1,
            terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15))),
            holidays = listOf(Holiday(1, "國慶日", "National Day", LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15))),
        )
        var classTable: List<Course> = emptyList()
        var current = true
        var offline = false
        override var pickedBuilding: String? = null
        override val signedIn = MutableStateFlow(true)
        override val coursesChanged = MutableStateFlow(0)
        override val calendarChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var account: String? = "B00000000"

        /** Holds the next course read, which then returns the class table as it was asked for. */
        var courseReadGate: CompletableDeferred<Unit>? = null

        override fun now() = now
        override fun calendar() = calendar
        override fun account() = account
        override suspend fun courses(): List<Course> {
            val asked = classTable
            courseReadGate?.let { courseReadGate = null; it.await() }
            return asked
        }
        override fun credentials() = ClassroomUsageService.Credentials("B00000000", "not-a-password")
        override fun isCurrent(credentials: ClassroomUsageService.Credentials) = current
        override fun isOffline(error: Throwable) = offline || error is IOException
    }

    private class Harness(now: LocalDateTime, demo: (LocalDate) -> CampusUsage? = { null }) {
        val env = FakeEnvironment(now)
        val transport = FakeCour01Transport()
        var clockMs = 1_000_000L
        val service = ClassroomUsageService(transport, { _, _ -> true }, {}, demo, { clockMs }, Dispatchers.Unconfined)
        val model = EmptyClassroomViewModel(service, env)

        fun serve(html: String?, gate: CompletableDeferred<Unit>? = null) = transport.serve(html, gate)

        /** A cold read of a day, today included: the query page, the campus postback and the date's one page. */
        fun serveDay(rows: List<String>, gate: CompletableDeferred<Unit>? = null) {
            serve(Pages.page(rows = null, campus = ""))
            serve(Pages.page(viewState = "campus"))
            serve(Pages.page(rows = rows), gate)
        }

        val state get() = model.state.value
        val requests get() = transport.sent.size
        val rooms get() = state.usage?.rooms?.map { it.room }
    }

    private fun time(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private fun day(day: Int) = LocalDate.of(2026, 10, day)

    @Test
    fun `it opens on the period in progress, and after the last one on the next teaching day`() {
        val wednesday = Harness(time(14, 10, 5))
        wednesday.serveDay(listOf(Pages.row("IB-508", "..............")))
        wednesday.model.appear()
        assertEquals(day(14), wednesday.state.selectedDay)
        assertEquals(2, wednesday.state.selectedPeriod)

        val friday = Harness(time(16, 23))
        friday.serveDay(listOf(Pages.row("IB-508", "..............")))
        friday.model.appear()
        assertEquals(day(19), friday.state.selectedDay)
        assertEquals(0, friday.state.selectedPeriod)
    }

    @Test
    fun `coming back after today's period has ended moves to the current one, and a picked period still ahead stays`() {
        // Pinned to the bottom bar, the finder keeps its model between visits.
        val harness = Harness(time(14, 8, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(0, harness.state.selectedPeriod)
        harness.model.disappear()

        harness.env.now = time(14, 13, 40)
        harness.model.appear()
        assertEquals(day(14), harness.state.selectedDay)
        assertEquals(5, harness.state.selectedPeriod)

        harness.model.select(8)
        harness.model.disappear()
        harness.env.now = time(14, 14, 30)
        harness.model.appear()
        assertEquals(8, harness.state.selectedPeriod)
    }

    @Test
    fun `a weekend shows no data without asking the site, and offers the next teaching day`() {
        val harness = Harness(time(14, 10, 5))
        harness.model.select(day(17))

        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(0, harness.requests)
        assertEquals(day(19), harness.state.nextTeachingDay)
    }

    @Test
    fun `a holiday shows its name and no rooms, without asking the site`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(day(16), harness.state.selectedDay)

        harness.model.select(day(15))

        assertEquals("National Day", (harness.state.content as FinderContent.HolidayOff).holiday.nameEn)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a day outside the term shows no data, without asking the site`() {
        val harness = Harness(time(14, 10, 5))
        harness.env.calendar = harness.env.calendar.copy(
            terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 10, 16))),
        )
        harness.model.select(day(20))

        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(0, harness.requests)
        assertNull(harness.state.nextTeachingDay)
    }

    @Test
    fun `a term's dates that arrive after the day was read take its rooms off the screen`() {
        val harness = Harness(time(14, 10, 5))
        val inTerm = harness.env.calendar
        // Before the calendar arrives, every weekday counts as a class day.
        harness.env.calendar = AcademicCalendar.EMPTY
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("IB-508"), harness.rooms)

        harness.env.calendar = inTerm
        harness.env.calendarChanged.tryEmit(Unit)
        assertEquals(listOf("IB-508"), harness.rooms)

        harness.env.calendar = inTerm.copy(
            terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 10, 13))),
        )
        harness.env.calendarChanged.tryEmit(Unit)
        assertEquals(FinderContent.NoData, harness.state.content)
        assertNull(harness.state.nextTeachingDay)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a calendar that makes the day a holiday while it loads stops the read`() {
        val harness = Harness(time(14, 10, 5))
        harness.env.calendar = AcademicCalendar.EMPTY
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), gate)
        harness.model.appear()
        assertEquals(FinderContent.Loading, harness.state.content)

        harness.env.calendar = harness.env.calendar.copy(
            holidays = listOf(Holiday(2, "校慶", "Founding Day", day(14), day(14))),
        )
        harness.env.calendarChanged.tryEmit(Unit)
        gate.complete(Unit)

        assertEquals("Founding Day", (harness.state.content as FinderContent.HolidayOff).holiday.nameEn)
        assertNull(harness.service.keptUsage(day(14)))
    }

    @Test
    fun `the day's rooms show once read, and a day the site has no rooms for is no data`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("TR-210", "cc..cc..cc...."), Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("TR-210", "IB-508"), harness.rooms)

        harness.serveDay(emptyList())
        harness.model.select(day(19))
        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(6, harness.requests)
    }

    @Test
    fun `a failed read shows the error, and a later pull that fails keeps the rooms on screen`() {
        val harness = Harness(time(14, 22, 30))
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }
        harness.model.appear()
        assertEquals(FinderContent.Failed(offline = false), harness.state.content)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.retry()
        assertEquals(listOf("IB-508"), harness.rooms)
        val fetchedAt = (harness.state.content as FinderContent.Rooms).fetchedAtMs

        harness.clockMs += 60 * 60_000L
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }
        harness.model.refresh()

        val shown = harness.state.content as FinderContent.Rooms
        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(fetchedAt, shown.fetchedAtMs)
        assertFalse(harness.state.isRefreshing)
    }

    @Test
    fun `the header's dot says how the site last answered, and a failed pull turns it with the rooms still on screen`() {
        val harness = Harness(time(14, 22, 30))
        assertEquals(ServerStatus.UNKNOWN, harness.state.siteStatus)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(ServerStatus.OK, harness.state.siteStatus)

        harness.clockMs += 60 * 60_000L
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }
        harness.model.refresh()
        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(ServerStatus.FAILED, harness.state.siteStatus)

        // The answer was the account before's.
        harness.model.disappear()
        harness.env.signedIn.value = false
        assertEquals(ServerStatus.UNKNOWN, harness.state.siteStatus)
    }

    @Test
    fun `the dot's last sync is when the day on screen was read, which a failed pull leaves as it was`() {
        val harness = Harness(time(14, 22, 30))
        assertNull(harness.state.syncedAtMs)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(1_000_000L, harness.state.syncedAtMs)

        harness.clockMs += 60 * 60_000L
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }
        harness.model.refresh()
        assertEquals(1_000_000L, harness.state.syncedAtMs)

        // A site that answers with no rooms was still read.
        harness.serveDay(emptyList())
        harness.model.select(day(19))
        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(harness.clockMs, harness.state.syncedAtMs)

        // A weekend is never asked about.
        harness.model.select(day(17))
        assertNull(harness.state.syncedAtMs)
    }

    @Test
    fun `an answer that arrives after the account changed is not shown`() {
        val harness = Harness(time(14, 22, 30))
        harness.env.current = false
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))

        harness.model.appear()

        assertEquals(FinderContent.Loading, harness.state.content)
    }

    @Test
    fun `the user's classes follow the class table while the screen is open`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertTrue(harness.state.classes.isEmpty())

        harness.env.classTable = listOf(
            Course.fromSchedule(
                courseNo = "CS1001301",
                courseName = "計算機網路",
                schedule = mapOf(3 to listOf("1", "2")),
                classroomMap = mapOf("3-1" to "TR-214", "3-2" to "TR-214"),
            ),
        )
        harness.model.reloadCourses()

        assertEquals(listOf("TR-214"), harness.state.classes.map { it.room })
        assertEquals(listOf(1), harness.state.classes.map { it.lastPeriod })
    }

    @Test
    fun `an account change while the screen is away reads nothing until it comes back`() {
        val harness = Harness(time(14, 22, 30))
        harness.env.pickedBuilding = "IB"

        // A sign-out and a sign-in while the finder is not on screen.
        harness.env.signedIn.value = false
        harness.env.pickedBuilding = null
        harness.env.signedIn.value = true

        assertEquals(0, harness.requests)
        assertEquals(FinderContent.Loading, harness.state.content)
        assertNull(harness.state.pickedBuilding)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("IB-508"), harness.rooms)

        harness.model.disappear()
        harness.env.signedIn.value = false
        assertEquals(3, harness.requests)
    }

    private fun networking(room: String) = Course.fromSchedule(
        courseNo = "CS1001301",
        courseName = "計算機網路",
        schedule = mapOf(3 to listOf("1", "2")),
        classroomMap = mapOf("3-1" to room, "3-2" to room),
    )

    @Test
    fun `an account change drops the classes at once`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.env.classTable = listOf(networking("TR-214"))
        harness.model.appear()
        assertEquals(listOf("TR-214"), harness.state.classes.map { it.room })

        // The new account's course read is held up; the old classes must not wait for it.
        harness.env.courseReadGate = CompletableDeferred()
        harness.env.account = null
        harness.env.signedIn.value = false

        assertTrue(harness.state.classes.isEmpty())
    }

    @Test
    fun `a course read started for the account before shows nothing after the account changes`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.env.classTable = listOf(networking("TR-214"))
        harness.model.appear()

        // A read of the departing account's courses, which the cache read holds up.
        val departing = CompletableDeferred<Unit>()
        harness.env.courseReadGate = departing
        harness.model.reloadCourses()
        harness.model.disappear()

        // The account signs out and another signs in while the finder is away.
        harness.env.account = null
        harness.env.signedIn.value = false
        harness.env.account = "B11111111"
        harness.env.classTable = listOf(networking("IB-305"))
        harness.env.signedIn.value = true
        departing.complete(Unit)

        assertTrue(harness.state.classes.isEmpty())
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("IB-305"), harness.state.classes.map { it.room })
    }

    @Test
    fun `a sync that rewrites the timetable while the screen is open updates the classes`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertTrue(harness.state.classes.isEmpty())

        harness.env.classTable = listOf(networking("TR-214"))
        harness.env.coursesChanged.value++

        assertEquals(listOf("TR-214"), harness.state.classes.map { it.room })
    }

    @Test
    fun `a pull reads the user's classes again`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.select(day(21))
        assertTrue(harness.state.classes.isEmpty())

        harness.env.classTable = listOf(networking("TR-214"))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.refresh()

        assertEquals(listOf("TR-214"), harness.state.classes.map { it.room })
    }

    @Test
    fun `a picked building is kept for the next visit, and an account change forgets it`() {
        val harness = Harness(time(14, 10, 5))
        harness.model.pick("IB")
        assertEquals("IB", harness.state.pickedBuilding)
        assertEquals("IB", harness.env.pickedBuilding)

        // A sign-out clears the stored pick, as AuthService.logout() does, then the screen hears.
        harness.env.pickedBuilding = null
        harness.model.accountChanged()
        assertNull(harness.state.pickedBuilding)
    }

    @Test
    fun `an open room's sheet follows each read of the day and closes once a read lacks the room`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("TR-214", "..............")))
        harness.model.appear()
        harness.model.open(harness.state.usage!!.rooms.first())
        assertEquals(true, harness.state.openRoom?.isFree(2))

        harness.serveDay(listOf(Pages.row("TR-214", "..b...........")))
        harness.model.refresh()
        assertEquals(false, harness.state.openRoom?.isFree(2))

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.refresh()
        assertNull(harness.state.openRoom)

        // The room back in a later read does not open the sheet again.
        harness.serveDay(listOf(Pages.row("TR-214", "..............")))
        harness.model.refresh()
        assertEquals(listOf("TR-214"), harness.rooms)
        assertNull(harness.state.openRoom)
    }

    @Test
    fun `an open room's sheet closes on another day, even one with the room, and on an account change`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("TR-214", "..............")))
        harness.model.appear()
        harness.serveDay(listOf(Pages.row("TR-214", "..............")))
        harness.model.select(day(16))
        harness.model.select(day(14))
        val room = harness.state.usage!!.rooms.first()

        harness.model.open(room)
        harness.model.select(day(16))
        assertEquals(listOf("TR-214"), harness.rooms)
        assertNull(harness.state.openRoom)

        harness.model.open(room)
        assertEquals(room, harness.state.openRoom)
        harness.model.accountChanged()
        assertEquals(listOf("TR-214"), harness.rooms)
        assertNull(harness.state.openRoom)
    }

    @Test
    fun `the demo account's day shows at once on the next visit, so an open room's sheet stays`() {
        val demo = CampusUsage(listOf(RoomUsage("TR-214", List(14) { RoomSlotUse.Free })), emptyMap())
        val harness = Harness(time(14, 10, 5), demo = { demo })
        harness.model.appear()
        harness.model.open(harness.state.usage!!.rooms.first())

        // A trip to the background, or the activity rebuilt for a dark-mode change.
        harness.model.disappear()
        harness.model.appear()

        assertEquals("TR-214", harness.state.openRoom?.room)
        assertEquals(0, harness.requests)
    }

    @Test
    fun `a closed room's sheet stays closed`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("TR-214", "..............")))
        harness.model.appear()
        harness.model.open(harness.state.usage!!.rooms.first())

        harness.model.closeRoom()

        assertNull(harness.state.openRoom)
        assertNull(harness.state.openRoomName)
    }

    @Test
    fun `leaving the screen lets its read finish, so the return shows the day without another`() {
        val harness = Harness(time(14, 22, 30))
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), gate)
        harness.model.appear()
        assertEquals(3, harness.requests)

        harness.model.disappear()
        gate.complete(Unit)
        assertEquals(listOf("IB-508"), harness.rooms)

        harness.model.appear()
        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a return while the day still loads joins that read instead of sending its requests again`() {
        // Pinned to the bottom bar, the finder keeps its model between visits.
        val harness = Harness(time(14, 22, 30))
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), gate)
        harness.model.appear()
        assertEquals(3, harness.requests)

        harness.model.disappear()
        harness.model.appear()
        gate.complete(Unit)

        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a sign-out while the screen is away ends the read it left running`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        harness.model.disappear()

        // Back once the day is too old to be fresh, but still shown while it is read again.
        harness.clockMs += 4 * 60 * 60_000L
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("TR-209", "..............")), gate)
        harness.model.appear()
        assertTrue(harness.state.isReloading)
        harness.model.disappear()

        harness.env.account = null
        harness.env.signedIn.value = false
        gate.complete(Unit)

        assertEquals(FinderContent.Loading, harness.state.content)
        assertFalse(harness.state.isReloading)
        // The read never finished, so the day is kept as the first read had it.
        assertEquals(1_000_000L, harness.service.keptUsage(day(16))?.fetchedAtMs)
        assertEquals(6, harness.requests)
    }

    @Test
    fun `a second appear while the day loads keeps that load`() {
        val harness = Harness(time(14, 22, 30))
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), gate)
        harness.model.appear()

        harness.model.appear()
        gate.complete(Unit)

        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a day chosen while another loads shows its own rooms`() {
        val harness = Harness(time(14, 22, 30))
        val first = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), first)
        harness.model.appear()
        assertEquals(day(16), harness.state.selectedDay)

        // Choosing another day cancels the first read, so the next starts from a new campus page.
        harness.serveDay(listOf(Pages.row("TR-209", "..............")))
        harness.model.select(day(19))
        first.complete(Unit)

        assertEquals(listOf("TR-209"), harness.rooms)
        assertEquals(day(19), harness.state.selectedDay)
    }

    @Test
    fun `a day read before shows at once while it is read again`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        harness.serveDay(listOf(Pages.row("TR-209", "..............")))
        harness.model.select(day(19))
        harness.clockMs += 4 * 60 * 60_000L

        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-401", "..............")), gate)
        harness.model.select(day(16))

        assertEquals(listOf("IB-508"), harness.rooms)
        assertTrue(harness.state.isReloading)
        gate.complete(Unit)
        assertEquals(listOf("IB-401"), harness.rooms)
        assertFalse(harness.state.isReloading)
    }

    @Test
    fun `today names the periods that have ended and the one in progress`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(2, harness.state.endedPeriods)
        assertEquals(2, harness.state.currentPeriod)
        assertEquals(14, harness.state.days.size)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.select(day(16))
        assertEquals(0, harness.state.endedPeriods)
        assertNull(harness.state.currentPeriod)
    }
}
