package org.ntust.app.tigerduck.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

sealed class SsoLoginError : Exception() {
    class LoginFormNotFound : SsoLoginError()
    class LoginFailed : SsoLoginError()
    class InvalidResponse : SsoLoginError()
    data class NetworkError(val cause_: Exception) : SsoLoginError()
}

/**
 * A page behind NTUST SSO, fetched with the session already held when
 * [sessionWarm] says there is one, and with one login and one retry when the
 * request is bounced to SSO anyway.
 *
 * [fetch] returns null for a bounce. [logIn] throws when it cannot log in.
 * Whatever the retry still cannot get past ends in [bounced], as does a
 * bounce after a cold session's login.
 *
 * A login costs a round of the OIDC bridge — the service root, the authorize
 * redirect, the bridge POST — even when the session it checks is fine, so
 * running one ahead of every fetch cost several requests where a warm
 * session needs one.
 * The retry is what makes skipping it safe: [sessionWarm] only says *a*
 * login succeeded within the hour, not that it was to this service.
 */
internal suspend fun <T> fetchWithSsoSession(
    sessionWarm: Boolean,
    logIn: suspend () -> Unit,
    fetch: suspend () -> T?,
    bounced: () -> Nothing,
): T {
    if (!sessionWarm) {
        // A bounce straight after a login of our own is not a stale session,
        // and a second login would only clear the cookies and do it again.
        logIn()
        return fetch() ?: bounced()
    }
    fetch()?.let { return it }
    logIn()
    return fetch() ?: bounced()
}

@Singleton
class SsoLoginService @Inject constructor(
    private val sessionManager: NtustSessionManager
) {
    private val client: OkHttpClient get() = sessionManager.client

    // One login at a time. A login that meets the SSO wall clears the whole
    // cookie jar (step 4) before it signs in, which pulled the session out
    // from under any other login running alongside — and Home, the class
    // table and the calendar each started one at the same moment on launch.
    private val loginMutex = Mutex()

    /**
     * Ensures the user is logged in to the given service via NTUST SSO.
     * Returns true on success, throws SsoLoginError on failure.
     */
    suspend fun ensureServiceLogin(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = loginMutex.withLock { logIn(serviceUrl, studentId, password) }

    /**
     * A sign-in with credentials just entered. [ensureServiceLogin] takes a
     * session the jar already holds as signed in, without submitting the
     * credentials; that is right for the account re-logging itself in, and
     * wrong for someone new, whose sign-in would then pass on the strength
     * of a session left by the account before. So this one empties the jar
     * first. Under the same lock, so a login still running for the account
     * that left finishes before the jar is emptied, not after.
     */
    suspend fun signIn(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = loginMutex.withLock {
        sessionManager.invalidateSession()
        logIn(serviceUrl, studentId, password)
    }

    private suspend fun logIn(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Step 1: Visit service URL
        var (html, url) = fetchPage(serviceUrl)

        // Step 2: Resolve OIDC bridge forms
        val resolved = resolveOIDCBridgeForms(html, url)
        html = resolved.first
        url = resolved.second

        // Step 3: Check if we're on SSO login page
        if (!HtmlParser.isSSOLoginPage(html, url)) {
            sessionManager.markLoginSuccess()
            return@withContext true
        }

        // Step 4: Clear cookies and retry
        sessionManager.invalidateSession()

        val fresh = fetchPage(serviceUrl)
        html = fresh.first
        url = fresh.second

        if (!HtmlParser.isSSOLoginPage(html, url)) {
            // Capture the bridge result instead of discarding it: a bridge
            // POST that lands back on the SSO wall means we aren't actually
            // logged in, so we must not mark success — fall through to the
            // credential submission below.
            val (bridgeHtml, bridgeUrl) = resolveOIDCBridgeForms(html, url)
            if (!HtmlParser.isSSOLoginPage(bridgeHtml, bridgeUrl)) {
                sessionManager.markLoginSuccess()
                return@withContext true
            }
            html = bridgeHtml
            url = bridgeUrl
        }

        // Step 5: Submit login form
        val form = HtmlParser.findFormById(html, "loginForm")
            ?: throw SsoLoginError.LoginFormNotFound()

        val fields = form.inputs.toMutableList().apply {
            replaceOrAppend("Username", studentId)
            replaceOrAppend("Password", password)
            if (none { it.first == "captcha" }) add("captcha" to "")
        }

        val actionUrl = resolveUrl(form.action, url)
        val loginResult = postForm(actionUrl, fields)
        html = loginResult.first
        url = loginResult.second

        // Step 6: Resolve OIDC bridge forms after login
        val afterLogin = resolveOIDCBridgeForms(html, url)
        html = afterLogin.first
        url = afterLogin.second

        // Step 7: Check if still on SSO page
        if (HtmlParser.isSSOLoginPage(html, url)) throw SsoLoginError.LoginFailed()

        sessionManager.markLoginSuccess()
        true
    }

    private fun fetchPage(url: String): Pair<String, HttpUrl> {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                return body to (response.request.url)
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun resolveOIDCBridgeForms(
        html: String,
        url: HttpUrl,
        maxSteps: Int = 3
    ): Pair<String, HttpUrl> {
        var currentHtml = html
        var currentUrl = url
        repeat(maxSteps) {
            if (HtmlParser.isSSOLoginPage(currentHtml, currentUrl)) return currentHtml to currentUrl
            val form =
                HtmlParser.findOIDCBridgeForm(currentHtml) ?: return currentHtml to currentUrl
            val actionUrl = resolveUrl(form.action, currentUrl)
            val (newHtml, newUrl) = postForm(actionUrl, form.inputs)
            currentHtml = newHtml
            currentUrl = newUrl
        }
        return currentHtml to currentUrl
    }

    private fun postForm(url: HttpUrl, fields: List<Pair<String, String>>): Pair<String, HttpUrl> {
        try {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body.string()
                return html to response.request.url
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun resolveUrl(path: String, base: HttpUrl): HttpUrl {
        return try {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path.toHttpUrl()
            } else {
                base.newBuilder(path)?.build() ?: base
            }
        } catch (e: IllegalArgumentException) {
            base
        }
    }

    private fun MutableList<Pair<String, String>>.replaceOrAppend(name: String, value: String) {
        val idx = indexOfFirst { it.first == name }
        if (idx >= 0) this[idx] = name to value
        else add(name to value)
    }
}
