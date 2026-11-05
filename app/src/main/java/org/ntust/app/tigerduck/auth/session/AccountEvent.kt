package org.ntust.app.tigerduck.auth.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.logSummary

enum class Account { SCHOOL, MAIL, LIBRARY }

/**
 * Why an account signed out: what [SignOutPlans.signOut] was given, on each [AccountEvent.SignedOut]
 * it tells. A sign-out a process death cut short, which the next start finishes, is the school's.
 */
enum class SignOutReason { SCHOOL, MAIL, LIBRARY, ENDPOINT_CHANGE, FULL_RESET }

/**
 * What happened to an account, one stream for the three of them ([AccountSessions.events]).
 *
 * A state flow can't carry this: it hands a collector only the latest value, so a sign-out and a
 * sign-in landing before the collector next runs read as no change, and a collector that first
 * ran after a sign-out took signed out for where it began. The stream hands every reactor every
 * event, in order, each account's in the order they happened. It replays nothing, so a reactor
 * subscribes before anything can happen that it must hear of ([follow]).
 */
sealed interface AccountEvent {
    val account: Account

    /**
     * [account] signed in, which is two things to follow. Without [byHand], the account now shows
     * as signed in, which it did not: told as its state changes, and what
     * [AccountSession.changes] reports. With [byHand], the account took a sign-in the user typed:
     * told as it is stored and however it leaves the state, the same student's again included,
     * which shows no change at all.
     */
    data class SignedIn(override val account: Account, val byHand: Boolean) : AccountEvent

    /**
     * [account] signed out, ending [ended]. [address] is the address of who left, on a mail
     * sign-out that had anyone to leave: cleanup that runs late must not take what the next
     * account has made since, nor keep what the one that left made.
     */
    data class SignedOut(
        override val account: Account,
        val reason: SignOutReason,
        val ended: SignIn,
        val address: String?,
    ) : AccountEvent

    /**
     * Another student took [account]'s place with no sign-out in between, leaving [address]. Told
     * once the student who left counts as gone, and before the sign-in ends.
     */
    data class StudentChanged(override val account: Account, val address: String?) : AccountEvent
}

/**
 * The stream's buffer. Replay 0, as nothing here is state, and unlimited, so that a tryEmit never
 * fails and never waits, under a lock or not, and a reactor that falls behind still gets every
 * event. Only reactors that have fallen behind hold anything in it: a cancelled one lets go.
 */
internal fun accountEventStream() = MutableSharedFlow<AccountEvent>(extraBufferCapacity = Int.MAX_VALUE)

/**
 * Follows [events] on this scope for as long as it lives: subscribed by the time this returns,
 * however busy the scope's dispatcher is, so an event between here and the first run still
 * arrives. For what follows an account at the application's level; a sign-out can't cancel it, so
 * never run one of these on [AccountSession.launch].
 *
 * A step that fails, with the exception or the cancellation of something it waited for, loses
 * that event's work and no later event. Only this scope's own cancellation ends it, and then the
 * stream keeps nothing for it.
 */
internal fun <T> CoroutineScope.follow(events: Flow<T>, onEach: suspend (T) -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        events.collect { event ->
            try {
                onEach(event)
            } catch (e: Exception) {
                ensureActive()
                Log.w("AccountEvents", "a reactor's step failed: ${e.logSummary()}")
            }
        }
    }
