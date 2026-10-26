package org.ntust.app.tigerduck.auth

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/** A backend session's access token held back from a backend that did not issue it. */
class SessionNotForThisBackend : IOException("session not issued by this backend")

/**
 * Sends a request with a backend session's access token only to the backend that issued it
 * ([AuthTokenManager.mayPresent]), and fails it otherwise, as a request that never left. The
 * last check before a request leaves: an address read before a change of the API endpoint
 * setting, and a header taken after it, or the other way round, never meet.
 *
 * For the clients that take the session's header. The sign-out's unregister names its backend
 * and token itself, from the debt, and goes without it.
 */
class SessionHostGuard(private val authTokenManager: AuthTokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = request.header("Authorization")?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
        if (token != null && !authTokenManager.mayPresent(token, request.url)) throw SessionNotForThisBackend()
        return chain.proceed(request)
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

/**
 * Puts a [SessionHostGuard] ahead of every interceptor the client was built with, so none of them
 * answers or sends the request before it is checked, and on every hop the request takes: within
 * one origin OkHttp keeps the header on a redirect, which the first check never saw.
 */
fun OkHttpClient.Builder.guardedBy(authTokenManager: AuthTokenManager): OkHttpClient.Builder = apply {
    val guard = SessionHostGuard(authTokenManager)
    interceptors().add(0, guard)
    addNetworkInterceptor(guard)
}
