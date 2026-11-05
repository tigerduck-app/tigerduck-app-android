package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.clubs.ClubActivities
import org.ntust.app.tigerduck.clubs.ClubDirectory
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.util.Date
import javax.inject.Inject

/** What Home's time machine reads of the clubs: the subscribed clubs' activities, and when they change. */
interface HomeClubs {
    /** The activities on [days] (`yyyy-MM-dd`), with the colors, ranks and names as they are now. */
    fun occurrences(days: Set<String>): List<ClubOccurrence>

    /** Emits at the start and each time what [occurrences] answers may have changed. */
    val changes: Flow<Unit>

    /** Emits when the school account signed out or in, whose clubs and details are not the next one's. */
    val accountChanges: Flow<Unit> get() = emptyFlow()

    /** The activities on the [radius] days before and after [center]'s Taipei day. */
    fun around(center: Date, radius: Int): List<ClubOccurrence> = occurrences(days(center, -radius..radius))

    /** Whether there is an activity on [now]'s Taipei day or the [radius] days after it. */
    fun hasActivities(now: Date, radius: Int): Boolean = occurrences(days(now, 0..radius)).isNotEmpty()

    private fun days(center: Date, offsets: IntRange): Set<String> {
        val day = center.toInstant().atZone(TaipeiClock.zone).toLocalDate()
        return offsets.mapTo(HashSet()) { day.plusDays(it.toLong()).toString() }
    }

    companion object {
        /** No clubs. */
        val None: HomeClubs = object : HomeClubs {
            override fun occurrences(days: Set<String>) = emptyList<ClubOccurrence>()
            override val changes: Flow<Unit> = emptyFlow()
        }
    }
}

/**
 * The feed filtered to the subscribed clubs, the class table's way ([ClubActivities.occurrences]).
 * A change of the feed, the subscriptions (which carry the colors and ranks), the directory (the
 * names) or the interface's [language] is a change.
 */
class FeedHomeClubs(
    private val activities: ClubActivities,
    private val subscriptions: ClubSubscriptions,
    private val directory: ClubDirectory,
    language: Flow<Unit>,
    /** The school account's [AccountSession.changes][org.ntust.app.tigerduck.auth.session.AccountSession.changes]. */
    account: Flow<Boolean> = emptyFlow(),
) : HomeClubs {
    override val accountChanges: Flow<Unit> = account.map { }

    override fun occurrences(days: Set<String>): List<ClubOccurrence> {
        val names = directory.state.value.clubs.associate { it.id to directory.displayName(it) }
        return ClubActivities.occurrences(activities.activities.value, days, subscriptions.subscribed(), names)
    }

    override val changes: Flow<Unit> = combine(
        activities.activities, subscriptions.document, directory.state, language.onStart { emit(Unit) },
    ) { _, _, _, _ -> }
}

/** Holds [clubs] across the Activity a language switch recreates. */
@HiltViewModel
class HomeClubsViewModel @Inject constructor(
    activities: ClubActivities,
    subscriptions: ClubSubscriptions,
    directory: ClubDirectory,
    uiLanguage: UiLanguageMonitor,
    sessions: AccountSessions,
) : ViewModel() {
    val clubs: HomeClubs = FeedHomeClubs(activities, subscriptions, directory, uiLanguage.changes, sessions.school.changes)
}

/**
 * Home's time machine, rebuilt as the clubs change while Home is shown, whether or not the section
 * is: Home draws the section when a club has an activity out of term.
 */
@Composable
fun rememberTimeSliderViewModel(): TimeSliderViewModel {
    val clubs = hiltViewModel<HomeClubsViewModel>().clubs
    val slider = remember(clubs) { TimeSliderViewModel(clubs) }
    LaunchedEffect(slider) { slider.followClubs() }
    return slider
}
