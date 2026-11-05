package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.clubs.ClubActivity
import org.ntust.app.tigerduck.clubs.ClubActivityKind
import org.ntust.app.tigerduck.clubs.ClubCard
import org.ntust.app.tigerduck.clubs.ClubDirectoryState
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument
import org.ntust.app.tigerduck.clubs.SubscribedClub
import java.time.LocalDate

/** The names on the class table's club blocks follow the interface's language (a ViewModel outlives the Activity a switch recreates). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassTableClubsWeekTest {

    private val feed = listOf(
        ClubActivity(
            id = 1, name = "Practice", clubId = 9001, clubName = "測試社團9001", date = "2026-11-09", endDate = "2026-11-09",
            startTime = "19:00:00", endTime = "21:00:00", location = "TR-000", kind = ClubActivityKind.Event, content = "",
        ),
    )
    private val directory = ClubDirectoryState(listOf(ClubCard(9001, "測試棋藝社", "Test Chess Club", "學藝性", null)))

    @Test
    fun `the week's club names are read again when the language changes, with no other input`() = runTest {
        var chinese = true
        val language = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val week = ClassTableClubsViewModel.week(
            MutableStateFlow(feed), MutableStateFlow(ClubSubscriptionsDocument()), MutableStateFlow(directory),
            MutableStateFlow(LocalDate.of(2026, 11, 9)), language,
            subscribed = { listOf(SubscribedClub(9001, 0x123456)) },
            name = { if (chinese) it.name else it.enName ?: it.name },
        )
        var names = emptyList<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            week.collect { names = it.values.flatten().map { occurrence -> occurrence.clubName } }
        }
        assertEquals(listOf("測試棋藝社"), names)

        chinese = false
        language.emit(Unit)

        assertEquals(listOf("Test Chess Club"), names)
    }
}
