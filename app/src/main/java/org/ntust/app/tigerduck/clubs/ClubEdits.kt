package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.ClubSubscriptionsSyncer
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's edits of their club subscriptions, each under the sign-in the screen read: saved in
 * [ClubSubscriptions], then sent ([ClubSubscriptionsSyncer.requestSync]); the account's first
 * subscription also starts the club fetches ([ClubFeeds]), which run only while there is one.
 */
@Singleton
class ClubEdits @Inject constructor(
    private val subscriptions: ClubSubscriptions,
    private val syncer: ClubSubscriptionsSyncer,
    private val feeds: ClubFeeds,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun subscribe(id: Int, signIn: SignIn) = sent(subscriptions.subscribe(id, signIn), signIn)

    fun unsubscribe(id: Int, signIn: SignIn) = sent(subscriptions.unsubscribe(id, signIn), signIn)

    /** [ids] is the subscribed clubs in their new priority order. */
    fun reorder(ids: List<Int>, signIn: SignIn) = sent(subscriptions.reorder(ids, signIn), signIn)

    fun setColor(color: Int, id: Int, signIn: SignIn) = sent(subscriptions.setColor(color, id, signIn), signIn)

    private fun sent(change: ClubSubscriptions.Change, signIn: SignIn) {
        if (!change.changed) return
        syncer.requestSync(signIn)
        if (change.firstSubscription) scope.launch { feeds.refresh(signIn) }
    }
}
