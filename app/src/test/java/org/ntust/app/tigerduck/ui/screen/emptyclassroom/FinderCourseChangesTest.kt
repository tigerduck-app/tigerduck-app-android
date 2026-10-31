package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.FakeCour01Transport
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/** The user's classes the finder walks between follow the school's changes to the day it shows. */
class FinderCourseChangesTest {
    @get:Rule val main = MainDispatcherRule()

    private class Environment : FinderEnvironment {
        var changes: CourseChanges = CourseChanges.EMPTY
        val feed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var classTable: List<Course> = emptyList()
        override var pickedBuilding: String? = null
        override val signedIn = MutableStateFlow(true)
        override val coursesChanged = MutableStateFlow(0)
        override val calendarChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override fun now(): LocalDateTime = LocalDateTime.of(2026, 10, 14, 10, 5)
        override fun calendar() = AcademicCalendar(
            revision = 1,
            terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15))),
            holidays = emptyList(),
        )
        override fun account() = "B00000000"
        override suspend fun courses() = classTable
        override fun credentials() = ClassroomUsageService.Credentials("B00000000", "not-a-password")
        override fun isCurrent(credentials: ClassroomUsageService.Credentials) = true
        override fun isOffline(error: Throwable) = error is IOException
        override fun courseChanges() = changes
        override val courseChangesChanged get() = feed
    }

    private val env = Environment()
    // Lazy, so the main dispatcher rule is in place before the model starts its work.
    private val model by lazy {
        EmptyClassroomViewModel(
            ClassroomUsageService(FakeCour01Transport(), { _, _ -> true }, {}, { null }, { 0L }, Dispatchers.Unconfined),
            env,
        )
    }

    private fun rooms() = model.state.value.classes.map { it.room }

    @Test
    fun `the classes on the day shown are as the school changed that date, and a new feed redraws them`() {
        env.classTable = listOf(
            Course.fromSchedule(courseNo = "CC910", courseName = "計算機網路", classroom = "TR-214", schedule = mapOf(3 to listOf("1", "2", "6"))),
        )
        env.changes = CourseChanges.decode(
            CourseChangeFixture.feed(
                listOf(
                    CourseChangeFixture.row(1, "room", listOf("1", "2"), listOf("2026-10-14"), mapOf("room" to "IB-305"), courseNo = "CC910"),
                    CourseChangeFixture.row(2, "cancelled", listOf("6"), listOf("2026-10-21"), courseNo = "CC910"),
                ),
            ),
        )
        model.reloadCourses()
        assertEquals(listOf("IB-305", "TR-214"), rooms())

        model.select(LocalDate.of(2026, 10, 21))
        assertEquals("a cancelled class is not one to walk from", listOf("TR-214"), rooms())
        assertEquals(listOf(1), model.state.value.classes.map { it.lastPeriod })

        model.select(LocalDate.of(2026, 10, 14))
        env.changes = CourseChanges.EMPTY
        env.feed.tryEmit(Unit)
        assertEquals(listOf("TR-214", "TR-214"), rooms())
    }
}
