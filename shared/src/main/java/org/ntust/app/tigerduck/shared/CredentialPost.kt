package org.ntust.app.tigerduck.shared

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** Where a POST that carries a credential may go. As on the iPhone (`CredentialPost`). */
object CredentialPost {
    /**
     * A host of the school's, over HTTPS: `ntust.edu.tw` or a name under it. The bridge forms that
     * pass a sign-in on to a service, with the ID token or the code the school hands it, post only
     * here.
     */
    fun isSchoolTarget(url: HttpUrl): Boolean =
        url.isHttps && (url.host == SCHOOL_DOMAIN || url.host.endsWith(".$SCHOOL_DOMAIN"))

    private const val SCHOOL_DOMAIN = "ntust.edu.tw"
}

/**
 * Executes [request], a POST that carries a credential (a password, the token a sign-in hands a
 * service, the library's token), so that it goes only where it was addressed, and once. OkHttp
 * follows redirects on its own, and for a 307 or a 308 it sends the same body again to wherever
 * the redirect points; this POST follows none itself, nor is it sent again after a connection
 * failure, or at a 503's asking for it again at once, unless [retryOnConnectionFailure].
 *
 * A 307 or a 308 is followed with the same body only to an address [formMayGoOnTo] allows, and to
 * none unless it says so. A 301, 302 or 303 is followed by hand as a GET, which carries no body,
 * and only to an HTTPS address; the client follows whatever comes after that GET as it always
 * does. Any other redirect, or one with no usable address, returns null, the body not sent again.
 * [run] runs each call: by default it blocks on it. The caller closes the response it gets.
 */
inline fun OkHttpClient.executeCredentialPost(
    request: Request,
    formMayGoOnTo: (HttpUrl) -> Boolean = { false },
    retryOnConnectionFailure: Boolean = false,
    run: (Call) -> Response = { it.execute() },
): Response? {
    val postClient = newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // A connection that drops once the POST is out may have delivered it: sent again, a
        // password would count twice toward the account's lockout.
        .retryOnConnectionFailure(retryOnConnectionFailure)
        .build()
    // A 503 with Retry-After: 0 has OkHttp send it again whatever the client's retry setting.
    var post = if (retryOnConnectionFailure) request else request.sentOnce()
    repeat(MAX_FORM_REDIRECTS + 1) {
        val response = run(postClient.newCall(post))
        if (!response.isRedirect) return response
        response.use {
            val next = it.header("Location")?.let(it.request.url::resolve) ?: return null
            // The request the POST went out as, not the caller's: it carries what the client's
            // interceptors marked it with, such as the account the school sign-in started under
            // (:app's AccountSessionGuard), which a sign-out since then makes stale.
            val sent = it.request
            when {
                it.code in FOLLOWED_AS_GET && next.isHttps ->
                    return run(newCall(sent.newBuilder().url(next).get().build()))
                it.code in FOLLOWED_WITH_FORM && formMayGoOnTo(next) ->
                    post = sent.newBuilder().url(next).build()
                else -> return null
            }
        }
    }
    return null
}

/**
 * This request with its body marked as one OkHttp sends only once. OkHttp sends a request again
 * after some replies, whatever retryOnConnectionFailure says, but none whose body is one-shot.
 */
@PublishedApi
internal fun Request.sentOnce(): Request {
    val sent = body?.takeUnless { it.isOneShot() } ?: return this
    return newBuilder().method(method, object : RequestBody() {
        override fun contentType() = sent.contentType()
        override fun contentLength() = sent.contentLength()
        override fun writeTo(sink: BufferedSink) = sent.writeTo(sink)
        override fun isOneShot() = true
    }).build()
}

/** The redirects a browser follows after a form's POST with a GET, the form left behind. */
@PublishedApi
internal val FOLLOWED_AS_GET = setOf(301, 302, 303)

/** The redirects that keep the method and the body. */
@PublishedApi
internal val FOLLOWED_WITH_FORM = setOf(307, 308)

/** How many times a POST may be sent on before it counts as a loop. */
@PublishedApi
internal const val MAX_FORM_REDIRECTS = 3
