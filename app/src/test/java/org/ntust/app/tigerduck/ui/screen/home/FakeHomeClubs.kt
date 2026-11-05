package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.ntust.app.tigerduck.clubs.ClubActivityKind
import org.ntust.app.tigerduck.clubs.ClubOccurrence

/** Hand-made clubs for the time machine: [activities] are what the subscribed clubs have, changed by assigning. */
class FakeHomeClubs(activities: List<ClubOccurrence> = emptyList()) : HomeClubs {
    private val state = MutableStateFlow(activities)

    var activities: List<ClubOccurrence>
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun occurrences(days: Set<String>) = state.value.filter { it.day in days }

    override val changes: Flow<Unit> = state.map { }

    companion object {
        private fun minutes(hm: String) = hm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

        /** A made-up club's activity; a lower [priority] ranks higher, [title] is the activity's own name. */
        fun club(
            id: Int,
            priority: Int,
            from: String,
            to: String,
            day: String = "2026-11-05",
            title: String = "Practice",
            location: String = "TR-000",
        ) = ClubOccurrence(
            id = id, clubId = 9000 + priority, clubName = "測試社團$priority", color = 0x123456 + priority, priority = priority,
            day = day, start = minutes(from), end = minutes(to), location = location, kind = ClubActivityKind.Event,
            name = title, content = "A made-up gathering.",
        )
    }
}
