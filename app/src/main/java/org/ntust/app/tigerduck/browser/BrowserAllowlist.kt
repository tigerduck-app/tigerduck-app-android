package org.ntust.app.tigerduck.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One line of the TigerDuck browser's allowlist: a host on its own
 * ([Exact], `moodle2.ntust.edu.tw`) or every subdomain of a domain
 * ([Wildcard], `*.ntust.edu.tw`).
 */
sealed interface AllowlistEntry {
    /** The entry as it is stored and shown. */
    val text: String

    /** Whether [host], lower case, is one this entry lets in. */
    fun matches(host: String): Boolean

    /** Only [host] itself. */
    data class Exact(val host: String) : AllowlistEntry {
        override val text: String get() = host
        override fun matches(host: String): Boolean = host == this.host
    }

    /**
     * Every subdomain of [domain], at any depth, but not [domain] itself:
     * `*.ntust.edu.tw` lets in `moodle2.ntust.edu.tw` and `a.b.ntust.edu.tw`,
     * not `ntust.edu.tw`. A host that should open too needs an [Exact] entry.
     */
    data class Wildcard(val domain: String) : AllowlistEntry {
        override val text: String get() = "$WILDCARD_PREFIX$domain"
        override fun matches(host: String): Boolean = host.endsWith(".$domain")
    }

    companion object {
        const val WILDCARD_PREFIX = "*."

        // A DNS label: letters, digits and inner hyphens.
        private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

        /**
         * [text] as an entry, or null when it is not one: a host, or `*.`
         * and a domain of at least two labels (`*.tw` would let in a whole
         * country). Case and surrounding spaces are ignored; a scheme, port,
         * path or a `*` anywhere but the front is refused rather than guessed
         * at.
         */
        fun parse(text: String): AllowlistEntry? {
            val clean = text.trim().lowercase()
            val wildcard = clean.startsWith(WILDCARD_PREFIX)
            val name = if (wildcard) clean.removePrefix(WILDCARD_PREFIX) else clean
            val labels = name.split('.')
            if (name.isEmpty() || name.length > 253 || labels.any { !LABEL.matches(it) }) return null
            if (wildcard && labels.size < 2) return null
            return if (wildcard) Wildcard(name) else Exact(name)
        }
    }
}

/**
 * Which pages load inside the TigerDuck browser: https pages on the default
 * port, with no user in the address, on a host one of [entries] lets in, or
 * any such page when [allowEverySite] (the developer switch) is on.
 */
class BrowserAllowlist(
    val entries: List<AllowlistEntry>,
    val allowEverySite: Boolean = false,
) {
    /** Whether [url] may load inside. */
    fun allows(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.port != 443) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (allowEverySite) return true
        val host = parsed.host.lowercase()
        return entries.any { it.matches(host) }
    }

    /** Whether [entry] adds nothing: an exact host a wildcard entry already lets in. */
    fun isRedundant(entry: AllowlistEntry): Boolean =
        entry is AllowlistEntry.Exact && entries.any { it is AllowlistEntry.Wildcard && it.matches(entry.host) }

    companion object {
        /** What a release build always uses: the school's sites. */
        val DEFAULT_ENTRIES: List<AllowlistEntry> = listOf(AllowlistEntry.Wildcard("ntust.edu.tw"))

        val DEFAULT = BrowserAllowlist(DEFAULT_ENTRIES)
    }
}
