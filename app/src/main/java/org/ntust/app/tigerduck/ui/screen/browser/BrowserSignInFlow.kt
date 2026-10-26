package org.ntust.app.tigerduck.ui.screen.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.browser.BrowserSignIn
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.ui.AppProcessing

/**
 * The browser's sign-in screen and the sign-ins it runs, apart from the activity so a test can
 * run it. [load] loads a page the app chose, on the page's own terms; [stopLoading] stops the
 * one under way.
 *
 * As the iPhone's: a link the site signs the browser in with comes first; where none can be had,
 * the app signs in itself ([BrowserSignIn.signInWithoutLink]) behind [processing]'s spinner, as
 * often as the site's provider allows by itself ([BrowserSignIn.maySignInWithoutLink]); and only
 * when neither works does the screen say the automatic sign-in failed, with Retry, which may try
 * the app's own sign-in at once. Nothing is tried again by itself after that, so a site that will
 * not keep the session ends on the screen rather than loop.
 */
internal class BrowserSignInFlow(
    private val state: BrowserPageState,
    private val loads: BrowserLoads,
    private val signIns: BrowserSignIns,
    private val scope: CoroutineScope,
    private val load: (String) -> Unit,
    private val stopLoading: () -> Unit,
    private val processing: AppProcessing = AppProcessing(),
) {
    private var job: Job? = null

    /** The page Retry was tapped for, whose next try is the student's own. */
    private var byHandFor: String? = null

    /** Signs the browser in to [target]'s site through [provider] and loads [target]; the screen when it cannot. */
    fun signIn(provider: BrowserSignIn, target: String) {
        loads.signInTarget = target
        // No page yet: the bars name the one on its way.
        if (state.url == null) state.url = target
        state.signIn = SignInState(provider.siteName, target, working = true)
        restart { attempt(provider, target) }
    }

    /**
     * A site sent the browser to its own sign-in page, [url]: signed out there. Signs in again at
     * once, back to the page the sign-in page came in the way of. A link the site bounces back here
     * does not loop: Moodle's next is 6 minutes after the last, and the app's own sign-in runs by
     * itself only as often as the provider allows. Reached from [from], the page shown, where the
     * provider does not sign in by itself ([BrowserSignIn.signsInFrom]), the sign-in screen waits
     * for the student's Retry.
     */
    fun signInPageReached(provider: BrowserSignIn, url: String, from: String? = null) {
        stopLoading()
        // A sign-in on its way loads nothing till it is done: this is the same navigation again.
        if (state.signIn?.working == true) return
        val target = loads.signInReturn(url)
        if (!provider.signsInFrom(from)) {
            loads.signInTarget = target
            if (state.url == null) state.url = target
            state.signIn = SignInState(provider.siteName, target)
            return
        }
        signIn(provider, target)
    }

    /** Retry on the screen. */
    fun retry() {
        val target = state.signIn?.target ?: return
        val provider = signIns.forUrl(target) ?: return
        // Asked for by hand: the app may sign in itself again at once.
        byHandFor = target
        signIn(provider, target)
    }

    /** The student left the sign-in, by Back or Forward: its try stops, and it is forgotten. */
    fun leave() {
        job?.cancel()
        state.signIn = null
        loads.signInTarget = null
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun attempt(provider: BrowserSignIn, target: String) {
        val byHand = byHandFor == target
        byHandFor = null
        val signedIn = provider.signedInUrl(target)
        if (signedIn != null) {
            state.signIn = null
            load(signedIn)
        } else if (provider.maySignInWithoutLink(target, byHand) && signInByApp(provider, target, byHand)) {
            state.signIn = null
            load(target)
        } else {
            loads.signInTarget = target
            state.signIn = SignInState(provider.siteName, target)
        }
    }

    /** The app's own sign-in, behind the spinner, which lets go however it ends. */
    private suspend fun signInByApp(provider: BrowserSignIn, target: String, byHand: Boolean): Boolean {
        val hold = processing.newHold()
        hold.start()
        return try {
            provider.signInWithoutLink(target, byHand)
        } finally {
            hold.stop()
        }
    }

    private fun restart(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }
}
