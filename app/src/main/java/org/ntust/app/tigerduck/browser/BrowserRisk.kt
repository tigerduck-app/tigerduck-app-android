package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.shared.tls.pinHostName
import java.security.MessageDigest

/**
 * A page the browser refused for its certificate, which the student may open at their own risk
 * (tigerduck-app#958): its address, and [leaf], the SHA-256 of the leaf certificate its host served
 * at the refusal, as lower-case hex.
 */
data class CertificateRefusal(val url: String, val leaf: String) {
    /** The refused page's host, as the pin matcher names hosts; null for an address that doesn't read. */
    val host: String? get() = hostOf(url)

    /**
     * What opening the refused page at risk loads. The app signs in to the hosts of
     * [BrowserSignInHosts] and posts credentials to them, so any part of their addresses may hold one,
     * and only their front page opens. Any other host keeps its page without the parts that could
     * carry a credential: the fragment, each path segment's `;` parameters, and each query item whose
     * name, decoded and lowercased, holds one of [CREDENTIAL_WORDS]. No user name or password either.
     */
    val pageToOpen: String?
        get() {
            val page = url.toHttpUrlOrNull() ?: return null
            val host = pinHostName(page.host)
            if (host in BrowserSignInHosts) return "https://$host/"
            val path = page.encodedPathSegments.joinToString("/", prefix = "/") { it.substringBefore(';') }
            // One piece of the encoded query per item, as OkHttp splits it; an empty one says nothing.
            val kept = page.encodedQuery?.split('&').orEmpty()
                .filterIndexed { i, piece -> piece.isNotEmpty() && !holdsCredential(page.queryParameterName(i)) }
            return HttpUrl.Builder().scheme(page.scheme).host(page.host).port(page.port).encodedPath(path)
                .encodedQuery(kept.takeIf { it.isNotEmpty() }?.joinToString("&"))
                .build().toString()
        }

    companion object {
        /** Query names that suggest a credential, as the iPhone's list them. */
        val CREDENTIAL_WORDS = listOf(
            "token", "code", "ticket", "key", "saml", "assertion", "secret", "pass", "session", "sid", "hint", "jwt",
            "auth", "verifier", "state", "nonce", "sig", "credential",
        )

        private fun holdsCredential(name: String): Boolean = name.lowercase().let { lower -> CREDENTIAL_WORDS.any { it in lower } }

        /** The SHA-256 of a certificate's DER, as lower-case hex: how a refusal names its leaf. */
        fun leafOf(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

        internal fun hostOf(url: String?): String? = url?.toHttpUrlOrNull()?.host?.let(::pinHostName)
    }
}

/**
 * The refused certificates the student opened anyway in one browser opened at risk
 * ([org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser.openAtRisk]): each a host and the leaf it
 * served at its refusal. Its WebView goes past a certificate error only for one of them, in its own
 * profile ([BrowserRiskProfiles]): never for another host or another leaf, and never in a browser
 * opened any other way, which has none.
 */
class BrowserRiskAcceptance private constructor(private val accepted: Set<Pair<String, String>>) {
    constructor() : this(emptySet())

    /** These and [refusal]'s host and leaf; this one unchanged when its address doesn't read. */
    fun accepting(refusal: CertificateRefusal): BrowserRiskAcceptance =
        refusal.host?.let { BrowserRiskAcceptance(accepted + (it to refusal.leaf)) } ?: this

    /** Whether the certificate error on [url], whose leaf is [leaf] ([CertificateRefusal.leafOf]), is one the student accepted. */
    fun allows(url: String?, leaf: String?): Boolean {
        val host = CertificateRefusal.hostOf(url) ?: return false
        return leaf != null && (host to leaf) in accepted
    }

    /** As an intent carries it: one `host leaf` line for each. */
    fun encoded(): ArrayList<String> = ArrayList(accepted.map { (host, leaf) -> "$host $leaf" })

    companion object {
        private val LEAF = Regex("[0-9a-f]{64}")

        /** What [encoded] made, or null for none at all: a browser not opened at risk. A line that doesn't read is left out. */
        fun decode(lines: List<String>?): BrowserRiskAcceptance? = lines?.let { list ->
            BrowserRiskAcceptance(
                list.mapNotNull { line ->
                    val host = line.substringBefore(' ', "")
                    val leaf = line.substringAfter(' ', "")
                    (host to leaf).takeIf { host.isNotEmpty() && host == pinHostName(host) && LEAF.matches(leaf) }
                }.toSet(),
            )
        }
    }
}
