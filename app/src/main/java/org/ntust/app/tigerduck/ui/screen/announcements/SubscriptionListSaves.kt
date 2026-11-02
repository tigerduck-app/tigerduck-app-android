package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `/v4`'s saves of the bulletin subscription rules: the whole list in one PUT, each rule by the
 * `client_id` this device made for it (`docs/api-v4.md`, "Subscriptions"), as `/v4` has no
 * per-rule route.
 *
 * One for the app, on its scope, not a page's. The student may leave the page with a save out and
 * an edit waiting, which clears the page's view model and cancels its scope; the edit still goes,
 * and a page opened again finds here the list being saved and how its saves went ([ledger]). One
 * worker sends one PUT at a time for the whole app, and waits for each whole request, so no page,
 * old or new, starts a second while one is out (iOS `sync-order`, "One send per channel at a
 * time"; spec §5.3, race 4). An edit made while a save is out waits; the newest waiting list takes
 * the place of an older one, and one of a sign-in that has ended is dropped, never sent.
 */
@Singleton
class SubscriptionListSaves @Inject constructor(
    private val api: BulletinApiClient,
    @ApplicationScope scope: CoroutineScope,
) {
    /**
     * A sign-in's list as its saves know it: [rules], the newest list the student made, or the
     * backend's once the saves have settled; [agreed], the list as the backend last agreed to it,
     * which a refused save puts back; [save], how the saves stand, saving while one is waiting or
     * out; and [edit], the number of the newest edit, by which an answer tells whether one came
     * since its save went.
     */
    data class Ledger(
        val signIn: SignIn,
        val rules: List<SubscriptionRule>,
        val agreed: List<SubscriptionRule>,
        val save: SaveState,
        val edit: Int,
    )

    /** What the worker has done, counted on it alone, for tests to wait on. Nothing in the app reads it. */
    @VisibleForTesting
    internal class Probe {
        /** Saves begun, each one PUT unless its header is refused first. */
        val savesBegun = AtomicInteger()

        /** Answers and refusals handled, whether they landed or not. */
        val answersHandled = AtomicInteger()

        /** Waiting saves of a sign-in that had ended, dropped unsent. */
        val dropped = AtomicInteger()
    }

    @VisibleForTesting
    internal val probe = Probe()

    /** A save of [rules], the list edit number [edit] left, under [signIn], the sign-in it was shown for. */
    private class Save(val signIn: SignIn, val rules: List<SubscriptionRule>, val edit: Int)

    // Guards waiting and the ledger's writes. Taken inside a sign-in's lock (SignIn.ifCurrent),
    // never around one.
    private val lock = Any()
    private var waiting: Save? = null
    private val _ledger = MutableStateFlow<Ledger?>(null)

    /** The ledger of the sign-in last read or edited; a page shows it while it is its sign-in's. */
    val ledger: StateFlow<Ledger?> = _ledger.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in wake) drain() }
    }

    /**
     * A read of [signIn]'s list answered [rules], the backend's: the ledger takes them as what the
     * backend holds and as the list shown. A ledger of another sign-in, which has ended, gives way.
     */
    fun loaded(signIn: SignIn, rules: List<SubscriptionRule>): Ledger = synchronized(lock) {
        val held = _ledger.value?.takeIf { it.signIn === signIn }
        val next = held?.copy(rules = rules, agreed = rules) ?: Ledger(signIn, rules, rules, SaveState.Idle, 0)
        next.also { _ledger.value = it }
    }

    /**
     * The student's edit of [signIn]'s list left [rules]: the ledger shows them, and their save
     * waits for any save out, in place of an older one waiting.
     */
    fun edit(signIn: SignIn, rules: List<SubscriptionRule>): Ledger {
        val next = synchronized(lock) {
            // A page edits only rules it read, so the ledger is its sign-in's; were it not, there
            // would be nothing to put back but the edit itself.
            val held = _ledger.value?.takeIf { it.signIn === signIn } ?: Ledger(signIn, rules, rules, SaveState.Idle, 0)
            held.copy(rules = rules, save = SaveState.Saving, edit = held.edit + 1).also {
                waiting = Save(signIn, rules, it.edit)
                _ledger.value = it
            }
        }
        wake.trySend(Unit)
        return next
    }

    /** The student has seen how [signIn]'s last save went, so a page opened again does not show it. */
    fun outcomeSeen(signIn: SignIn) = synchronized(lock) {
        val held = _ledger.value?.takeIf { it.signIn === signIn && it.save != SaveState.Saving } ?: return
        _ledger.value = held.copy(save = SaveState.Idle)
    }

    private suspend fun drain() {
        while (true) put(next() ?: return)
    }

    /** The waiting save, taken to send. One of a sign-in that has ended is dropped, never sent. */
    private fun next(): Save? = synchronized(lock) {
        val save = waiting ?: return null
        waiting = null
        if (!save.signIn.isCurrent) {
            probe.dropped.incrementAndGet()
            return null
        }
        save
    }

    /**
     * One PUT of [save]'s list under its sign-in, the only one out; the worker waits for the whole
     * request. Its answer lands only while the save's sign-in stands, in one step with the
     * sign-out's end, and otherwise touches nothing: the ledger may be the next student's by then.
     */
    private suspend fun put(save: Save) {
        probe.savesBegun.incrementAndGet()
        val answer = try {
            Result.success(api.saveSubscriptions(save.rules, save.signIn))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        save.signIn.ifCurrent { synchronized(lock) { settle(save, answer) } }
        probe.answersHandled.incrementAndGet()
    }

    /**
     * The backend's answer is what it holds now ([Ledger.agreed]), and becomes the list shown only
     * if no edit came since the save went; a refusal puts back what the backend last agreed to, on
     * the same condition. A newer edit stays, and its own save follows.
     */
    private fun settle(save: Save, answer: Result<List<SubscriptionRule>>) {
        val held = _ledger.value?.takeIf { it.signIn === save.signIn } ?: return
        val newer = held.edit != save.edit
        _ledger.value = answer.fold(
            onSuccess = { kept ->
                if (newer) held.copy(agreed = kept) else held.copy(rules = kept, agreed = kept, save = SaveState.Saved)
            },
            onFailure = { e ->
                if (newer) held else held.copy(rules = held.agreed, save = SaveState.Failed(e.message ?: "error"))
            },
        )
    }
}

/** The rule with a `client_id` for `/v4`: its own, or a new UUID, made here, for one without. */
internal fun SubscriptionRule.withClientId(): SubscriptionRule =
    if (clientId != null) this else copy(clientId = UUID.randomUUID().toString())
