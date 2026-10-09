package org.ntust.app.tigerduck.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps an account's requests out of the session of whoever signs in after
 * it.
 *
 * Sign-out clears the cookie jar, but a request already on its way is a
 * blocking OkHttp call that cancelling its coroutine does not stop. An SSO
 * login caught mid-way went on through its remaining hops after the clear,
 * put the departing account's session cookies back in the jar, and the next
 * account's login found that session already signed in and used it without
 * submitting its own credentials.
 *
 * Every call is marked with the account it started under. Once the account
 * has moved on, a call from before is refused its next network hop, and a
 * response that arrives after the move keeps its cookies out of the jar.
 */
internal class AccountSessionGuard {

    private class StartedUnder(val account: Int)

    private val account = AtomicInteger()

    /** Sign-out: every call already running now belongs to the account that left. */
    fun nextAccount() {
        account.incrementAndGet()
    }

    /** Application interceptor: marks a call with the account it starts under. */
    val markCall = Interceptor { chain -> chain.proceed(marked(chain.request())) }

    /**
     * Network interceptor, so it sees each hop of a redirect chain and runs
     * before the response's cookies are saved. Refuses with an
     * InterruptedIOException, which OkHttp does not retry on another route.
     */
    val guardExchange = Interceptor { chain ->
        val request = chain.request()
        if (isStale(request)) {
            throw InterruptedIOException("the account this request was for has signed out")
        }
        withoutStaleCookies(request, chain.proceed(request))
    }

    internal fun marked(request: Request): Request =
        if (request.tag(StartedUnder::class.java) != null) request
        else request.newBuilder().tag(StartedUnder::class.java, StartedUnder(account.get())).build()

    /** Whether [request] started under an account that has since signed out. */
    internal fun isStale(request: Request): Boolean =
        request.tag(StartedUnder::class.java)?.let { it.account != account.get() } == true

    internal fun withoutStaleCookies(request: Request, response: Response): Response =
        if (isStale(request)) response.newBuilder().removeHeader("Set-Cookie").build() else response
}
