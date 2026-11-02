package org.ntust.app.tigerduck.network.backend

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.network.canonicalBackend

/**
 * The API a backend base speaks, named by the base's last path segment: `…/v4` is TigerSync
 * protocol 2, and every other base is spoken to as `/v3` is, as every base was before phase 3.
 */
enum class BackendApi(val segment: String) {
    V3("v3"),
    V4("v4"),
    ;

    companion object {
        fun of(base: String): BackendApi = if (named(base) == V4) V4 else V3

        /** The API [base]'s last path segment names; null when it names none, or [base] is no URL. */
        internal fun named(base: String): BackendApi? = base.toHttpUrlOrNull()?.let(::named)

        internal fun named(url: HttpUrl): BackendApi? {
            // A base spelled with a trailing slash ends in an empty segment.
            val segments = url.encodedPathSegments.let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
            return entries.firstOrNull { it.segment == segments.last() }
        }
    }
}

/**
 * One backend across its API versions. A token pair `/v3` issued works on `/v4` of the same
 * backend and back (`docs/api-v4.md`, "Auth and refresh"), so what decides where a session or the
 * unregister a sign-out owes may go compares backends without the version segment, and an update
 * that moves the app to `/v4` signs nobody out. As iOS's `BackendClient.apiBase`, the API the app
 * speaks is decided here alone: every base the endpoint setting, a stored session or a stored debt
 * names is moved by [of] as it is sent, and nothing stored is rewritten.
 */
object ApiBase {
    /** The API the app speaks. A base ending in `/v3` moves to it; one ending in `/v4` already speaks it. */
    val TARGET: BackendApi = BackendApi.V3

    /**
     * [server] speaking [target]: a last path segment `v3` becomes [target]'s, and nothing else
     * changes, not even its spelling. A base without a version, or already at `/v4`, is as given.
     */
    fun of(server: String, target: BackendApi = TARGET): String {
        val url = server.toHttpUrlOrNull() ?: return server
        val trimmed = server.trimEnd('/')
        val v3 = BackendApi.V3.segment
        // Checked on the parsed URL as well, so a host named like a version is never taken for one.
        if (BackendApi.named(url) != BackendApi.V3 || !trimmed.endsWith("/$v3")) return server
        return trimmed.dropLast(v3.length) + target.segment + server.substring(trimmed.length)
    }

    /**
     * The backend [base] names whatever its version: its canonical spelling ([canonicalBackend])
     * with a last `v3` or `v4` segment dropped. A base with no version segment is its own key.
     */
    fun backendKey(base: String): String {
        val canonical = canonicalBackend(base)
        val url = canonical.toHttpUrlOrNull() ?: return canonical
        if (BackendApi.named(url) == null) return canonical
        return url.newBuilder().removePathSegment(url.pathSize - 1).build().toString().trimEnd('/')
    }

    /**
     * Whether [a] and [b] are one backend: the same once spelled alike, but for their version
     * segment. A base without one is not the same backend as one with: `…/api` and `…/api/v3` are
     * two address spaces, which nothing before phase 3 took for one either.
     */
    fun sameBackend(a: String, b: String): Boolean =
        backendKey(a) == backendKey(b) && (BackendApi.named(a) == null) == (BackendApi.named(b) == null)

    /**
     * Whether [url] is a request to the backend [base] names: the same scheme, host and port, and a
     * path under [base]'s own, or, for a base with a version segment, under the same prefix at the
     * other version. Nothing else: not the unversioned paths beside it (`/links`, `/health`), and not
     * a path that only starts like it (`/v3x`).
     */
    fun serves(base: String, url: HttpUrl): Boolean {
        val home = base.toHttpUrlOrNull() ?: return false
        if (url.scheme != home.scheme || url.host != home.host || url.port != home.port) return false
        val path = home.encodedPath.trimEnd('/')
        if (url.isUnder(path)) return true
        if (BackendApi.named(home) == null) return false
        val prefix = path.substringBeforeLast('/')
        return BackendApi.entries.any { url.isUnder("$prefix/${it.segment}") }
    }

    private fun HttpUrl.isUnder(path: String): Boolean = encodedPath == path || encodedPath.startsWith("$path/")
}
