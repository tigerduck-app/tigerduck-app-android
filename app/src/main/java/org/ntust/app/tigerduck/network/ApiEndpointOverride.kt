package org.ntust.app.tigerduck.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.net.URI

/**
 * Resolved base URL for the Announcement API plus a flag indicating whether
 * the persisted override is actually what we're using.
 *
 * The flag matters for the debug screen: a stale stored override that
 * [OverrideValidator] now rejects (e.g. set by `adb`, by an older build's
 * looser validator, or by hand-editing prefs) will fall back to the default
 * URL, and the UI must not claim the override is "active" in that case.
 */
internal data class ResolvedAnnouncementEndpoint(
    val url: String,
    val overrideApplied: Boolean,
)

/**
 * Resolves the Announcement-API base URL with the same allowlist policy the
 * save screen enforces. Release builds short-circuit to the default — the
 * override pref is debug-gated at the UI layer, so this is defensive only.
 *
 * Used by both [BulletinApiClient] (to pick the URL for the next request)
 * and the debug API-endpoint screen (so its "effective endpoint" line and
 * "active override" status cannot diverge from what the client actually
 * sends). Re-validation here (not just URL parsing) guarantees that a stale
 * value `OverrideValidator` would now reject — say, `http://example.com/v2`
 * left by an older build — cannot leak Bearer credentials to a disallowed host
 * from subscription calls.
 */
internal fun resolveAnnouncementEndpoint(prefs: AppPreferences): ResolvedAnnouncementEndpoint {
    val default = BuildConfig.PUSH_BASE_URL.trimEnd('/')
    if (!BuildConfig.DEBUG) return ResolvedAnnouncementEndpoint(default, overrideApplied = false)
    val override = prefs.announcementApiBaseUrlOverride?.trimEnd('/')
        ?: return ResolvedAnnouncementEndpoint(default, overrideApplied = false)
    val ok = OverrideValidator.validate(override) as? OverrideValidator.Result.Ok
        ?: return ResolvedAnnouncementEndpoint(default, overrideApplied = false)
    val normalized = ok.normalized.trimEnd('/')
    // Belt-and-suspenders: even after the allowlist passes, OkHttp's parser
    // is the source of truth for what `HttpUrl.toHttpUrl()` will accept at
    // request time. Reject anything URI accepts but OkHttp doesn't, so we
    // don't crash every request with no path back to this screen.
    return if (normalized.toHttpUrlOrNull() != null) {
        ResolvedAnnouncementEndpoint(normalized, overrideApplied = true)
    } else {
        ResolvedAnnouncementEndpoint(default, overrideApplied = false)
    }
}

/**
 * Mirrors the iOS `PushServerConfig.isOverrideAllowed` / `normalize` pair so
 * the two platforms accept the same dev backends. Public hosts must be on
 * the `*.api.tigerduck.app` allowlist and speak HTTPS. Loopback / RFC1918
 * accept either scheme; `https://` to those hosts is rewritten to `http://`
 * so the most common LAN typo doesn't fail at the TLS handshake.
 */
internal object OverrideValidator {
    private val publicHostExactAllowlist = setOf("api.tigerduck.app")
    private val publicHostSuffixAllowlist = listOf(".api.tigerduck.app")

    sealed interface Result {
        data class Ok(val normalized: String, val rewrittenToHttp: Boolean) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(raw: String): Result {
        val parsed = runCatching { URI(raw) }.getOrNull()
            ?: return Result.Invalid("URL is malformed.")
        val rawScheme = parsed.scheme
            ?: return Result.Invalid("URL is missing a scheme.")
        val scheme = rawScheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("Scheme must be http or https.")
        }
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) return Result.Invalid("URL is missing a host.")

        // OkHttp rejects ports outside 1..65535 at HttpUrl construction; URI
        // accepts wider values. Reject early so we don't persist an override
        // that crashes every subsequent request.
        val port = parsed.port
        if (port != -1 && port !in 1..65535) {
            return Result.Invalid("Port must be between 1 and 65535.")
        }

        val isLocal = host == "localhost" ||
            host == "[::1]" || host == "::1" ||
            isLoopbackIpv4(host) || isPrivateIpv4(host)
        val isPublic = isAllowedPublicHost(host)

        if (!isLocal && !isPublic) {
            return Result.Invalid(
                "Rejected by allowlist. Only loopback (127.x.x.x / ::1), RFC1918 " +
                    "(10.x / 172.16–31.x / 192.168.x), or *.api.tigerduck.app are accepted.",
            )
        }
        if (isPublic && scheme != "https") {
            return Result.Invalid("Public hosts must use https.")
        }

        // Normalize via string-level scheme swap rather than the 7-arg URI
        // constructor: the latter takes decoded components and re-encodes
        // them, which double-escapes any percent-escapes the user pasted in
        // the path (and silently diverges from the non-rewrite branch).
        val rewrittenToHttp = isLocal && scheme == "https"
        val normalized = when {
            rewrittenToHttp -> "http" + raw.substring(raw.indexOf(':'))
            scheme != rawScheme -> scheme + raw.substring(raw.indexOf(':'))
            else -> raw
        }
        return Result.Ok(normalized = normalized, rewrittenToHttp = rewrittenToHttp)
    }

    private fun isAllowedPublicHost(host: String): Boolean {
        if (host in publicHostExactAllowlist) return true
        return publicHostSuffixAllowlist.any { suffix ->
            // host must be longer than the suffix so the apex isn't
            // double-counted via the suffix branch.
            host.length > suffix.length && host.endsWith(suffix)
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = parseStrictIpv4(host) ?: return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }

    // Whole 127.0.0.0/8 is loopback per RFC 1122, not just 127.0.0.1.
    private fun isLoopbackIpv4(host: String): Boolean {
        val octets = parseStrictIpv4(host) ?: return false
        return octets[0] == 127
    }

    // Strict dotted-quad: exactly 4 decimal octets in 0..255 with NO leading
    // zeros. Rejects forms like "0192.168.1.5" where the resolver may
    // interpret a leading-zero octet as octal (or fail with a confusing
    // 'unknown host' error far from the validator).
    private fun parseStrictIpv4(host: String): IntArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n.toString() != parts[i] || n !in 0..255) return null
            octets[i] = n
        }
        return octets
    }
}
