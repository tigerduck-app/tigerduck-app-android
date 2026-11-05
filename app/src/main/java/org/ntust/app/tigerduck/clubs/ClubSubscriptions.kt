package org.ntust.app.tigerduck.clubs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.auth.session.AccountScoped
import org.ntust.app.tigerduck.shared.account.SignIn
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's club subscriptions: which clubs, in what priority, and the color of each, as the
 * Apple apps' `ClubSubscriptions`. They are not tied to a term, so they carry over to the next one.
 * Each change is stamped and saved for the sign-in it was made in, and marked unsent until a sync
 * leaves nothing to send ([adopt]); the school's sign-out drops them all ([SchoolStores][org.ntust.app.tigerduck.auth.session.SchoolStores]).
 */
@Singleton
class ClubSubscriptions internal constructor(
    private val prefs: SharedPreferences,
    private val now: () -> Instant = Instant::now,
) : AccountScoped {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val lock = Any()
    private val _document = MutableStateFlow(ClubSubscriptionsWire.read(prefs.getString(KEY_DOCUMENT, null)))
    val document: StateFlow<ClubSubscriptionsDocument> = _document.asStateFlow()

    /** The subscribed clubs, highest priority first. */
    val subscribedIds: List<Int> get() = _document.value.subscribedIds

    val hasSubscriptions: Boolean get() = _document.value.hasSubscriptions

    fun isSubscribed(id: Int): Boolean = _document.value.isSubscribed(id)

    /** `0xRRGGBB`: the color picked or first given, or the club's default when there is none. */
    fun color(id: Int): Int = _document.value.clubs[id]?.color?.value ?: ClubColors.defaultColor(id)

    /** The subscribed clubs with their colors, highest priority first, as the feed is filtered to them. */
    fun subscribed(document: ClubSubscriptionsDocument = _document.value): List<SubscribedClub> =
        document.subscribedIds.map { SubscribedClub(it, document.clubs[it]?.color?.value ?: ClubColors.defaultColor(it)) }

    /** Whether a change made here has yet to reach the server. */
    val hasUnsentChanges: Boolean get() = prefs.getBoolean(KEY_UNSENT, false)

    fun subscribe(id: Int, signIn: SignIn): Change = change(signIn) { doc, at -> doc.subscribe(id, ClubColors.defaultColor(id), at) }

    fun unsubscribe(id: Int, signIn: SignIn): Change = change(signIn) { doc, at -> doc.unsubscribe(id, at) }

    /** [ids] is the subscribed clubs in their new priority order; any other id is left out. */
    fun reorder(ids: List<Int>, signIn: SignIn): Change = change(signIn) { doc, at -> doc.reorder(ids, at) }

    fun setColor(color: Int, id: Int, signIn: SignIn): Change = change(signIn) { doc, at -> doc.setColor(color, id, at) }

    /**
     * Takes the document as the server now holds it. A change made here since the sync read the
     * device's copy is stamped later and stays; once the two agree, nothing is left to send.
     * Returns whether the account went from no subscribed club to one.
     */
    fun adopt(synced: ClubSubscriptionsDocument, signIn: SignIn): Boolean = signIn.ifCurrent {
        synchronized(lock) {
            val before = _document.value
            val merged = ClubSubscriptionsDocument.merged(local = before, server = synced)
            val editor = prefs.edit()
            if (merged != before) editor.putString(KEY_DOCUMENT, ClubSubscriptionsWire.json(merged).toString())
            if (merged == synced) editor.putBoolean(KEY_UNSENT, false)
            editor.apply()
            _document.value = merged
            !before.hasSubscriptions && merged.hasSubscriptions
        }
    } == true

    /** At the school's sign-out: the subscriptions, their colors and the unsent mark. */
    override fun forgetAccount() = clear()

    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_DOCUMENT).remove(KEY_UNSENT).apply()
        _document.value = ClubSubscriptionsDocument()
    }

    /** What an edit came to: whether it changed anything, and whether it was the account's first subscription. */
    data class Change(val changed: Boolean, val firstSubscription: Boolean = false)

    private fun change(
        signIn: SignIn,
        edit: (ClubSubscriptionsDocument, ClubSubscriptionsDocument.Stamp) -> ClubSubscriptionsDocument,
    ): Change = signIn.ifCurrent {
        synchronized(lock) {
            val before = _document.value
            val next = edit(before, before.stamp(now()))
            if (next == before) return@synchronized Change(false)
            prefs.edit()
                .putString(KEY_DOCUMENT, ClubSubscriptionsWire.json(next).toString())
                .putBoolean(KEY_UNSENT, true)
                .apply()
            _document.value = next
            Change(true, firstSubscription = !before.hasSubscriptions && next.hasSubscriptions)
        }
    } ?: Change(false)

    private companion object {
        const val PREFS_NAME = "club_subscriptions"
        const val KEY_DOCUMENT = "document"
        const val KEY_UNSENT = "unsent"
    }
}
