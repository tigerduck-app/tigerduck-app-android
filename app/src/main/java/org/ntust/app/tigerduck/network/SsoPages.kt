package org.ntust.app.tigerduck.network

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.shared.CredentialPost
import org.ntust.app.tigerduck.shared.executeCredentialPost

/**
 * The pages an SSO sign-in walks, on [client]: the app's shared session for [SsoLoginService]'s
 * sign-ins, and a jar of its own for [SsoLoginService.signInForBrowser]. Nothing here carries a
 * password; the sign-in form's POST is [SsoLoginService]'s.
 */
internal class SsoPages(private val client: OkHttpClient) {

    fun fetchPage(url: String): Pair<String, HttpUrl> {
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

    fun resolveOIDCBridgeForms(
        html: String,
        url: HttpUrl,
        maxSteps: Int = 3,
    ): Pair<String, HttpUrl> {
        var currentHtml = html
        var currentUrl = url
        repeat(maxSteps) {
            if (HtmlParser.isSSOLoginPage(currentHtml, currentUrl)) return currentHtml to currentUrl
            val form =
                HtmlParser.findOIDCBridgeForm(currentHtml) ?: return currentHtml to currentUrl
            val actionUrl = resolveUrl(form.action, currentUrl)
            // What the bridge carries passes the sign-in on, so it goes to the school's hosts over
            // HTTPS and nowhere else, whatever the page names as the form's action. As on the
            // iPhone.
            if (!CredentialPost.isSchoolTarget(actionUrl)) throw SsoLoginError.InvalidResponse()
            val (newHtml, newUrl) = postForm(actionUrl, form.inputs)
            currentHtml = newHtml
            currentUrl = newUrl
        }
        return currentHtml to currentUrl
    }

    /**
     * A bridge form's POST, which carries the ID token or the code the school hands the service: no
     * redirect sends the form on, and one that would ends the sign-in ([executeCredentialPost]).
     */
    private fun postForm(url: HttpUrl, fields: List<Pair<String, String>>): Pair<String, HttpUrl> {
        try {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = client.executeCredentialPost(request) ?: throw SsoLoginError.InvalidResponse()
            response.use {
                val html = it.body.string()
                return html to it.request.url
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    fun resolveUrl(path: String, base: HttpUrl): HttpUrl {
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
}
