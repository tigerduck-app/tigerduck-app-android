package org.ntust.app.tigerduck.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import java.net.URI

/**
 * Resolved base URL for the Announcement API plus a flag indicating whether
 * the persisted override is actually what we're using.
 *
 * The flag matters for the endpoint screen: a stored override that
 * [OverrideValidator] now rejects (e.g. set by `adb`, by an older build's
 * looser validator, or by hand-editing prefs) will fall back to the default
 * URL, and the UI must not claim the override is "active" in that case.
 */
internal data class ResolvedAnnouncementEndpoint(
    val url: String,
    val overrideApplied: Boolean,
)

/**
 * Resolves the Announcement-API base URL with the same policy the save
 * screen enforces. Honoured by every build, matching iOS
 * `PushServerConfig.resolveServerURL`: the row lives in Settings → Other
 * settings, not behind the debug gate.
 *
 * The backend is open source and self-hostable, so any host is accepted —
 * what [OverrideValidator] enforces is transport, not identity. Since this
 * re-validates on every read rather than trusting what was stored, a value
 * that no longer passes (say a plain-HTTP public host saved by an older
 * build) falls back to the default here instead of leaking Bearer
 * credentials over cleartext.
 *
 * Used by both [BulletinApiClient] (to pick the URL for the next request)
 * and the API-endpoint screen (so its "effective endpoint" line and
 * "active override" status cannot diverge from what the client actually
 * sends).
 */
internal fun resolveAnnouncementEndpoint(prefs: AppPreferences): ResolvedAnnouncementEndpoint {
    val default = BuildConfig.PUSH_BASE_URL.trimEnd('/')
    val override = prefs.announcementApiBaseUrlOverride?.trimEnd('/')
        ?: return ResolvedAnnouncementEndpoint(default, overrideApplied = false)
    val ok = OverrideValidator.validate(override) as? OverrideValidator.Result.Ok
        ?: return ResolvedAnnouncementEndpoint(default, overrideApplied = false)
    val normalized = ok.normalized.trimEnd('/')
    // Belt-and-suspenders: even after validation passes, OkHttp's parser
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
 * the two platforms accept the same backends.
 *
 * There is no host allowlist: TigerDuck's backend is self-hostable and
 * pointing the app at your own deployment is a supported setting. The rule
 * is about **transport**:
 *
 * - Private / loopback / link-local addresses (see [isPrivateOrLoopbackHost])
 *   accept either scheme, and `https://` is rewritten to `http://` because a
 *   LAN backend usually terminates no TLS and the traffic never leaves the
 *   local link.
 * - Everything else — public IP literals and hostnames alike — must speak
 *   HTTPS. These requests carry a Bearer token, and cleartext to a routable
 *   address puts it on the wire.
 *
 * The tradeoff this replaced: the old `*.api.tigerduck.app` allowlist also
 * meant a prefs value seeded by a restored backup couldn't redirect the app
 * anywhere interesting. Self-hosting requires giving that up; the HTTPS
 * floor and the pre-save health probe are what remain.
 */
internal object OverrideValidator {

    sealed interface Result {
        data class Ok(val normalized: String, val rewrittenToHttp: Boolean) : Result
        /** Not parseable as a URL with a scheme and a host. */
        data object Malformed : Result
        /** Parsed, but points at a routable address over plain HTTP. */
        data object Insecure : Result
    }

    fun validate(raw: String): Result {
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return Result.Malformed
        val rawScheme = parsed.scheme ?: return Result.Malformed
        val scheme = rawScheme.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Malformed

        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) return Result.Malformed

        // OkHttp rejects ports outside 1..65535 at HttpUrl construction; URI
        // accepts wider values. Reject early so we don't persist an override
        // that crashes every subsequent request.
        val port = parsed.port
        if (port != -1 && port !in 1..65535) return Result.Malformed

        val isLocal = isPrivateOrLoopbackHost(host)
        if (!isLocal && scheme != "https") return Result.Insecure

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

    /**
     * True when [host] is an address that cannot be routed off the local
     * network, and so may be talked to over plain HTTP.
     *
     * Covers `localhost` (and RFC 6761's `*.localhost`), IPv4 loopback /
     * RFC1918 / link-local, and the IPv6 equivalents — `::1`, unique-local
     * `fc00::/7`, link-local `fe80::/10`, plus IPv4-mapped forms like
     * `::ffff:192.168.1.5`, which resolve to an IPv4 address and must be
     * judged by that address rather than waved through.
     *
     * Deliberately excluded: `100.64.0.0/10` (CGNAT) is carrier-routable,
     * and `*.local` mDNS names, which are link-local in practice but are
     * names rather than the IP ranges this rule is specified in terms of.
     */
    fun isPrivateOrLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        // `URI.getHost()` keeps the brackets on an IPv6 literal.
        val bare = normalized.removeSurrounding("[", "]")
        parseIpv4(bare)?.let { return isPrivateIpv4(it) }
        if (bare.contains(':')) return isPrivateIpv6(bare)
        return false
    }

    private fun isPrivateIpv4(octets: IntArray): Boolean = when {
        octets[0] == 10 -> true
        octets[0] == 127 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        octets[0] == 169 && octets[1] == 254 -> true
        else -> false
    }

    /**
     * Strict dotted-quad: exactly 4 decimal octets in 0..255 with NO leading
     * zeros. Leading zeros are rejected because some resolvers read
     * `0192.168.1.5` as octal, which would let a crafted literal classify as
     * private here and resolve somewhere else entirely.
     */
    private fun parseIpv4(host: String): IntArray? {
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

    private fun isPrivateIpv6(host: String): Boolean {
        // Drop any zone id (`fe80::1%wlan0`) before parsing.
        val withoutZone = host.substringBefore('%')
        val bytes = parseIpv6(withoutZone) ?: return false

        // ::1 — loopback.
        if (bytes.take(15).all { it == 0 } && bytes[15] == 1) return true
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d): judge
        // by the embedded IPv4 address, not by the v6 wrapper.
        if (bytes.take(10).all { it == 0 }) {
            val mapped = bytes[10] == 0xff && bytes[11] == 0xff
            val compatible = bytes[10] == 0 && bytes[11] == 0
            if (mapped || compatible) {
                return isPrivateIpv4(intArrayOf(bytes[12], bytes[13], bytes[14], bytes[15]))
            }
        }
        // fc00::/7 — unique local.
        if (bytes[0] and 0xfe == 0xfc) return true
        // fe80::/10 — link local.
        if (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80) return true
        return false
    }

    /**
     * Parses an IPv6 literal into 16 unsigned bytes, handling the `::`
     * compressed form and a trailing embedded IPv4 quad.
     *
     * Hand-rolled rather than delegating to `InetAddress` / `InetAddresses`:
     * the former would resolve a hostname over the network, and the latter
     * is an Android framework class that is not mocked in JVM unit tests —
     * and this classification is exactly the part worth testing.
     */
    private fun parseIpv6(host: String): IntArray? {
        if (host.isEmpty() || host.count { it == ':' } < 2) return null
        var body = host
        var tail: IntArray? = null

        // Trailing dotted quad, as in `::ffff:192.168.1.5`.
        val lastColon = body.lastIndexOf(':')
        if (body.indexOf('.') > lastColon) {
            tail = parseIpv4(body.substring(lastColon + 1)) ?: return null
            body = body.substring(0, lastColon)
        }
        val groupsNeeded = if (tail != null) 6 else 8

        val doubleColon = body.indexOf("::")
        if (doubleColon != body.lastIndexOf("::")) return null

        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(":").map { piece ->
                if (piece.isEmpty() || piece.length > 4) return null
                piece.toIntOrNull(16) ?: return null
            }
        }

        val head: List<Int>
        val rest: List<Int>
        if (doubleColon >= 0) {
            head = groups(body.substring(0, doubleColon)) ?: return null
            rest = groups(body.substring(doubleColon + 2)) ?: return null
            if (head.size + rest.size > groupsNeeded) return null
        } else {
            head = groups(body) ?: return null
            rest = emptyList()
            if (head.size != groupsNeeded) return null
        }

        val filled = IntArray(groupsNeeded)
        head.forEachIndexed { i, value -> filled[i] = value }
        rest.forEachIndexed { i, value -> filled[groupsNeeded - rest.size + i] = value }

        val bytes = IntArray(16)
        for (i in 0 until groupsNeeded) {
            bytes[i * 2] = (filled[i] shr 8) and 0xff
            bytes[i * 2 + 1] = filled[i] and 0xff
        }
        tail?.forEachIndexed { i, octet -> bytes[12 + i] = octet }
        return bytes
    }
}
