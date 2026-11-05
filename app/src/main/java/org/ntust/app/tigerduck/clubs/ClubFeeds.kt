package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The club fetches, as iOS's `SchoolRefresh.clubActivities`: the activity feed and the directory
 * the class table names the clubs from, together, and only while the account has subscriptions.
 * The app starts it on every return to the foreground and when the account's first club is
 * subscribed, here or on another device. Both are cheap when nothing changed: the feed is a
 * conditional GET, and the directory waits out its `max-age`.
 */
@Singleton
class ClubFeeds @Inject constructor(
    private val subscriptions: ClubSubscriptions,
    private val activities: ClubActivities,
    private val directory: ClubDirectory,
) {
    suspend fun refresh(signIn: SignIn) {
        if (!subscriptions.hasSubscriptions || !signIn.isCurrent) return
        coroutineScope {
            launch { activities.refresh(signIn) }
            directory.refresh()
        }
    }
}
