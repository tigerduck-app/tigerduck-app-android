package org.ntust.app.tigerduck.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * The TigerSync switch's PATCHes, one at a time: a flip waits for the PATCH
 * before it, so the server keeps the latest. Sent at once, an off and an on
 * in quick succession could land in either order. A flip a later one
 * overtook before it went is not sent at all.
 */
internal class SwitchPatches(private val patch: suspend (enabled: Boolean, signIn: SignIn) -> Unit) {
    private val requests = java.util.concurrent.atomic.AtomicInteger()
    private val mutex = Mutex()

    /** A flip asked for; the number [send] and [isLatest] take. */
    fun request(): Int = requests.incrementAndGet()

    /** Whether no flip has been asked for since [request]. */
    fun isLatest(request: Int): Boolean = requests.get() == request

    /**
     * Sends [enabled] for [request] once the PATCHes before it have ended,
     * unless a later flip was asked for meanwhile, which sends its own. It
     * goes under [signIn], the sign-in the flip was made in, however long it
     * waited. Returns whether it sent; throws what the PATCH throws.
     */
    suspend fun send(request: Int, enabled: Boolean, signIn: SignIn): Boolean = mutex.withLock {
        if (!isLatest(request)) return@withLock false
        patch(enabled, signIn)
        true
    }
}
