package org.ntust.app.tigerduck.review

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.preferences.ReviewPromptStore
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When to ask for a Play Store rating (tigerduck-app-android#332): once,
 * ever, after the class table has loaded on [DAYS_OF_USE] different days,
 * and at a calm moment — Home or the class table on screen and resumed,
 * with a successful load behind it. Not on a cold start before anything
 * shows, not while signed out, signing in, in the wizard or in the demo
 * account, and not right after a load failed.
 *
 * The Home and class table view models report their fetches
 * ([classTableLoaded], [loadFailed]), their page's resumes ([calmMoment])
 * and its pauses ([withdraw]); `MainActivity` watches [pending], asks Play
 * for the flow through `InAppReview` and comes back with
 * [launchIfStillCalm] or [refused]. Right before the launch the gate is
 * generic rather than a list of dialogs: the page that offered the moment
 * must still be resumed, and the activity's window must have the focus —
 * every dialog, sheet and system prompt is a window of its own and takes
 * it. The days are counted in [ReviewPromptStore], so they survive
 * restarts and upgrades; what happened in this process — a load, a failure,
 * an attempt — is kept here, so a new process has to see the class table
 * load again before it asks.
 *
 * Flavor-neutral: on fdroid `InAppReview` is a stub that never hands out a
 * flow, so nothing is launched or recorded as asked there.
 */
@Singleton
class ReviewPrompt(
    private val store: ReviewPromptStore,
    private val today: () -> LocalDate,
    private val mayAskNow: () -> Boolean,
) {
    @Inject
    constructor(
        store: ReviewPromptStore,
        authService: AuthService,
        appState: AppState,
        demoAccount: DemoAccount,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        store = store,
        // The app's clock, so a debug date moves the days with everything else.
        today = { AppClock.localDateTime().toLocalDate() },
        mayAskNow = {
            authService.authState.value &&
                !authService.isLoggingIn.value &&
                !appState.ntustSignInSheetRequested &&
                !appState.showOnboarding &&
                !demoAccount.isActive &&
                // A red status dot is an error still on screen.
                ServerStatusTracker.statuses.value.values.none { it == ServerStatus.FAILED }
        },
    ) {
        // The gates that can flip while an ask is pending — a sign-out, a
        // sign-in starting, the wizard, a source failing — withdraw it as
        // they flip, rather than leaving the host to launch a flow that no
        // longer may. The demo account comes and goes with authState. What
        // is not a flow is covered by the check right before the launch,
        // in launchIfStillCalm.
        scope.launch {
            merge<Any>(
                authService.authState,
                authService.isLoggingIn,
                ServerStatusTracker.statuses,
                snapshotFlow { appState.ntustSignInSheetRequested || appState.showOnboarding },
            ).collect { recheck() }
        }
    }

    private val _pending = MutableStateFlow(false)

    /** Whether the flow is to be requested now; answered with [launchIfStillCalm] or [refused]. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    // The page that offered the pending moment: whether it is still on
    // screen and resumed, asked right before the launch. Null while nothing
    // is pending.
    private var pageResumed: (() -> Boolean)? = null

    // This process only: whether the class table has loaded with no failure
    // since, and whether the flow was already attempted. Both go with the
    // process on purpose — see the class comment.
    @Volatile
    private var loaded = false

    @Volatile
    private var attempted = false

    /**
     * The class table's courses were fetched and saved. Counts today once,
     * however many times that happens today.
     */
    @Synchronized
    fun classTableLoaded() {
        loaded = true
        val day = today().toString()
        if (store.reviewPromptLastDay == day) return
        store.reviewPromptDaysUsed = store.reviewPromptDaysUsed + 1
        store.reviewPromptLastDay = day
    }

    /**
     * A fetch failed, or could not start. Not a moment to ask, and an ask
     * not launched yet is withdrawn, until a load goes through again.
     */
    @Synchronized
    fun loadFailed() {
        loaded = false
        withdrawPending()
    }

    /**
     * Home or the class table is on screen and resumed: ask now if
     * everything says so. [pageResumed] is that page's own answer, asked
     * again right before the launch, to whether it still is.
     */
    @Synchronized
    fun calmMoment(pageResumed: () -> Boolean) {
        if (attempted || !loaded || store.reviewPromptAsked) return
        if (store.reviewPromptDaysUsed < DAYS_OF_USE) return
        if (!mayAskNow()) return
        this.pageResumed = pageResumed
        _pending.value = true
    }

    /**
     * The page that offered the moment paused — a background trip, a system
     * prompt — or was left: the ask must not outlive it. Not an attempt; the
     * page's next resume may offer the moment again.
     */
    @Synchronized
    fun withdraw() = withdrawPending()

    /** Withdraws a pending ask whose gates no longer hold. Never arms one: that is [calmMoment]'s. */
    @Synchronized
    fun recheck() {
        if (_pending.value && (!loaded || !mayAskNow())) withdrawPending()
    }

    private fun withdrawPending() {
        pageResumed = null
        _pending.value = false
    }

    /**
     * Play handed out the flow: starts it through [launch], but only if the
     * moment still holds. The request was a round trip to Play, in which the
     * user may have signed out or opened a sign-in, a source may have
     * failed, the page may have been paused or left, a dialog or sheet may
     * have opened — [windowFocused] is false while one is up, whatever page
     * owns it — or [loadFailed] may have withdrawn the ask; then the flow is
     * left unlaunched and the ask withdrawn, which is not an attempt — a
     * later calm moment asks again. A launch that started is the one
     * "asked" the API gives — whether the dialog showed is not said — and
     * is recorded for good; one that would not start is this process's one
     * attempt.
     */
    @Synchronized
    fun launchIfStillCalm(windowFocused: () -> Boolean, launch: () -> Boolean) {
        if (!_pending.value) return
        val stillCalm = loaded && !store.reviewPromptAsked && mayAskNow() &&
            pageResumed?.invoke() == true && windowFocused()
        if (!stillCalm) {
            withdrawPending()
            return
        }
        attempted = true
        withdrawPending()
        if (launch()) store.reviewPromptAsked = true
    }

    /**
     * Play would not hand out a flow — no Play Store, a sideloaded install,
     * offline. Nothing is recorded, and it is not tried again before the
     * next process.
     */
    @Synchronized
    fun refused() {
        attempted = true
        withdrawPending()
    }

    companion object {
        /** Calendar days on which the class table must have loaded before the one ask. */
        const val DAYS_OF_USE = 5
    }
}
