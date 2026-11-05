package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.at
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.ui.screen.home.FakeHomeClubs.Companion.club
import java.time.LocalDate
import java.util.Date

/**
 * Home's time machine with clubs, on the fixture's Thursday 2026-11-05 (CC900 at 09:10 to 12:10
 * and 14:20 to 16:20), as the iPhone's `TimeSliderViewModelTests`. A piece is written
 * `id start-end`, in minutes after midnight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeSliderClubsTest {

    @Before
    fun freezeClock() {
        AppClock.setOverride(ClockOverride(at("10:30").toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun slider(
        clubs: FakeHomeClubs,
        courses: List<Course> = listOf(course()),
        changes: CourseChanges = CourseChanges.EMPTY,
        skipped: (Course, LocalDate) -> Boolean = { _, _ -> false },
        isQuietDay: (LocalDate) -> Boolean = { false },
    ) = TimeSliderViewModel(clubs).apply { configure(courses, changes, skipped, isQuietDay) }

    private fun TimeSliderViewModel.pieces() = items.filterIsInstance<TimeSliderItem.Club>()
        .map { "${it.occurrence.id} ${it.piece.start}-${it.piece.end}" }

    private fun date(text: String) = Date.from(at(text))

    private val morning = listOf("2", "3", "4")

    @Test
    fun `a class covers a club, which shows where the class is not`() {
        // 10:00 to 13:00, over the morning class (to 12:10).
        val s = slider(FakeHomeClubs(listOf(club(1, 0, "10:00", "13:00"))))

        assertEquals(listOf("1 730-780"), s.pieces())
        val thursdayIds = s.items.filter { it.start >= date("00:00") && it.start < date("23:59") }.map { it.id }
        assertEquals(listOf("CC900_20261105_2", "club-1@730", "CC900_20261105_7"), thursdayIds)
    }

    @Test
    fun `a higher club covers a lower one, which shows only where the higher has ended`() {
        val s = slider(FakeHomeClubs(listOf(club(1, 0, "18:00", "19:00"), club(2, 1, "18:30", "19:30"), club(3, 2, "18:15", "18:45"))))

        assertEquals(listOf("1 1080-1140", "2 1140-1170"), s.pieces())
    }

    @Test
    fun `a cancelled class still covers a club`() {
        // 10:00 to 13:00 over the cancelled morning class (to 12:10).
        val s = slider(FakeHomeClubs(listOf(club(1, 0, "10:00", "13:00"))), changes = changes(row(1, "cancelled", periods = morning)))

        assertTrue("the cancelled class is on the track", s.items.any { it.id == "CC900_20261105_2" && it.isCancelled })
        assertEquals(listOf("1 730-780"), s.pieces())
    }

    @Test
    fun `a skipped class does not cover a club`() {
        val clubs = FakeHomeClubs(listOf(club(1, 0, "10:00", "11:00")))

        assertEquals(emptyList<String>(), slider(clubs).pieces())
        assertEquals(listOf("1 600-660"), slider(clubs, skipped = { _, day -> day == thursday }).pieces())
    }

    @Test
    fun `a quiet day's classes do not cover a club, and the day keeps the club`() {
        val clubs = FakeHomeClubs(listOf(club(1, 0, "10:00", "11:00")))
        val s = slider(clubs, isQuietDay = { it == thursday })

        assertEquals(listOf("1 600-660"), s.pieces())
        assertTrue("no class that day", s.items.none { it is TimeSliderItem.Course && it.slot.localDate == thursday })
        val state = s.state(date("10:30")) as CourseState.InClass
        assertEquals(listOf("club-1@600"), state.slots.map { it.id })
    }

    @Test
    fun `a day with only club activities still has a time machine`() {
        val s = slider(FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00"))), courses = emptyList())

        assertTrue(s.hasItems)
        assertEquals(listOf("1 1140-1260"), s.pieces())
        assertEquals(listOf("club-1@1140"), (s.state(date("19:30")) as CourseState.InClass).slots.map { it.id })
        assertFalse(slider(FakeHomeClubs(), courses = emptyList()).hasItems)
    }

    @Test
    fun `a club days away is a time machine for the days ahead, and one in the past is not`() {
        val ahead = FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00", day = "2026-11-12")))
        val past = FakeHomeClubs(listOf(club(2, 0, "19:00", "21:00", day = "2026-11-04")))

        assertTrue(TimeSliderViewModel(ahead).hasClubActivities(date("10:30")))
        assertFalse(TimeSliderViewModel(past).hasClubActivities(date("10:30")))
        assertFalse(TimeSliderViewModel().hasClubActivities(date("10:30")))
    }

    @Test
    fun `a piece starting where a class ends sits flush against it on the track`() {
        // 11:00 to 13:00 is cut to start at 12:10, the morning class's end.
        val s = slider(FakeHomeClubs(listOf(club(1, 0, "11:00", "13:00"))))

        assertEquals(listOf("1 730-780"), s.pieces())
        // Thirty minutes on, 0.9 points a minute, with no gap between the class and the piece.
        assertEquals(30 * TimeSliderViewModel.POINTS_PER_MINUTE, s.xOffset(date("12:40")) - s.xOffset(date("12:10")), 0.001f)
    }

    @Test
    fun `the timeline is drawn again when the clubs change`() = runTest {
        val clubs = FakeHomeClubs()
        val s = slider(clubs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.followClubs() }
        assertEquals(emptyList<String>(), s.pieces())
        val revision = s.clubsRevision

        clubs.activities = listOf(club(1, 0, "19:00", "21:00"))
        assertEquals(listOf("1 1140-1260"), s.pieces())
        assertTrue(s.clubsRevision > revision)

        // A new color or rank is a new activity list for the same club.
        clubs.activities = listOf(club(1, 0, "19:00", "21:00").copy(color = 0x654321), club(2, 1, "19:00", "20:00"))
        assertEquals(listOf("1 1140-1260"), s.pieces())
        assertEquals(0x654321, s.items.filterIsInstance<TimeSliderItem.Club>().single().occurrence.color)
    }

    private fun openedAfter(clubs: FakeHomeClubs, change: () -> Unit): ClubOccurrence? {
        val s = slider(clubs)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { s.followClubs() }
        s.openedClub = clubs.activities.first()
        change()
        job.cancel()
        return s.openedClub
    }

    @Test
    fun `an open activity that the clubs no longer have is closed`() {
        val clubs = FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00"), club(2, 1, "18:00", "19:00")))
        assertEquals(null, openedAfter(clubs) { clubs.activities = clubs.activities.drop(1) })
    }

    @Test
    fun `an open activity that changed is shown as it is now`() {
        val clubs = FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00")))
        val open = openedAfter(clubs) { clubs.activities = listOf(club(1, 0, "19:00", "21:00").copy(color = 0x654321, name = "Renamed")) }
        assertEquals(0x654321, open?.color)
        assertEquals("Renamed", open?.name)
    }

    @Test
    fun `an open activity is closed by a sign-out of the school account`() {
        val clubs = FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00")))
        assertEquals(null, openedAfter(clubs) { clubs.signOuts.tryEmit(Unit) })
    }
}
