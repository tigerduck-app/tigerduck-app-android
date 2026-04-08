package org.ntust.app.tigerduck.network

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

sealed class SsoLoginError : Exception() {
    object LoginFormNotFound : SsoLoginError()
    object LoginFailed : SsoLoginError()
    object InvalidResponse : SsoLoginError()
    data class NetworkError(val cause_: Exception) : SsoLoginError()
}

@Singleton
class SsoLoginService @Inject constructor(
    private val sessionManager: NtustSessionManager
) {
    private val client: OkHttpClient get() = sessionManager.client

    /**
     * Ensures the user is logged in to the given service via NTUST SSO.
     * Returns true on success, throws SsoLoginError on failure.
     */
    suspend fun ensureServiceLogin(
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
            val bridged = resolveOIDCBridgeForms(html, url)
            html = bridged.first
            url = bridged.second
            sessionManager.markLoginSuccess()
            return@withContext true
        }

        // Step 5: Submit login form
        val form = HtmlParser.findFormById(html, "loginForm")
            ?: throw SsoLoginError.LoginFormNotFound

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
        if (HtmlParser.isSSOLoginPage(html, url)) throw SsoLoginError.LoginFailed

        sessionManager.markLoginSuccess()
        true
    }

    private fun fetchPage(url: String): Pair<String, HttpUrl> {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw SsoLoginError.InvalidResponse
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
            val form = HtmlParser.findOIDCBridgeForm(currentHtml) ?: return currentHtml to currentUrl
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
                val html = response.body?.string() ?: throw SsoLoginError.InvalidResponse
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
