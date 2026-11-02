package org.ntust.app.tigerduck.ui.screen.announcements

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.SubscriptionRule
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.SaveState
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsViewModel.State
import java.util.UUID

/**
 * `/v4`'s saves of the subscription page's rules: the whole list in one PUT, each rule by the
 * `client_id` this device made for it (`docs/api-v4.md`, "Subscriptions"), as `/v4` has no
 * per-rule route. An edit shows on the page at once, and the list it left is then saved under the
 * sign-in of the rules it edited. A save never reads the page again: a list the page holds after
 * starting over, empty or the next student's, is never the one a waiting save sends.
 */
internal class SubscriptionListSaves(
    scope: CoroutineScope,
    private val api: BulletinApiClient,
    /** The page's state, whose list the saves send and their answers replace. */
    private val page: MutableStateFlow<State>,
    /** Puts an answer to a save of the given sign-in's on the page, only while that sign-in stands. */
    private val answered: (SignIn, () -> Unit) -> Unit,
    private val probe: SubscriptionSettingsViewModel.Probe,
) {
    /** The list as the backend last agreed to it, which a refused save puts back. */
    var acknowledged: List<SubscriptionRule> = emptyList()

    /** A save of [rules], the list an edit left, under [signIn], the sign-in that list was shown for. */
    private class Save(val signIn: SignIn, val rules: List<SubscriptionRule>)

    /**
     * The save waiting to go. One save is out at a time ([put]), and a later edit's save takes the
     * place of one still waiting, so the edits made while another was out are one save, of the
     * list the last of them left.
     */
    private val waiting = Channel<Save>(Channel.CONFLATED)

    init {
        scope.launch { for (save in waiting) put(save) }
    }

    /**
     * [edit] on the page at once, then the whole list saved under [signIn], the sign-in of the
     * rules it edits. A save already out is answered first: one request of a channel at a time, as
     * on iOS (`sync-order`, "One send per channel at a time"; spec §5.3, race 4).
     */
    fun edit(signIn: SignIn, edit: (List<SubscriptionRule>) -> List<SubscriptionRule>) {
        val rules = edit(page.value.rules)
        page.update { it.copy(rules = rules, saveState = SaveState.Saving) }
        waiting.trySend(Save(signIn, rules))
    }

    /**
     * One PUT of [save]'s list under its sign-in. Its answer is what the backend holds now, and
     * replaces the page's list only if no edit came since it went: a newer edit stays on the page,
     * and its own save follows. A refusal puts back what the backend last agreed to, on the same
     * condition. Either lands only while the save's sign-in stands ([answered]), and a save of a
     * sign-in that has ended is never sent: its header is refused first.
     */
    private suspend fun put(save: Save) {
        probe.savesBegun.incrementAndGet()
        val sent = save.rules
        val kept = try {
            api.saveSubscriptions(sent, save.signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            answered(save.signIn) {
                if (page.value.rules == sent) {
                    page.update { it.copy(rules = acknowledged, saveState = SaveState.Failed(e.message ?: "error")) }
                }
            }
            return
        }
        answered(save.signIn) {
            acknowledged = kept
            if (page.value.rules == sent) page.update { it.copy(rules = kept, saveState = SaveState.Saved) }
        }
    }
}

/** The rule with a `client_id` for `/v4`: its own, or a new UUID, made here, for one without. */
internal fun SubscriptionRule.withClientId(): SubscriptionRule =
    if (clientId != null) this else copy(clientId = UUID.randomUUID().toString())
