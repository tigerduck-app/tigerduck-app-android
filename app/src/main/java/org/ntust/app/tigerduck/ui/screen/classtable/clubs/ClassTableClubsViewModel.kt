package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.clubs.ClubActivities
import org.ntust.app.tigerduck.clubs.ClubActivity
import org.ntust.app.tigerduck.clubs.ClubCard
import org.ntust.app.tigerduck.clubs.ClubDirectory
import org.ntust.app.tigerduck.clubs.ClubDirectoryState
import org.ntust.app.tigerduck.clubs.ClubEdits
import org.ntust.app.tigerduck.clubs.ClubFeeds
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument
import org.ntust.app.tigerduck.clubs.SubscribedClub
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableClubLayout
import java.time.LocalDate
import javax.inject.Inject

/**
 * The class table's clubs: the subscribed clubs' activities in the week from today, by the weekday
 * whose column stands for their date, and the subscribe screen's list and edits. Each edit is made
 * under the sign-in as it is when the user makes it.
 */
@HiltViewModel
class ClassTableClubsViewModel @Inject constructor(
    private val subscriptions: ClubSubscriptions,
    private val directory: ClubDirectory,
    activities: ClubActivities,
    private val edits: ClubEdits,
    private val feeds: ClubFeeds,
    private val sessions: AccountSessions,
    uiLanguage: UiLanguageMonitor,
) : ViewModel() {

    private val today = TaipeiClock.minuteTicks().map { it.toLocalDate() }.distinctUntilChanged()

    /** The activities by weekday, 1 = Monday … 7 = Sunday, for the dates the columns stand for. */
    val week: StateFlow<Map<Int, List<ClubOccurrence>>> =
        week(activities.activities, subscriptions.document, directory.state, today, uiLanguage.changes, subscriptions::subscribed, directory::displayName)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val document: StateFlow<ClubSubscriptionsDocument> = subscriptions.document

    val directoryState: StateFlow<ClubDirectoryState> = directory.state

    fun displayName(club: ClubCard): String = directory.displayName(club)

    fun search(query: String) = directory.search(query)

    fun color(id: Int): Int = subscriptions.color(id)

    /** The directory, at the subscribe screen's opening: fetched only once its `max-age` is over. */
    suspend fun refreshDirectory() = directory.refresh()

    /** The feeds, as the class table's own refresh fetches them, while the account has subscriptions. */
    @OptIn(StartsAccountWork::class)
    fun refreshFeeds() {
        val signIn = sessions.school.current
        viewModelScope.launch { feeds.refresh(signIn) }
    }

    @OptIn(StartsAccountWork::class)
    fun subscribe(id: Int) = edits.subscribe(id, sessions.school.current)

    @OptIn(StartsAccountWork::class)
    fun unsubscribe(id: Int) = edits.unsubscribe(id, sessions.school.current)

    /** [ids] is the subscribed clubs in their new order. */
    @OptIn(StartsAccountWork::class)
    fun reorder(ids: List<Int>) = edits.reorder(ids, sessions.school.current)

    @OptIn(StartsAccountWork::class)
    fun setColor(id: Int, color: Int) = edits.setColor(color, id, sessions.school.current)

    companion object {
        /**
         * The week's occurrences, rebuilt on each input. The ViewModel outlives the Activity a language
         * switch recreates, so [language] (`UiLanguageMonitor.changes`) is an input too: the clubs' names
         * are read through [name] again on it.
         */
        internal fun week(
            feed: Flow<List<ClubActivity>>,
            document: Flow<ClubSubscriptionsDocument>,
            clubs: Flow<ClubDirectoryState>,
            today: Flow<LocalDate>,
            language: Flow<Unit>,
            subscribed: (ClubSubscriptionsDocument) -> List<SubscribedClub>,
            name: (ClubCard) -> String,
        ): Flow<Map<Int, List<ClubOccurrence>>> = combine(feed, document, clubs, today, language.onStart { emit(Unit) }) { activities, doc, directory, day, _ ->
            val names = directory.clubs.associate { it.id to name(it) }
            val shown = ClubActivities.occurrences(activities, ClassTableClubLayout.weekDays(day), subscribed(doc), names)
            ClassTableClubLayout.byWeekday(shown, day)
        }
    }
}
