package org.ntust.app.tigerduck.browser

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.util.logSummary

/**
 * The TigerDuck browser's Moodle session from the app's own sign-in to Moodle's website ([run],
 * MoodleTokenService.signInToWebsite), for when Moodle has no autologin key to give: Moodle allows
 * one every 6 minutes. The run uses the school SSO session the app already holds and posts no
 * password; the browser gets Moodle's session cookies, and nothing else of the run
 * ([writeCookie], then [flush]).
 *
 * Nothing runs for the demo account or while the browser cannot be used ([usable]: a clearing is
 * owed), and nothing is kept of a run a sign-out or an account change overtook ([generation],
 * which a clearing moves) or that [usable] no longer allows, or that took longer than [timeoutMs].
 *
 * By itself it runs at most once every [INTERVAL_MS], for every browser screen alike, a run that
 * failed included, so Moodle not keeping the session it gets never sends the browser round and
 * round; asked for by hand (Retry) it may run at once. The count starts afresh for another
 * sign-in.
 */
class MoodleWebSessionSignIn(
    private val run: suspend () -> List<Cookie>,
    private val isDemo: () -> Boolean,
    private val usable: () -> Boolean,
    private val generation: () -> Int,
    private val writeCookie: (url: String, value: String) -> Unit,
    private val flush: () -> Unit,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    /** When it last ran by [now], and for which [generation]; null for never. */
    @Volatile private var lastRun: Pair<Long, Int>? = null

    /** Whether [signIn] may run now: by itself once every [INTERVAL_MS], [byHand] at once. */
    fun maySignIn(byHand: Boolean = false): Boolean {
        if (isDemo() || !usable()) return false
        if (byHand) return true
        val (at, forGeneration) = lastRun ?: return true
        return forGeneration != generation() || now() - at >= INTERVAL_MS
    }

    /** Whether the browser now holds a Moodle session from the app's own sign-in. */
    suspend fun signIn(byHand: Boolean = false): Boolean {
        if (!maySignIn(byHand)) return false
        val before = generation()
        lastRun = now() to before
        val cookies = try {
            withTimeoutOrNull(timeoutMs) { run() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Moodle's website sign-in failed: ${e.logSummary()}")
            null
        } ?: return false
        if (generation() != before || !usable()) return false
        val session = cookies.filter { it.matches(SITE) && (it.name.startsWith(SESSION) || it.name.startsWith(REMEMBERED)) }
        if (session.none { it.name.startsWith(SESSION) }) return false
        session.forEach { writeCookie(MoodleWebservice.SITE, it.forBrowser()) }
        flush()
        return true
    }

    /** As Moodle set it, sent over https alone whatever it said. */
    private fun Cookie.forBrowser(): String = if (secure) toString() else "$this; secure"

    private companion object {
        const val TAG = "MoodleWebSession"

        /** Longer than a key's wait, shorter than a student waits on a spinner. */
        const val TIMEOUT_MS = 15_000L

        /** As Moodle gives its autologin keys. */
        const val INTERVAL_MS = 360_000L

        const val SESSION = "MoodleSession"
        const val REMEMBERED = "MOODLEID1_"
        val SITE = "${MoodleWebservice.SITE}/".toHttpUrl()
    }
}
